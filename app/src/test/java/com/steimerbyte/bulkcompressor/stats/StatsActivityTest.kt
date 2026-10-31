package com.steimerbyte.bulkcompressor.stats

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.ui.SegmentBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper

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
    fun `ohne laeufe steht der hinweis`() {
        val activity = seed()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.text_stats_empty).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.list_stats_runs).visibility)
        assertEquals("0 B", activity.findViewById<TextView>(R.id.text_stats_total).text.toString())
        assertEquals("0", activity.findViewById<TextView>(R.id.text_stats_runs).text.toString())
        // Kein Platzhalter im Text: getString ohne Argument laesst %1$d stehen.
        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.text_stats_footer).visibility
        )
    }

    @Test
    fun `fusszeile nennt die grenze ohne platzhalter`() {
        val activity = seed(run(1_700_000_000_000, 1L shl 30))

        val footer = activity.findViewById<TextView>(R.id.text_stats_footer)
        assertEquals(View.VISIBLE, footer.visibility)
        val text = footer.text.toString()
        assertTrue("Platzhalter stehengeblieben: \"$text\"", !text.contains("%"))
        assertTrue("Grenze fehlt: \"$text\"", text.contains(RunHistory.MAX_RUNS.toString()))
    }

    @Test
    fun `summe und durchschnitt kommen aus der historie`() {
        val activity = seed(run(1_700_000_000_000, 1L shl 30), run(1_700_000_100_000, 3L shl 30))

        assertEquals("4,00 GB", activity.findViewById<TextView>(R.id.text_stats_total).text.toString())
        assertEquals("2", activity.findViewById<TextView>(R.id.text_stats_runs).text.toString())
        assertEquals("20", activity.findViewById<TextView>(R.id.text_stats_files).text.toString())
        assertEquals("16", activity.findViewById<TextView>(R.id.text_stats_saved_files).text.toString())
        assertEquals("2,00 GB", activity.findViewById<TextView>(R.id.text_stats_average).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.text_stats_empty).visibility)
    }

    @Test
    fun `neuester lauf steht oben und traegt den vollsten balken`() {
        val activity = seed(run(1_700_000_000_000, 1L shl 30), run(1_700_000_100_000, 4L shl 30))
        val list = activity.findViewById<LinearLayout>(R.id.list_stats_runs)

        assertEquals(2, list.childCount)
        val top = list.getChildAt(0)
        val bottom = list.getChildAt(1)
        assertEquals(
            "−4,00 GB",
            top.findViewById<TextView>(R.id.text_run_saved).text.toString()
        )
        assertEquals(
            "−1,00 GB",
            bottom.findViewById<TextView>(R.id.text_run_saved).text.toString()
        )
        assertEquals(1f, top.findViewById<SegmentBar>(R.id.bar_run).progress, 0.001f)
        assertEquals(0.25f, bottom.findViewById<SegmentBar>(R.id.bar_run).progress, 0.001f)
        assertTrue(
            top.findViewById<TextView>(R.id.text_run_files).text.contains("8 von 10 Dateien")
        )
    }

    @Test
    fun `lauf ohne einsparung steht da und sagt so`() {
        val activity = seed(run(1_700_000_000_000, 0L, files = 4, smaller = 0))
        val list = activity.findViewById<LinearLayout>(R.id.list_stats_runs)

        assertEquals(1, list.childCount)
        assertEquals(
            "nichts gespart",
            list.getChildAt(0).findViewById<TextView>(R.id.text_run_saved).text.toString()
        )
        assertEquals(0f, list.getChildAt(0).findViewById<SegmentBar>(R.id.bar_run).progress, 0.001f)
    }
}
