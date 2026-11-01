package com.steimerbyte.bulkcompressor

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult
import com.steimerbyte.bulkcompressor.stats.RunHistory
import com.steimerbyte.bulkcompressor.stats.RunRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
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
    fun `neu scannen ist ohne ordner gesperrt und oeffnet die auswahl`() {
        val activity = activity()
        val rescan = activity.findViewById<android.widget.Button>(R.id.button_rescan)

        // Ohne freigegebenen Ordner gibt es nichts zu scannen; der Knopf
        // sieht gesperrt aus und oeffnet nach dem Tippen den Systemdialog
        // statt still nichts zu tun.
        assertFalse("Ohne Ordner muss der Knopf gesperrt sein", rescan.isEnabled)
        assertTrue("Gesperrt muss sichtbar schwaecher sein", rescan.alpha < 1f)

        rescan.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("Tippen muss die Ordnerauswahl oeffnen", started)
        assertEquals(
            android.content.Intent.ACTION_OPEN_DOCUMENT_TREE,
            started.action
        )
    }

    @Test
    fun `nach einem scan laesst sich neu scannen und der knopf sperrt sich beim start`() {
        val activity = activity()
        val rescan = activity.findViewById<android.widget.Button>(R.id.button_rescan)
        activity.applyScanForTest(
            android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM"),
            ScanResult(items = emptyList(), folderCount = 3, truncated = false)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Mit Ordner muss der Knopf frei sein", rescan.isEnabled)

        rescan.performClick()
        // startScan() sperrt den Knopf sofort: ein zweiter Start wuerde den
        // laufenden Scan abbrechen.
        assertFalse("Waehrend eines Scans darf der Knopf nicht klicken", rescan.isEnabled)
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
        // Der wirksame Abschnitt bekommt kein Label - nur die Ausnahme
        // wird beschriftet, sonst steht ueberall "AKTIV".
        assertEquals(View.GONE, statusResolution.visibility)

        // Auf Bitrate umschalten (Chip 0): umgekehrtes Bild.
        modeChips.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Bitrate muss im Bitratenmodus bedienbar sein", seekBitrate.isEnabled)
        assertEquals(View.VISIBLE, statusResolution.visibility)
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
        activity.findViewById<View>(R.id.row_retag).performClick()
        activity.findViewById<View>(R.id.row_retag).performClick()

        val modeChips = activity.findViewById<LinearLayout>(R.id.chip_row_mode)
        modeChips.getChildAt(1).performClick()

        val seek = activity.findViewById<SeekBar>(R.id.seek_bitrate)
        shadowOf(seek).onSeekBarChangeListener?.onProgressChanged(seek, 40, true)
        shadowOf(seek).onSeekBarChangeListener?.onStopTrackingTouch(seek)

        shadowOf(Looper.getMainLooper()).idle()

        val markSound = activity.findViewById<TextView>(R.id.mark_sound)
        assertNotNull("Schalter fuer Klickgeraeusche fehlt im Layout", markSound)
    }

    /**
     * Ein Marker, dessen Zustand niemand lesen konnte, steht auf der
     * Liste. Wird das verschwiegen, sieht eine unvollstaendige Liste aus
     * wie eine fertige - und die Dateien werden ein zweites Mal
     * verlustbehaftet komprimiert.
     */
    @Test
    fun `unlesbare marker erscheinen in der zusammenfassung`() {
        val activity = activity()
        val items = listOf(
            MediaItem(
                uri = android.net.Uri.parse("content://test/document/VID_1.mp4"),
                docId = "v1",
                parentId = "p",
                displayName = "VID_1.mp4",
                mime = "video/mp4",
                sizeBytes = 10_000_000L,
                lastModified = 1L,
                kind = MediaKind.VIDEO
            )
        )

        activity.applyScanForTest(
            android.net.Uri.parse("content://test/tree/p"),
            ScanResult(items = items, folderCount = 0, truncated = false, markersUnreadable = 1)
        )
        shadowOf(Looper.getMainLooper()).idle()

        val text = activity.findViewById<TextView>(R.id.text_summary).text.toString()
        assertTrue("Zaehler fehlt in der Zusammenfassung:\n$text", text.contains("Marker nicht lesbar"))
    }

    @Test
    fun `ohne unlesbare marker bleibt die zusammenfassung knapp`() {
        val activity = activity()
        val items = listOf(
            MediaItem(
                uri = android.net.Uri.parse("content://test/document/IMG_1.jpg"),
                docId = "i1",
                parentId = "p",
                displayName = "IMG_1.jpg",
                mime = "image/jpeg",
                sizeBytes = 2_000_000L,
                lastModified = 1L,
                kind = MediaKind.IMAGE
            )
        )

        activity.applyScanForTest(
            android.net.Uri.parse("content://test/tree/p"),
            ScanResult(items = items, folderCount = 0, truncated = false)
        )
        shadowOf(Looper.getMainLooper()).idle()

        val text = activity.findViewById<TextView>(R.id.text_summary).text.toString()
        assertFalse("Null-Meldung ist Rauschen:\n$text", text.contains("nicht lesbar"))
    }

    @Test
    fun `abschnitte mit auswahl tragen kein aktiv-label`() {
        val activity = activity()
        val items = listOf(
            MediaItem(
                uri = android.net.Uri.parse("content://test/document/VID_1.mp4"),
                docId = "v1",
                parentId = "p",
                displayName = "VID_1.mp4",
                mime = "video/mp4",
                sizeBytes = 10_000_000L,
                lastModified = 1L,
                kind = MediaKind.VIDEO
            ),
            MediaItem(
                uri = android.net.Uri.parse("content://test/document/IMG_1.jpg"),
                docId = "i1",
                parentId = "p",
                displayName = "IMG_1.jpg",
                mime = "image/jpeg",
                sizeBytes = 2_000_000L,
                lastModified = 1L,
                kind = MediaKind.IMAGE
            )
        )
        activity.applyScanForTest(
            android.net.Uri.parse("content://test/tree/p"),
            ScanResult(items = items, folderCount = 0, truncated = false)
        )
        shadowOf(Looper.getMainLooper()).idle()

        // Beide Arten sind ausgewaehlt: beide Abschnitte wirken, also
        // steht dort nichts. Ein sichtbares "AKTIV" waere Rauschen.
        assertEquals(View.GONE, activity.findViewById<TextView>(R.id.status_video).visibility)
        assertEquals(View.GONE, activity.findViewById<TextView>(R.id.status_images).visibility)

        // Nur die Auswahl der Videos aufheben: dann ist der Videoabschnitt
        // die Ausnahme und muss es sagen.
        activity.findViewById<View>(R.id.button_select_none).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            View.VISIBLE,
            activity.findViewById<TextView>(R.id.status_video).visibility
        )
        assertEquals(
            View.VISIBLE,
            activity.findViewById<TextView>(R.id.status_images).visibility
        )
    }

    // ------------------------------------------------------------------
    // Speichertafel
    // ------------------------------------------------------------------

    /**
     * Legt einen Stand der Laufhistorie an, bevor die Activity entsteht -
     * genau so, wie ihn der Dienst hinterlaesst.
     */
    private fun seedHistory(vararg runs: RunRecord) {
        val store = RunHistory.of(RuntimeEnvironment.getApplication())
        store.clear()
        runs.forEach { store.record(it) }
    }

    private fun run(id: Long, folder: String = "Intern / DCIM", files: Int = 120, smaller: Int = 120, bytes: Long) =
        RunRecord(id = id, folder = folder, files = files, savedFiles = smaller, savedBytes = bytes)

    private fun activityWithHistory(): MainActivity {
        val activity = activity()
        activity.awaitHistoryForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    @Test
    fun `ohne lauf keine freigabe und keine summenzeile`() {
        seedHistory()
        val activity = activityWithHistory()

        assertEquals(
            "Ohne Lauf darf keine Zahl dastehen",
            View.GONE,
            activity.findViewById<View>(R.id.group_stats_saved).visibility
        )
        assertEquals(
            View.GONE,
            activity.findViewById<TextView>(R.id.text_total_saved).visibility
        )
    }

    @Test
    fun `letzter lauf steht in der speichertafel`() {
        seedHistory(run(id = 1_700_000_000_000, bytes = 12L * 1024 * 1024 * 1024, files = 120))
        val activity = activityWithHistory()

        assertEquals(
            View.VISIBLE,
            activity.findViewById<View>(R.id.group_stats_saved).visibility
        )
        assertEquals(
            "12,00 GB",
            activity.findViewById<TextView>(R.id.text_stats_saved).text.toString()
        )
        val detail = activity.findViewById<TextView>(R.id.text_stats_saved_detail).text.toString()
        assertTrue("Dateizahl fehlt: \"$detail\"", detail.startsWith("120 Dateien"))
    }

    @Test
    fun `summenzeile zaehlt alle laeufe`() {
        seedHistory(
            run(id = 1_700_000_000_000, bytes = 1L shl 30),
            run(id = 1_700_000_100_000, bytes = 1L shl 30)
        )
        val activity = activityWithHistory()

        val text = activity.findViewById<TextView>(R.id.text_total_saved).text.toString()
        assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.text_total_saved).visibility)
        assertTrue("Summe fehlt: \"$text\"", text.startsWith("Insgesamt gespart: 2,00 GB"))
        assertTrue("Laufzahl fehlt: \"$text\"", text.endsWith("in 2 Läufen"))
    }

    @Test
    fun `lauf ohne einsparung erscheint nicht als freigabe`() {
        seedHistory(run(id = 1_700_000_000_000, bytes = 0, files = 4, smaller = 0))
        val activity = activityWithHistory()

        // Kein Platz gewonnen: die Tafel schweigt, die Historie zaehlt den
        // Lauf trotzdem - er ist gelaufen.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.group_stats_saved).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.text_total_saved).visibility)
    }

    @Test
    fun `Schalter fuer erneutes Bearbeiten kippt die Einstellung und bleibt gespeichert`() {
        val activity = activity()
        val prefs = activity.getSharedPreferences(CompressSettings.PREFS, Context.MODE_PRIVATE)
        assertFalse("Vorgabe muss sein: Marker beachten", CompressSettings.load(prefs).ignoreCompressionTag)

        activity.findViewById<View>(R.id.row_retag).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "Klick auf den Schalter muss die Einstellung umlegen",
            CompressSettings.load(prefs).ignoreCompressionTag
        )
        val mark = activity.findViewById<TextView>(R.id.mark_retag)
        assertEquals("Schalter muss den neuen Zustand zeigen", "\u25A0", mark.text.toString())

        // Ein zweiter Klick muss zurueckfallen - ein Schalter, der nur in
        // eine Richtung kippt, waere schlimmer als keiner.
        activity.findViewById<View>(R.id.row_retag).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CompressSettings.load(prefs).ignoreCompressionTag)
    }
}
