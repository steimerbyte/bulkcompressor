package com.steimerbyte.bulkcompressor.ui

import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der Zustand des Bildschirms ohne Oberflaeche: was die Liste zeigt, was
 * die Auswahl ist, und was eine Drehung ueberlebt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeStateTest {

    private fun state() = HomeState(CompressSettings()) { }

    private fun item(name: String, kind: MediaKind) = MediaItem(
        uri = Uri.parse("content://test/document/$name"),
        docId = "doc:$name",
        parentId = "p",
        displayName = name,
        mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        sizeBytes = 1000,
        lastModified = 1_700_000_000_000,
        kind = kind
    )

    private fun scanned() = state().apply {
        settleScan(
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
    }

    @Test
    fun `ein scan waehlt alles aus`() {
        val state = scanned()
        assertEquals(3, state.selected.size)
        assertEquals(3000L, state.selectionBytes())
    }

    @Test
    fun `filter und suche aendern nur die sicht nicht die auswahl`() {
        val state = scanned()
        state.filter = MediaFilter.VIDEOS
        assertEquals(1, state.rows().size)
        assertEquals(3, state.selected.size)

        state.filter = MediaFilter.ALL
        state.query = "IMG_1"
        assertEquals(1, state.rows().size)
        assertEquals(3, state.selected.size)

        state.selected = setOf("doc:IMG_1.jpg")
        assertFalse("Eine Suche darf die Auswahl nicht kuerzen", state.selected.isEmpty())
        assertEquals(1, state.rows().size)
    }

    @Test
    fun `schalter arbeitet nur auf der sichtbaren datei`() {
        val state = scanned()
        val img = state.rows().first { it.displayName == "IMG_1.jpg" }
        state.selected = emptySet()
        state.toggle(img)
        assertEquals(setOf(img.docId), state.selected)
        state.toggle(img)
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun `ein neuer scan behaelt die auswahl der noch vorhandenen dateien`() {
        val state = scanned()
        state.selected = setOf("doc:IMG_1.jpg")

        // Nach dem Neuladen fehlt IMG_2, IMG_1 ist noch da.
        state.settleScan(
            ScanResult(
                items = listOf(item("IMG_1.jpg", MediaKind.IMAGE), item("NEU.jpg", MediaKind.IMAGE)),
                folderCount = 1,
                truncated = false
            )
        )
        assertEquals(setOf("doc:IMG_1.jpg"), state.selected)
    }

    @Test
    fun `saver nimmt ordner auswahl filter suche und preset mit`() {
        val saver = HomeState.saver(CompressSettings()) { }
        val original = scanned().apply {
            folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
            query = "IMG"
            filter = MediaFilter.IMAGES
            selected = setOf("doc:IMG_1.jpg")
            presetOpen = true
        }

        val scope: SaverScope = SaverScope { true }
        // `save` ist eine Mitglieds-Erweiterung von Saver; der Saver muss
        // als Empfaenger im Scope liegen.
        val saved: Any? = with(saver) { scope.save(original) }
        val restored = saver.restore(saved!!)!!

        assertEquals(original.folder, restored.folder)
        assertEquals("IMG", restored.query)
        assertEquals(MediaFilter.IMAGES, restored.filter)
        assertEquals(setOf("doc:IMG_1.jpg"), restored.selected)
        assertTrue(restored.presetOpen)
    }

    @Test
    fun `der saver ueberlebt einen unbekannten filter-namen`() {
        val saver = HomeState.saver(CompressSettings()) { }
        val saved = listOf("", "", "GIBT_ES_NICHT", ArrayList<String>(), false)

        val restored = saver.restore(saved)!!

        assertEquals(MediaFilter.ALL, restored.filter)
        assertTrue(restored.selected.isEmpty())
    }

    @Test
    fun `die startbeschriftung folgt dem zustand`() {
        assertEquals("Start", startControlLabel(false, false, false, true))
        assertEquals("Keine Auswahl", startControlLabel(false, false, false, false))
        assertEquals("Stopp", startControlLabel(true, false, false, true))
        assertEquals("Bricht ab …", startControlLabel(true, false, true, true))
        assertEquals("Startet …", startControlLabel(false, true, false, true))
    }
}
