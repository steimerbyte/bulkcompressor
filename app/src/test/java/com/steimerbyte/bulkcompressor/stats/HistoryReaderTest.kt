package com.steimerbyte.bulkcompressor.stats

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Die Trennung selbst: die Datei wird nicht auf dem Haupt-Thread gelesen,
 * und gezeichnet wird nicht auf dem Lese-Thread.
 *
 * Warum eigener Test statt nur ueber die Bildschirme: dort ist nur das
 * Ergebnis sichtbar ("Zahl steht da"), nicht der Thread, auf dem es
 * entstanden ist. Genau der ist hier die Zusage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryReaderTest {

    /** Aufzeichnung der Threads, auf denen gelesen und geliefert wurde. */
    private class Recorder {
        @Volatile var loadThread: String? = null
        @Volatile var deliveredThread: String? = null
        @Volatile var deliveredHistory: History? = null
        val delivered = CountDownLatch(1)
    }

    private fun reader(recorder: Recorder, history: History = History()): HistoryReader =
        HistoryReader(RuntimeEnvironment.getApplication()) {
            recorder.loadThread = Thread.currentThread().name
            history
        }

    @Test
    fun `gelesen wird im hintergrund, geliefert auf dem haupt-thread`() {
        val recorder = Recorder()
        val reader = reader(recorder)

        reader.read { history ->
            recorder.deliveredThread = Thread.currentThread().name
            recorder.deliveredHistory = history
            recorder.delivered.countDown()
        }
        reader.awaitForTest()

        assertEquals(
            "Die Datei muss auf dem Leser-Thread gelesen werden",
            HistoryReader.THREAD_NAME,
            recorder.loadThread
        )
        // Vor dem Abarbeiten des Haupt-Threads darf nichts ankommen.
        assertFalse(
            "Der Rueckruf lief ohne den Haupt-Thread",
            recorder.delivered.await(100, TimeUnit.MILLISECONDS)
        )

        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "Der Rueckruf kommt nicht an",
            recorder.delivered.await(1, TimeUnit.SECONDS)
        )
        assertEquals(
            "Gezeichnet wird auf dem Haupt-Thread",
            Looper.getMainLooper().thread.name,
            recorder.deliveredThread
        )
    }

    @Test
    fun `ein fehler beim lesen liefert einen leeren stand`() {
        val recorder = Recorder()
        val reader = HistoryReader(RuntimeEnvironment.getApplication()) {
            recorder.loadThread = Thread.currentThread().name
            throw IllegalStateException("Datei kaputt")
        }

        reader.read { history ->
            recorder.deliveredHistory = history
            recorder.delivered.countDown()
        }
        reader.awaitForTest()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(recorder.delivered.await(1, TimeUnit.SECONDS))
        assertEquals(History(), recorder.deliveredHistory)
    }
}
