package com.steimerbyte.bulkcompressor.stats

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Die Statistikseite, ohne Geraet: Summe, Zeilen und die eine Zahl, die
 * man leicht falsch herum anzeigt - der neueste Lauf gehoert nach oben.
 */
@RunWith(RobolectricTestRunner::class)
class StatsActivityTest {

    private fun seed(vararg runs: RunRecord): StatsActivity {
        val store = RunHistory.of(RuntimeEnvironment.getApplication())
        store.clear()
        runs.forEach { store.record(it) }
        val activity = Robolectric.buildActivity(StatsActivity::class.java).setup().get()
        // Die Seite liest die Datei im Hintergrund; ohne diese zwei
        // Schritte steht der Test vor dem ersten Bild.
        activity.awaitHistoryForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun run(id: Long, savedBytes: Long, files: Int = 10, smaller: Int = 8) =
        RunRecord(id = id, folder = "Intern / DCIM / Camera", files = files, savedFiles = smaller, savedBytes = savedBytes)

    @Test
    fun `ohne laeufe bleibt der stand leer`() {
        val activity = seed()

        assertEquals(0, activity.history.totals.runs)
        assertEquals(0L, activity.history.totals.savedBytes)
        assertTrue(activity.history.isEmpty)
    }

    @Test
    fun `summe und reihenfolge stimmen`() {
        val activity = seed(
            run(id = 1_700_000_000_000, savedBytes = 3L * 1024 * 1024 * 1024),
            run(id = 1_700_100_000_000, savedBytes = 1L * 1024 * 1024 * 1024)
        )

        assertEquals(2, activity.history.totals.runs)
        assertEquals(4L * 1024 * 1024 * 1024, activity.history.totals.savedBytes)
        assertEquals(
            "Der neueste Lauf gehoert nach oben",
            1_700_100_000_000,
            activity.history.newestFirst.first().id
        )
    }

    @Test
    fun `ein lauf mit einsparung zaehlt als kleiner`() {
        val activity = seed(run(id = 1, savedBytes = 1024, files = 10, smaller = 3))

        assertEquals(10, activity.history.totals.files)
        assertEquals(3, activity.history.totals.savedFiles)
    }
}
