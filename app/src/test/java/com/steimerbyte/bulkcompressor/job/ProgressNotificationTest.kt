package com.steimerbyte.bulkcompressor.job

import android.app.Notification
import android.app.NotificationManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Die Fortschrittsbenachrichtigung selbst, nicht nur ihr Textbaustein:
 * waehrend eines Laufs ist sie die einzige Anzeige ausserhalb der App.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProgressNotificationTest {

    /**
     * Der Bus ist prozessweit und die Tests laufen im selben Prozess:
     * ohne Rueckstellung uebernimmt ein neuer Dienst den Zustand des
     * vorigen Tests und meldet sofort einen Fortschritt, den es hier nicht
     * gibt.
     */
    private fun service(): CompressionService {
        JobBus.reset()
        return Robolectric.buildService(CompressionService::class.java).create().get()
    }

    private fun lastNotification(service: CompressionService): Notification =
        shadowOf(service.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last()

    @Test
    fun `meldung aus dem worker-thread wird erst auf dem haupt-thread gezeichnet`() {
        val service = service()
        val manager = shadowOf(service.getSystemService(NotificationManager::class.java))

        // Der Kompressor meldet aus seinem Worker. Wuerde der Dienst dort
        // direkt zeichnen, fasste ein zweiter Thread Zustand an, den der
        // Haupt-Thread gleichzeitig schreibt.
        val worker = Thread {
            service.onState(
                JobBus.State(running = true, total = 4, done = 1, currentName = "a.mp4", overall = 0.25f)
            )
        }
        worker.start()
        worker.join(2_000)

        assertEquals(
            "Aus dem Worker darf keine Benachrichtigung entstehen",
            emptyList<Notification>(),
            manager.allNotifications.toList()
        )

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "Auf dem Haupt-Thread muss sie dann erscheinen",
            1,
            manager.allNotifications.size
        )
        assertEquals(
            "a.mp4",
            manager.allNotifications.last().extras.getString(Notification.EXTRA_SUB_TEXT)
        )
    }

    @Test
    fun `Fortschrittsmeldung zeigt Arbeitsspeicher neben dem Fortschritt`() {
        val service = service()

        service.onState(
            JobBus.State(running = true, total = 10, done = 4, currentName = "a.mp4", overall = 0.4f)
        )

        val notification = lastNotification(service)
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()

        assertTrue("Arbeitsspeicher fehlt in der Meldung: $text", text.contains("RAM "))
        assertTrue("Dateiangabe fehlt: $text", text.contains("4 / 10"))
        assertEquals("a.mp4", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals(
            40,
            notification.extras.getInt(Notification.EXTRA_PROGRESS)
        )
    }

    @Test
    fun `aufgeklappt stehen Fortschritt und Datei untereinander`() {
        val service = service()

        service.onState(
            JobBus.State(running = true, total = 10, done = 7, currentName = "b.jpg", overall = 0.7f)
        )

        val notification = lastNotification(service)
        val big = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        assertTrue("Aufgeklappte Fassung fehlt: $big", big.contains("RAM "))
        assertTrue("Dateiname fehlt in der aufgeklappten Fassung: $big", big.contains("b.jpg"))
    }
}
