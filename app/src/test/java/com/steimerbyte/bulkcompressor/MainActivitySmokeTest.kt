package com.steimerbyte.bulkcompressor

import android.os.Looper
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.steimerbyte.bulkcompressor.log.Log
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
}
