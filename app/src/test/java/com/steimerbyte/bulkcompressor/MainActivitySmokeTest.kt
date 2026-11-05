package com.steimerbyte.bulkcompressor

import android.net.Uri
import android.os.Looper
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult
import com.steimerbyte.bulkcompressor.stats.RunHistory
import com.steimerbyte.bulkcompressor.stats.RunRecord
import com.steimerbyte.bulkcompressor.ui.scanMarkerNote
import com.steimerbyte.bulkcompressor.ui.scanNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Prueft die Verdrahtung des Bildschirms auf der JVM: die Activity startet
 * mit Compose, der Zustand nimmt Ordner und Scan an, die Zusammenfassung
 * nennt die richtigen Zahlen, und die Logdatei schreibt.
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

    private fun item(
        name: String,
        kind: MediaKind,
        size: Long = 1_000_000L
    ) = MediaItem(
        uri = Uri.parse("content://test/document/$name"),
        docId = "doc:$name",
        parentId = "p",
        displayName = name,
        mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        sizeBytes = size,
        lastModified = 1_700_000_000_000,
        kind = kind
    )

    @Test
    fun `activity startet mit leerem zustand`() {
        val activity = activity()
        val state = activity.home()

        assertNull("Ohne Ordner kein Scanergebnis", state.scan)
        assertEquals(0, state.rows().size)
        assertEquals(0, state.selected.size)
        assertFalse(activity.startEnabled())
    }

    @Test
    fun `ein scan waehlt alles aus und fuellt die liste`() {
        val activity = activity()
        activity.applyScanForTest(
            Uri.parse("content://test/tree/p"),
            ScanResult(
                items = listOf(item("a.mp4", MediaKind.VIDEO), item("b.jpg", MediaKind.IMAGE)),
                folderCount = 1,
                truncated = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        val state = activity.home()
        assertEquals(2, state.rows().size)
        assertEquals(2, state.selected.size)
        assertEquals(2_000_000L, state.selectionBytes())
        assertEquals("Start", activity.startLabel())
    }

    @Test
    fun `filter und suche aendern die liste aber nicht die auswahl`() {
        val activity = activity()
        activity.applyScanForTest(
            Uri.parse("content://test/tree/p"),
            ScanResult(
                items = listOf(
                    item("VID_1.mp4", MediaKind.VIDEO),
                    item("IMG_1.jpg", MediaKind.IMAGE),
                    item("IMG_2.jpg", MediaKind.IMAGE)
                ),
                folderCount = 1,
                truncated = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        val state = activity.home()

        state.query = "IMG"
        assertEquals(2, state.rows().size)
        assertEquals("Suche ist keine Auswahl", 3, state.selected.size)

        state.query = ""
        state.filter = com.steimerbyte.bulkcompressor.ui.MediaFilter.VIDEOS
        assertEquals(1, state.rows().size)
        assertEquals(3, state.selected.size)
    }

    @Test
    fun `ein zweiter scan haelt die auswahl der noch vorhandenen dateien`() {
        val activity = activity()
        val tree = Uri.parse("content://test/tree/p")
        val a = item("a.jpg", MediaKind.IMAGE)
        val b = item("b.jpg", MediaKind.IMAGE)
        activity.applyScanForTest(tree, ScanResult(listOf(a, b), folderCount = 1, truncated = false))
        val state = activity.home()

        state.selected = setOf(a.docId)
        activity.applyScanForTest(
            tree,
            ScanResult(listOf(a, b, item("c.jpg", MediaKind.IMAGE)), folderCount = 1, truncated = false)
        )

        assertEquals("Auswahl darf beim Neuladen nicht zurueckfallen", setOf(a.docId), state.selected)
    }

    @Test
    fun `einstellungen werden gespeichert`() {
        val activity = activity()
        activity.home().updateSettings(CompressSettings(imageQuality = 55))

        val stored = CompressSettings.load(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences(CompressSettings.PREFS, android.content.Context.MODE_PRIVATE)
        )
        assertEquals(55, stored.imageQuality)
    }

    @Test
    fun `unlesbare marker erscheinen in der zusammenfassung und null bleibt still`() {
        val withMarkers = ScanResult(
            items = listOf(item("VID_1.mp4", MediaKind.VIDEO)),
            folderCount = 0,
            truncated = false,
            markersUnreadable = 1,
            markerReason = "Anbieter ohne Deskriptor"
        )
        assertTrue(scanMarkerNote(withMarkers).contains("1 MARKER NICHT LESBAR"))
        assertTrue(scanMarkerNote(withMarkers).contains("Anbieter ohne Deskriptor"))
        assertTrue(
            scanNotes(withMarkers.copy(skippedTagged = 3)).contains("3 BEREITS KOMPRIMIERT")
        )

        val clean = ScanResult(listOf(item("IMG_1.jpg", MediaKind.IMAGE)), folderCount = 1, truncated = false)
        assertEquals("0 MARKER NICHT LESBAR", scanMarkerNote(clean))
        assertFalse(scanNotes(clean).contains("nicht lesbar"))
        assertTrue(scanNotes(clean).isEmpty())
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
    fun `export enthaelt den letzten absturzbericht`() {
        activity()
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
    fun `ohne lauf bleibt die historie leer und mit lauf nennt sie die summe`() {
        val store = RunHistory.of(RuntimeEnvironment.getApplication())
        store.clear()

        val empty = activity()
        empty.awaitHistoryForTest()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, empty.history.totals.runs)
        assertTrue(empty.history.isEmpty)

        store.record(
            RunRecord(
                id = 1_700_000_000_000,
                folder = "Intern / DCIM",
                files = 120,
                savedFiles = 118,
                savedBytes = 3L * 1024 * 1024 * 1024
            )
        )
        val withRun = activity()
        withRun.awaitHistoryForTest()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, withRun.history.totals.runs)
        assertEquals(3L * 1024 * 1024 * 1024, withRun.history.totals.savedBytes)
    }
}
