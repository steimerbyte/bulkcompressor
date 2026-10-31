package com.steimerbyte.bulkcompressor

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.ui.StatsBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Prueft die Verdrahtung der Oberflaeche auf der JVM: Layout-Inflation
 * (inklusive Terminal-Panel), die Statusanzeigen der Selektoren und die
 * Logdatei. Ohne diesen Test bliebe ein falscher View-Namen oder ein
 * fehlendes Include erst auf dem Geraet sichtbar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainActivitySmokeTest {

    private fun activity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // Gepostete Aufgaben (Log-Zustellung, Repaint) sofort ausfuehren.
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    @Test
    fun `activity startet und terminal panel existiert`() {
        val activity = activity()

        assertNotNull("Terminal-Panel fehlt im Layout", activity.findViewById(android.R.id.content))
        assertNotNull(activity.findViewById(R.id.terminal_panel))
        assertNotNull(activity.findViewById(R.id.text_active_config))
        assertNotNull(activity.findViewById(R.id.status_video))
    }

    @Test
    fun `aktive konfiguration nennt alle drei bloecke`() {
        val activity = activity()
        val text = activity.findViewById<TextView>(R.id.text_active_config).text.toString()

        assertTrue("Videozeile fehlt:\n$text", text.contains("VIDEO"))
        assertTrue("Bilderzeile fehlt:\n$text", text.contains("BILDER"))
        assertTrue("Ausgabezeile fehlt:\n$text", text.contains("AUSGABE"))
    }

    @Test
    fun `bitratenmodus gibt die aufloesung frei und sperrt sie im aufloesungsmodus`() {
        val activity = activity()
        val modeChips = activity.findViewById<LinearLayout>(R.id.chip_row_mode)
        val seekBitrate = activity.findViewById<SeekBar>(R.id.seek_bitrate)
        val statusResolution = activity.findViewById<TextView>(R.id.status_video_resolution)

        // Werkseinstellung ist der Aufloesungsmodus: Bitrate wirkt nicht.
        assertFalse("Bitrate darf im Aufloesungsmodus nicht bedienbar sein", seekBitrate.isEnabled)
        assertTrue(statusResolution.text.toString().contains("AKTIV"))

        // Auf Bitrate umschalten (Chip 0): umgekehrtes Bild.
        modeChips.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Bitrate muss im Bitratenmodus bedienbar sein", seekBitrate.isEnabled)
        val status = statusResolution.text.toString()
        assertTrue("Auflösung muss als inaktiv markiert sein, war: $status", status.contains("INAKTIV"))
    }

    @Test
    fun `logzeile landet im ringpuffer und in der logdatei`() {
        activity()
        val marker = "smoke-${System.nanoTime()}"
        Log.i("Test", marker)

        assertTrue(
            "Ringpuffer kennt die Zeile nicht",
            Log.snapshot(50).any { it.message.contains(marker) }
        )

        // Die Datei wird auf einem eigenen Thread geschrieben.
        val file = Log.appLogFile()
        assertNotNull("Keine Logdatei", file)
        val deadline = System.currentTimeMillis() + 3000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            found = file != null && file.exists() && file.readText().contains(marker)
            if (!found) Thread.sleep(20)
        }
        assertTrue("Zeile steht nicht in ${file?.absolutePath}", found)
    }

    @Test
    fun `terminal klappt auf und zeigt neue logzeilen`() {
        val activity = activity()
        val scroll = activity.findViewById<android.view.View>(R.id.terminal_scroll)
        val text = activity.findViewById<TextView>(R.id.terminal_text)

        // Startzustand: zugeklappt, Inhalt unsichtbar.
        assertFalse("Terminal startet aufgeklappt", scroll.visibility == android.view.View.VISIBLE)

        val marker = "terminal-${System.nanoTime()}"
        Log.i("Test", marker)
        shadowOf(Looper.getMainLooper()).idle()

        // Aufklappen ueber den Griff.
        activity.findViewById<android.view.View>(R.id.terminal_bar).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Terminal bleibt zugeklappt", scroll.visibility == android.view.View.VISIBLE)
        assertTrue(
            "Logzeile fehlt im Terminal: ${text.text}",
            text.text.toString().contains(marker)
        )
    }

    @Test
    fun `export enthaelt den letzten absturzbericht`() {
        val activity = activity()
        val crashFile = Log.crashLogFile()
        assertNotNull("crash.log Pfad fehlt", crashFile)
        crashFile!!.writeText(
            "Absturz am 31.10.2026\njava.lang.IllegalStateException: probe-crash\n\tat Probe.boom(Probe.kt:1)\n"
        )

        val text = Log.exportText()

        assertTrue("Kopfzeile des Berichts fehlt", text.contains("Letzter Absturzbericht"))
        assertTrue("Stacktrace fehlt im Export", text.contains("probe-crash"))
        crashFile.delete()
    }

    @Test
    fun `export enthaelt kopfzeile und geraet`() {
        activity()
        Log.i("Test", "export-probe")
        val text = Log.exportText()

        assertTrue(text.contains("=== Bulk Compressor Log ==="))
        assertTrue(text.contains("export-probe"))
        assertNotNull(Log.crashLogFile())
    }

    @Test
    fun `tippen auf schalter chip und slider bleibt ohne absturz`() {
        val activity = activity()

        // Jede dieser Beruehrungen spielt einen Klick. Fehlt der
        // SoundPool oder ist die Verdrahtung falsch, faellt es hier auf --
        // auf dem Geraet waere es nur ein fehlender Ton.
        activity.findViewById<View>(R.id.row_timestamps).performClick()
        activity.findViewById<View>(R.id.row_exif).performClick()
        activity.findViewById<View>(R.id.row_sound).performClick()
        activity.findViewById<View>(R.id.row_sound).performClick()

        val modeChips = activity.findViewById<LinearLayout>(R.id.chip_row_mode)
        modeChips.getChildAt(1).performClick()

        val seek = activity.findViewById<SeekBar>(R.id.seek_bitrate)
        shadowOf(seek).onSeekBarChangeListener?.onProgressChanged(seek, 40, true)
        shadowOf(seek).onSeekBarChangeListener?.onStopTrackingTouch(seek)

        shadowOf(Looper.getMainLooper()).idle()

        val markSound = activity.findViewById<TextView>(R.id.mark_sound)
        assertNotNull("Schalter fuer Klickgeraeusche fehlt im Layout", markSound)
    }

    // ------------------------------------------------------------------
    // Speichertafel
    // ------------------------------------------------------------------

    private fun board(activity: MainActivity): StatsBoard = StatsBoard(
        activity,
        activity.findViewById(R.id.stats_board),
        activity.getSharedPreferences(CompressSettings.PREFS, Context.MODE_PRIVATE)
    )

    @Test
    fun `speichertafel zeigt heap und geraetespeicher`() {
        val activity = activity()
        shadowOf(Looper.getMainLooper()).idle()

        val heap = activity.findViewById<TextView>(R.id.text_stats_heap).text.toString()
        val total = activity.findViewById<TextView>(R.id.text_stats_heap_total).text.toString()
        val device = activity.findViewById<TextView>(R.id.text_stats_device).text.toString()
        val state = activity.findViewById<TextView>(R.id.text_stats_memory_state).text.toString()

        assertTrue("Heap-Zeile ohne Einheit: \"$heap\"", heap.endsWith("MB") || heap.endsWith("GB"))
        assertTrue("Gesamtzeile ohne Schraegstrich: \"$total\"", total.startsWith("/ "))
        assertTrue("Geraetezeile fehlt: \"$device\"", device.startsWith("Gerät:"))
        assertTrue("Zustand unbekannt: \"$state\"", state in setOf("FREI", "KNAPP", "SEHR KNAPP"))
    }

    @Test
    fun `freigabe erscheint erst nach erfolgreichem lauf`() {
        val activity = activity()
        val group = activity.findViewById<View>(R.id.group_stats_saved)
        assertEquals("Ohne Lauf darf keine Zahl dastehen", View.GONE, group.visibility)

        board(activity).onRunFinished(
            running = false,
            cancelled = false,
            finished = true,
            savedBytes = 12L * 1024 * 1024 * 1024,
            files = 120
        )

        assertEquals(View.VISIBLE, group.visibility)
        assertEquals(
            "12,00 GB",
            activity.findViewById<TextView>(R.id.text_stats_saved).text.toString()
        )
        val detail = activity.findViewById<TextView>(R.id.text_stats_saved_detail).text.toString()
        assertTrue("Dateizahl fehlt: \"$detail\"", detail.startsWith("120 Dateien"))
    }

    @Test
    fun `abgebrochener lauf setzt keine freigabe`() {
        val activity = activity()
        val group = activity.findViewById<View>(R.id.group_stats_saved)

        board(activity).onRunFinished(
            running = false,
            cancelled = true,
            finished = false,
            savedBytes = 5L * 1024 * 1024 * 1024,
            files = 40
        )

        assertEquals("Ein Abbruch ist kein Ergebnis", View.GONE, group.visibility)
    }

    @Test
    fun `waehrend des laufs keine freigabe`() {
        val activity = activity()
        val group = activity.findViewById<View>(R.id.group_stats_saved)

        board(activity).onRunFinished(true, false, false, 3L * 1024 * 1024 * 1024, 30)

        assertEquals(View.GONE, group.visibility)
    }
}
