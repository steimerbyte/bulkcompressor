package com.steimerbyte.bulkcompressor.job

import android.app.Notification
import android.app.NotificationManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun service(): CompressionService =
        Robolectric.buildService(CompressionService::class.java).create().get()

    private fun lastNotification(service: CompressionService): Notification =
        shadowOf(service.getSystemService(NotificationManager::class.java))
            .allNotifications
            .last()

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
