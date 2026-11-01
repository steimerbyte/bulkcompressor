package com.steimerbyte.bulkcompressor.scan

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.Naming
import com.steimerbyte.bulkcompressor.engine.tag.MarkerProbe
import com.steimerbyte.bulkcompressor.engine.tag.MarkerReading
import com.steimerbyte.bulkcompressor.engine.tag.MarkerState
import com.steimerbyte.bulkcompressor.log.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.FileNotFoundException

/**
 * Einordnung durch den Filter - ohne Datei-I/O.
 *
 * Der Vertrag sagt, wer entscheidet: [MarkerProbe]. Der Test beantwortet
 * ihn deshalb direkt, statt Dateien mit echten Markern zu schreiben. Das
 * trennt zwei Fragen, die sonst in einem Test stecken und sich gegenseitig
 * verdecken: "kann der Leser den Marker finden" (A1, TagStoreProbeTest) und
 * "was macht der Scan mit einer Antwort" (hier).
 *
 * Jeder Test prueft eine Invariante aus dem Vertrag:
 *  - MARKED faellt von der Liste, wird aber gezaehlt.
 *  - UNREADABLE bleibt **auf** der Liste und wird gezaehlt - ein
 *    unbekannter Zustand ist kein "erledigt".
 *  - "Original behalten": das Original ist erledigt, wenn daneben eine
 *    MARKED-Kopie liegt - das Original selbst wird nie angefasst.
 *  - `respectTag = false` listet alles und zaehlt nichts.
 *  - `live` nennt die Dateizahl, sobald Dateien gefunden sind.
 */
@RunWith(RobolectricTestRunner::class)
class SafScannerFilterTest {

    /** Fenster, in das dieser Test sicher passt - der Ring fasst 2000 Zeilen. */
    private companion object {
        const val LOG_WINDOW = 2000
    }

    data class Row(
        val id: String,
        val name: String,
        val mime: String? = "application/octet-stream",
        val size: Long? = 1024
    )

    /** SAF-Anbieter, der nur Kind-Cursor bedient - mehr braucht der Filter nicht. */
    class ListProvider : ContentProvider() {

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? {
            val segments = uri.pathSegments
            val documentIndex = segments.indexOf("document")
            if (documentIndex < 0 || documentIndex + 1 >= segments.size) {
                throw IllegalArgumentException("Kein Dokument in $uri")
            }
            val documentId = segments[documentIndex + 1]
            val rows = children?.get(documentId) ?: return null
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val cursor = MatrixCursor(columns)
            for (row in rows) {
                cursor.addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mime
                            DocumentsContract.Document.COLUMN_SIZE -> row.size
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                            else -> null
                        }
                    }
                )
            }
            return cursor
        }

        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ) = 0

        /** Kein Inhalt: dieser Test darf nie eine Datei oeffnen. */
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("Filtertest liest keine Dateien: $uri")
        }

        companion object {
            private const val AUTHORITY = "com.android.externalstorage.documents"
            internal var children: Map<String, List<Row>>? = null

            internal fun install(children: Map<String, List<Row>>): ContentResolver {
                ListProvider.children = children
                Robolectric.setupContentProvider(ListProvider::class.java, AUTHORITY)
                return RuntimeEnvironment.getApplication().contentResolver
            }
        }
    }

    /**
     * Probe, die nur eine feste Karte beantwortet.
     *
     * [asked] ist der eigentliche Pruefpunkt bei `respectTag = false`: kein
     * Aufruf heisst, der Scan hat gar nicht erst gefragt - und das ist die
     * Zusage "kein Probing, alle Zaehler 0", nicht "fragt und ignoriert".
     */
    private class StubProbe(
        private val states: Map<String, MarkerReading> = emptyMap()
    ) : MarkerProbe {

        val asked = ArrayList<String>()

        override fun probe(uri: Uri): MarkerReading {
            // Die Dokument-ID steht dekodiert im Pfad - ein Vergleich auf
            // der kodierten URI-Zeichenkette waere von der Kodierung des
            // jeweiligen Android-Jars abhaengig.
            val segments = uri.pathSegments
            val index = segments.indexOf("document")
            val docId = if (index >= 0 && index + 1 < segments.size) segments[index + 1] else ""
            asked.add(docId)
            return states[docId] ?: MarkerReading(MarkerState.CLEAR)
        }
    }

    private fun treeUri(root: String = "primary:DCIM") =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode(root))

    private fun file(id: String, name: String, size: Long = 1024) = Row(id, name, size = size)
    private fun dir(id: String, name: String) = Row(id, name, DocumentsContract.Document.MIME_TYPE_DIR, null)

    private fun resolverWith(vararg entries: Row) =
        ListProvider.install(mapOf("primary:DCIM" to entries.toList()))

    /**
     * Nur die Zeilen, die dieser Test selbst geschrieben hat.
     *
     * [Log] ist prozessweit und der Ring ueberlebt den Test: eine Zusage
     * wie "es gibt **keine** solche Warnung" wuerde sonst von der Warnung
     * eines vorher gelaufenen Tests erfuellt oder widerlegt - je nach
     * Reihenfolge. Die Reihenfolge ist hier nichts, worauf man sich
     * verlassen darf. Deshalb wird ueber die laufende Nummer geschnitten,
     * nicht ueber die Anzahl.
     */
    private fun logMark(): Long = Log.snapshot(LOG_WINDOW).lastOrNull()?.seq ?: -1L

    private fun logSince(mark: Long): List<String> =
        Log.snapshot(LOG_WINDOW)
            .filter { it.seq > mark && it.tag == "Scan" }
            .map { it.message }

    // ------------------------------------------------------------------
    // Einordnung je Datei
    // ------------------------------------------------------------------

    @Test
    fun `datei mit marker fehlt auf der liste und wird gezaehlt`() {
        val resolver = resolverWith(
            file("primary:DCIM/alt.jpg", "alt.jpg"),
            file("primary:DCIM/neu.jpg", "neu.jpg")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/alt.jpg" to MarkerReading(MarkerState.MARKED),
                "primary:DCIM/neu.jpg" to MarkerReading(MarkerState.CLEAR)
            )
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals("nur die freie Datei", listOf("neu.jpg"), result.items.map { it.displayName })
        assertEquals(1, result.skippedTagged)
        assertEquals(0, result.markersUnreadable)
    }

    @Test
    fun `datei ohne marker steht auf der liste`() {
        val resolver = resolverWith(file("primary:DCIM/frei.mp4", "frei.mp4"))

        val result = SafScanner(resolver, StubProbe()).scan(treeUri())

        assertEquals(listOf("frei.mp4"), result.items.map { it.displayName })
        assertEquals(0, result.skippedTagged)
        assertEquals(0, result.markersUnreadable)
    }

    /**
     * **Der Kern von Invariante 1/2.** Ein unbekannter Zustand ist nicht
     * "erledigt": wuerde die Datei verschwinden, behauptete die Liste, sie
     * sei komprimiert - und der Job, der sie mangels lesbarem Marker nicht
     * ueberspringt, waere die einzige Stelle, die das Gegenteil weiss. Die
     * Datei bleibt, der Zaehler nennt sie.
     */
    @Test
    fun `unlesbarer marker listet die datei und zaehlt sie`() {
        val resolver = resolverWith(
            file("primary:DCIM/kaputt.jpg", "kaputt.jpg"),
            file("primary:DCIM/gut.jpg", "gut.jpg")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/kaputt.jpg" to MarkerReading(MarkerState.UNREADABLE, reason = "kein Deskriptor"),
                "primary:DCIM/gut.jpg" to MarkerReading(MarkerState.CLEAR)
            )
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "UNREADABLE ist unbekannt, nicht komprimiert - die Datei muss sichtbar bleiben",
            listOf("gut.jpg", "kaputt.jpg"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(1, result.markersUnreadable)
        assertEquals("ein Lesefehler ist kein Treffer", 0, result.skippedTagged)
    }

    /**
     * Brief-Punkt 6: **keine** Datei einordenbar heisst, der Leser
     * funktioniert fuer diesen Anbieter nicht. Ohne diese Warnung sieht
     * der Fall wie ein kaputter Filter aus - die Liste ist voll, und der
     * naechste Lauf zeigt dieselben Dateien noch einmal.
     */
    @Test
    fun `kein einziger lesbarer marker wird als anbieterproblem gewarnt`() {
        val resolver = resolverWith(
            file("primary:DCIM/eins.jpg", "eins.jpg"),
            file("primary:DCIM/zwei.mp4", "zwei.mp4")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/eins.jpg" to MarkerReading(MarkerState.UNREADABLE, reason = "Pipe"),
                "primary:DCIM/zwei.mp4" to MarkerReading(MarkerState.UNREADABLE, reason = "Pipe")
            )
        )

        val before = logMark()

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(2, result.markersUnreadable)
        assertTrue(
            "Die Warnung muss den Anbieter nennen: ${logSince(before)}",
            logSince(before).any { it.contains("Marker fuer diesen Anbieter nicht lesbar") }
        )
    }

    /**
     * Die Gegenprobe: eine einzige lesbare Antwort genuegt, damit der
     * Leser als funktionierend gilt. Eine Warnung bei jedem einzelnen
     * Fehlschlag waere bei einem kaputten Foto in einem gesunden Ordner
     * ein Fehlalarm.
     */
    @Test
    fun `teilweise lesbare marker loesen keine anbieterwarnung aus`() {
        val resolver = resolverWith(
            file("primary:DCIM/kaputt.jpg", "kaputt.jpg"),
            file("primary:DCIM/gut.jpg", "gut.jpg")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/kaputt.jpg" to MarkerReading(MarkerState.UNREADABLE, reason = "EXIF"),
                "primary:DCIM/gut.jpg" to MarkerReading(MarkerState.CLEAR)
            )
        )

        val before = logMark()

        SafScanner(resolver, probe).scan(treeUri())

        assertTrue(
            "Ein einzelner Lesefehler ist kein Anbieterproblem: ${logSince(before)}",
            logSince(before).none { it.contains("Marker fuer diesen Anbieter nicht lesbar") }
        )
    }

    @Test
    fun `unlesbarer marker ohne grund bekommt trotzdem einen text ins log`() {
        val resolver = resolverWith(file("primary:DCIM/ohne.jpg", "ohne.jpg"))
        val probe = StubProbe(mapOf("primary:DCIM/ohne.jpg" to MarkerReading(MarkerState.UNREADABLE)))
        val mark = logMark()

        SafScanner(resolver, probe).scan(treeUri())

        val warnung = logSince(mark).filter { it.contains("ohne.jpg") }
        assertTrue("Es muss eine Zeile zu dieser Datei geben: ${logSince(mark)}", warnung.isNotEmpty())
        assertTrue(
            "Ein leerer Grund darf nicht als 'kein Grund' durchgehen: $warnung",
            warnung.any { it.contains("Grund unbekannt") }
        )
    }

    /**
     * Wirft der Leser, ist das genau der Fall, den der Vertrag ausschliesst -
     * aber der Scan darf daran nicht sterben, und die Antwort bleibt
     * UNREADABLE. Ein Absturz hier waere schlimmer als eine gelistete Datei.
     */
    @Test
    fun `werfender leser zaehlt als unlesbar statt den scan abzubrechen`() {
        val resolver = resolverWith(file("primary:DCIM/wirft.jpg", "wirft.jpg"))
        val probe = object : MarkerProbe {
            override fun probe(uri: Uri): MarkerReading = throw IllegalStateException("Leser kaputt")
        }
        val mark = logMark()

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(listOf("wirft.jpg"), result.items.map { it.displayName })
        assertEquals(1, result.markersUnreadable)
        assertTrue(
            "Der Grund des Werfers gehoert ins Log",
            logSince(mark).any { it.contains("Leser kaputt") }
        )
    }

    /**
     * Ein Grund aus einer Fremdbibliothek kann umbrechen. Die Zeile steht
     * in einer Textzeile der App - ein Umbruch darin zerlegt das Layout.
     */
    @Test
    fun `grund mit zeilenumbruch bleibt eine zeile`() {
        val resolver = resolverWith(file("primary:DCIM/kaputt.jpg", "kaputt.jpg"))
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/kaputt.jpg" to MarkerReading(
                    MarkerState.UNREADABLE,
                    reason = "Os.pread fehlgeschlagen\nCaused by: IOException"
                )
            )
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, probe).scan(treeUri()) { gemeldet.add(it) }

        assertTrue(gemeldet.isNotEmpty())
        for (meldung in gemeldet) {
            assertTrue(
                "Umbruch in der Live-Zeile: ${meldung.live}",
                !meldung.live.contains('\n')
            )
        }
    }

    // ------------------------------------------------------------------
    // "Original behalten"
    // ------------------------------------------------------------------

    /**
     * Invariante 4 im Modus "Original behalten": das Original wird beim
     * Komprimieren nie angefasst und traegt deshalb nie einen Marker.
     * Erledigt ist es trotzdem, sobald die Kopie daneben liegt - sonst
     * stuende nach einem vollstaendigen Lauf bei jedem weiteren Scan
     * wieder alles auf der Liste.
     */
    @Test
    fun `original mit markierter kopie daneben fehlt auf der liste`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a_compressed.jpg", "a_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf(
                // Das Original ist unangetastet - kein Marker.
                "primary:DCIM/a.jpg" to MarkerReading(MarkerState.CLEAR),
                // Die Kopie traegt ihn.
                "primary:DCIM/a_compressed.jpg" to MarkerReading(MarkerState.MARKED)
            )
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertTrue(
            "Das Original ist erledigt, die Kopie ist der Marker-Traeger",
            result.items.isEmpty()
        )
        assertEquals("Beide zaehlen als 'bereits komprimiert'", 2, result.skippedTagged)
        assertEquals(0, result.markersUnreadable)
    }

    /**
     * Die Gegenprobe: gleicher Name ohne Marker in der Kopie ist **kein**
     * Beweis. Ein abgebrochener Lauf hinterlaesst genau so eine Datei -
     * und das Original waere dann nicht erledigt.
     */
    @Test
    fun `original mit unmaskierter kopie bleibt auf der liste`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a_compressed.jpg", "a_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/a_compressed.jpg" to MarkerReading(MarkerState.CLEAR))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            listOf("a.jpg", "a_compressed.jpg"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(0, result.skippedTagged)
    }

    /**
     * Der Job schreibt bei Formatwechsel die neue Endung: aus
     * `IMG_1.heic` wird `IMG_1_compressed.jpg`. Ein Abgleich auf den
     * vollen Soll-Namen (`compressedName`) faende die Kopie nicht und der
     * Scan zeigte das Original fuer immer wieder.
     */
    @Test
    fun `kopie mit anderer endung gilt trotzdem als vorhanden`() {
        val resolver = resolverWith(
            file("primary:DCIM/IMG_1.heic", "IMG_1.heic"),
            file("primary:DCIM/IMG_1_compressed.jpg", "IMG_1_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/IMG_1_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertTrue(
            "Original erledigt: ${result.items.map { it.displayName }}",
            result.items.isEmpty()
        )
        assertEquals(2, result.skippedTagged)
    }

    /**
     * `uniqueName` haengt `_2` an, wenn der Name schon belegt ist. Die
     * Kopie heisst dann `a_compressed_2.jpg` - und das Original ist
     * trotzdem erledigt.
     */
    @Test
    fun `kopie mit kollisionszusatz gilt als vorhanden`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.png", "a.png"),
            file("primary:DCIM/a_compressed_2.jpg", "a_compressed_2.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/a_compressed_2.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertTrue(
            "Original erledigt: ${result.items.map { it.displayName }}",
            result.items.isEmpty()
        )
        assertEquals(2, result.skippedTagged)
    }

    /**
     * Ein Kamerafolger legt zu `VID_1.mp4` das Standbild `VID_1.jpg` ab.
     * Ohne die Medienart im Schluessel gaelte das Vorschaubild als Kopie
     * des Videos - und das Video verschwaende, obwohl es nie komprimiert
     * wurde. Das waere der schlimmste Fehler dieser Bauart: eine Datei
     * fehlt, die niemand erledigt hat.
     */
    @Test
    fun `vorschaubild gleichen stamms erledigt das video nicht`() {
        val resolver = resolverWith(
            file("primary:DCIM/VID_1.mp4", "VID_1.mp4"),
            file("primary:DCIM/VID_1_compressed.jpg", "VID_1_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/VID_1_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "Das Video ist nicht erledigt, nur sein Bild",
            listOf("VID_1.mp4"),
            result.items.map { it.displayName }
        )
    }

    /**
     * Zwei Originale, ein Stamm: `a.jpg` und `a.png` neben einer
     * `a_compressed.jpg`. Namensgleich trifft nur `a.jpg` - das ist der
     * Beweis, den `compressedName` selbst liefert. Die `a.png` bleibt
     * stehen, weil der Stamm unter den Quellen nicht eindeutig ist.
     *
     * Ein falsches "erledigt" waere der schlimmere Fehler: die Datei
     * waere nie komprimiert und wuerde nie wieder angeboten.
     */
    @Test
    fun `bei zwei originalem gleichen stamms gilt nur der namensgleiche fall`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a.png", "a.png"),
            file("primary:DCIM/a_compressed.jpg", "a_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/a_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "Nur a.jpg hat eine namensgleiche Kopie: ${result.items.map { it.displayName }}",
            listOf("a.png"),
            result.items.map { it.displayName }.sorted()
        )
        // Einmal die MARKED-Kopie selbst, einmal das erledigte a.jpg.
        assertEquals(2, result.skippedTagged)
    }

    /**
     * Die schaerfere Fassung desselben Falls: die Kopie traegt einen
     * Kollisionszusatz, es gibt also **keinen** namensgleichen Treffer.
     * Dann entscheidet allein die Eindeutigkeit des Stamms - und die ist
     * hier nicht gegeben. Beide Originale bleiben stehen.
     */
    @Test
    fun `kollisionszusatz bei zwei originalem bleibt ohne wirkung`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a.png", "a.png"),
            file("primary:DCIM/a_compressed_2.jpg", "a_compressed_2.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/a_compressed_2.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "Nicht zuzuordnen heisst: beide bleiben: ${result.items.map { it.displayName }}",
            listOf("a.jpg", "a.png"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals("nur die MARKED-Kopie selbst", 1, result.skippedTagged)
    }

    /**
     * Ein unlesbarer Nachbar zaehlt als moegliche Quelle mit. Sonst wuerde
     * eine unbekannte Datei stillschweigend zum Beweis, dass die Kopie von
     * der lesbaren Datei stammt - und die verschwaende, obwohl niemand
     * weiss, wovon die Kopie eigentlich ein Ergebnis ist.
     */
    @Test
    fun `unlesbarer nachbar macht den stamm uneindeutig`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a.png", "a.png"),
            file("primary:DCIM/a_compressed_2.jpg", "a_compressed_2.jpg")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/a.png" to MarkerReading(MarkerState.UNREADABLE, reason = "Pipe"),
                "primary:DCIM/a_compressed_2.jpg" to MarkerReading(MarkerState.MARKED)
            )
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "Beide Quellen bleiben sichtbar: ${result.items.map { it.displayName }}",
            listOf("a.jpg", "a.png"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(1, result.markersUnreadable)
        assertEquals("nur die MARKED-Kopie selbst", 1, result.skippedTagged)
    }

    /**
     * Gegenprobe zum vorigen Test: liegt nur **ein** Original mit dem
     * Stamm im Ordner, ist der Formatwechsel eindeutig und das Original
     * gilt als erledigt.
     */
    @Test
    fun `bei genau einem original ist der formatwechsel eindeutig`() {
        val resolver = resolverWith(
            file("primary:DCIM/bild.heic", "bild.heic"),
            file("primary:DCIM/bild_compressed.jpg", "bild_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/bild_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertTrue(
            "Nur das HEIC ist erledigt durch die JPEG-Kopie: ${result.items.map { it.displayName }}",
            result.items.isEmpty()
        )
        assertEquals(2, result.skippedTagged)
    }

    @Test
    fun `eine kopie in einem anderen ordner erledigt nichts`() {
        val resolver = ListProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    dir("primary:DCIM/A", "A"),
                    dir("primary:DCIM/B", "B")
                ),
                "primary:DCIM/A" to listOf(file("primary:DCIM/A/a.jpg", "a.jpg")),
                "primary:DCIM/B" to listOf(
                    file("primary:DCIM/B/a_compressed.jpg", "a_compressed.jpg")
                )
            )
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/B/a_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertEquals(
            "Geschwister heisst: im selben Ordner - die Kopie in B erledigt A nicht",
            listOf("a.jpg"),
            result.items.map { it.displayName }
        )
        assertEquals("nur die MARKED-Kopie in B", 1, result.skippedTagged)
    }

    /** Die Kopie kann im Cursor vor ihrem Original stehen - die Karte kennt beide. */
    @Test
    fun `reihenfolge im cursor aendert nichts an der einordnung`() {
        val resolver = resolverWith(
            file("primary:DCIM/a_compressed.jpg", "a_compressed.jpg"),
            file("primary:DCIM/a.jpg", "a.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/a_compressed.jpg" to MarkerReading(MarkerState.MARKED))
        )

        val result = SafScanner(resolver, probe).scan(treeUri())

        assertTrue(result.items.isEmpty())
        assertEquals(2, result.skippedTagged)
    }

    // ------------------------------------------------------------------
    // Schalter
    // ------------------------------------------------------------------

    /**
     * "Marker ignorieren" heisst: nicht fragen. Nicht "fragen und
     * verwerfen" - der Blick in tausende Dateikoepfe ist genau der Preis,
     * den der Schalter spart.
     */
    @Test
    fun `abgeschalteter marker listet alles und zaehlt nichts`() {
        val resolver = resolverWith(
            file("primary:DCIM/alt.jpg", "alt.jpg"),
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/a_compressed.jpg", "a_compressed.jpg")
        )
        val probe = StubProbe(
            mapOf(
                "primary:DCIM/alt.jpg" to MarkerReading(MarkerState.MARKED),
                "primary:DCIM/a_compressed.jpg" to MarkerReading(MarkerState.MARKED)
            )
        )

        val result = SafScanner(resolver, probe).scan(treeUri(), respectTag = false)

        assertEquals(
            listOf("a.jpg", "a_compressed.jpg", "alt.jpg"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(0, result.skippedTagged)
        assertEquals(0, result.markersUnreadable)
        assertTrue("Ohne respectTag darf gar nicht geprobt werden: ${probe.asked}", probe.asked.isEmpty())
    }

    // ------------------------------------------------------------------
    // live-Zeile
    // ------------------------------------------------------------------

    /**
     * Die Zeile ist der einzige Ort, an dem der Benutzer waehrend des
     * Laufs erfaehrt, dass Dateien fehlen. Eine Progress-Meldung ohne Text
     * waere eine Zahl, die niemand deuten kann.
     */
    @Test
    fun `live zeile nennt die dateizahl sobald dateien gefunden sind`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/b.jpg", "b.jpg")
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, StubProbe()).scan(treeUri()) { gemeldet.add(it) }

        assertTrue("Es muss mindestens eine Meldung geben", gemeldet.isNotEmpty())
        assertTrue(
            "Jede Meldung braucht eine Zeile: ${gemeldet.map { it.live }}",
            gemeldet.all { it.live.isNotBlank() }
        )
        assertTrue(
            "Die Dateizahl gehoert in die Zeile: ${gemeldet.map { it.live }}",
            gemeldet.last().live.startsWith("2 Dateien")
        )
    }

    @Test
    fun `live zeile nennt die uebersprungenen dateien`() {
        val resolver = resolverWith(
            file("primary:DCIM/alt.jpg", "alt.jpg"),
            file("primary:DCIM/neu.jpg", "neu.jpg")
        )
        val probe = StubProbe(
            mapOf("primary:DCIM/alt.jpg" to MarkerReading(MarkerState.MARKED))
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, probe).scan(treeUri()) { gemeldet.add(it) }

        assertTrue(
            "Der Filter muss in der Zeile sichtbar sein: ${gemeldet.map { it.live }}",
            gemeldet.last().live.contains("1 bereits komprimiert")
        )
    }

    @Test
    fun `live zeile nennt unlesbare marker`() {
        val resolver = resolverWith(file("primary:DCIM/kaputt.jpg", "kaputt.jpg"))
        val probe = StubProbe(
            mapOf("primary:DCIM/kaputt.jpg" to MarkerReading(MarkerState.UNREADABLE, reason = "Pipe"))
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, probe).scan(treeUri()) { gemeldet.add(it) }

        assertTrue(
            "Ein Lesefehler darf nie still bleiben: ${gemeldet.map { it.live }}",
            gemeldet.last().live.contains("1 Marker unlesbar")
        )
    }

    /**
     * Der Scan muss sich vom ersten Moment an bewegen.
     *
     * Vorher blieb die Zeile bei einem Ordner voller Bilder stumm, bis alle
     * Marker gelesen waren: die Meldungen entstanden nur beim Einordnen in
     * der Liste (Durchgang 3), davor stand dort "0 Dateien". Fuer den
     * Benutzer sah das aus, als taete die App nichts.
     */
    @Test
    fun `stand steht schon waehrend des lesens in der zeile`() {
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/b.jpg", "b.jpg"),
            file("primary:DCIM/c.jpg", "c.jpg")
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, StubProbe()).scan(treeUri()) { gemeldet.add(it) }

        assertTrue("Die erste Meldung muss sofort kommen: ${gemeldet.map { it.live }}",
            gemeldet.first().live.contains("liest"))
        // Die Marker-Pruefung ist der langsame Teil und braucht einen Bruch:
        // "prüft 1/3" sagt, welche Datei von wie vielen gerade dran ist.
        assertTrue("Kein Bruch im Fortschritt: ${gemeldet.map { it.live }}",
            gemeldet.any { it.live.contains("prüft 1/3") })
        // Gezaehlt wird, was gefunden ist - nicht, was schon auf der Liste
        // steht. Sonst stuende dort bis zum Ordnerende eine 0.
        assertEquals("Der Zaehler muss dem Fund folgen", 3, gemeldet.last().files)
    }

    /** Das Protokoll muss sich waehrend des Scans bewegen, nicht erst am Ende. */
    @Test
    fun `jeder gelesene ordner bekommt eine protokollzeile`() {
        org.robolectric.shadows.ShadowLog.clear()
        val resolver = resolverWith(
            file("primary:DCIM/a.jpg", "a.jpg"),
            file("primary:DCIM/b.jpg", "b.jpg")
        )

        SafScanner(resolver, StubProbe()).scan(treeUri()) {}

        val zeilen = org.robolectric.shadows.ShadowLog.getLogsForTag("Scan")
            .filter { it.type == android.util.Log.INFO }
            .map { it.msg }
        assertTrue(
            "Ohne Ordnerzeile schweigt das Protokoll bei grossen Ordnern: $zeilen",
            zeilen.any { it.contains("Ordner 1:") && it.contains("2 Eintraege") }
        )
    }

    /**
     * Die Zeile steht in der Kopfzeile der Liste - sie darf nicht wachsen.
     * Tiefe, lange Namen und viele Zaehler zusammen sind der Fall, in dem
     * eine ungedrosselte Zeile das Layout sprengt.
     */
    @Test
    fun `live zeile bleibt bei hoechstens 90 zeichen`() {
        val langerOrdner = "primary:" + "SehrLangerOrdnername".repeat(6)
        val langerName = "SehrLangerDateiname" + "x".repeat(80) + ".jpg"
        val resolver = ListProvider.install(
            mapOf(
                langerOrdner to listOf(
                    file("$langerOrdner/$langerName", langerName),
                    file("$langerOrdner/kaputt.jpg", "kaputt.jpg"),
                    file("$langerOrdner/alt.jpg", "alt.jpg")
                )
            )
        )
        val probe = StubProbe(
            mapOf(
                "$langerOrdner/kaputt.jpg" to MarkerReading(MarkerState.UNREADABLE, reason = "x".repeat(300)),
                "$langerOrdner/alt.jpg" to MarkerReading(MarkerState.MARKED)
            )
        )
        val gemeldet = ArrayList<SafScanner.Progress>()

        SafScanner(resolver, probe).scan(treeUri(langerOrdner)) { gemeldet.add(it) }

        assertTrue(gemeldet.isNotEmpty())
        for (meldung in gemeldet) {
            assertTrue(
                "Zeile ueber 90 Zeichen (${meldung.live.length}): ${meldung.live}",
                meldung.live.length <= 90
            )
            assertTrue("Die Zeile muss eine Zeile bleiben", !meldung.live.contains('\n'))
        }
    }

    /**
     * Tausende Bilder duerfen die Oberflaeche nicht fluten: zwischen zwei
     * Meldungen muessen mehrere Dateien liegen. Genau die Verdrahtung
     * haengt sonst an einer Zahl, die niemand prueft.
     */
    @Test
    fun `viele dateien erzeugen deutlich weniger meldungen als dateien`() {
        val rows = (0 until 400).map { file("primary:DCIM/f$it.jpg", "f$it.jpg") }
        val resolver = ListProvider.install(mapOf("primary:DCIM" to rows))
        var meldungen = 0

        val result = SafScanner(resolver, StubProbe()).scan(treeUri()) { meldungen++ }

        assertEquals(400, result.items.size)
        assertTrue("400 Dateien, aber $meldungen Meldungen - die Drossel greift nicht", meldungen < 400)
        assertTrue(
            "Die letzte Meldung kommt am Ordnerende und muss stehen: $meldungen",
            meldungen >= 1
        )
    }

    // ------------------------------------------------------------------
    // Naming-Vertrag
    // ------------------------------------------------------------------

    /**
     * `copySourceStem` muss genau die Namen verstehen, die der Job
     * schreibt. Der Test haengt an `compressedName` und `uniqueName`, damit
     * eine Aenderung dort hier auffaellt statt als stiller Filterfehler.
     */
    @Test
    fun `copySourceStem versteht die namen des jobs`() {
        assertEquals("a", Naming.copySourceStem(Naming.compressedName("a.jpg")))
        assertEquals(
            "Formatwechsel: der Job haengt die neue Endung an",
            "IMG_1",
            Naming.copySourceStem(Naming.compressedName("IMG_1.heic", "jpg"))
        )
        assertEquals(
            "Kollision: uniqueName haengt _2 an",
            "IMG_1",
            Naming.copySourceStem(
                Naming.uniqueName(Naming.compressedName("IMG_1.heic", "jpg")) { it == "IMG_1_compressed.jpg" }
            )
        )
        assertNull("Ein Original ist keine Kopie", Naming.copySourceStem("a.jpg"))
        assertEquals("Ohne Endung", "a", Naming.copySourceStem("a_compressed"))
    }

    @Test
    fun `copySourceStem haelt echte ziffern im namen aus`() {
        // "IMG_1_2.jpg" ist ein Original der Kamera, keine Kopie mit
        // Kollisionszusatz. Wuerde der Ziffernrest blind abgeschnitten,
        // verschwaende dieses Bild hinter einem erfundenen Stamm.
        assertNull(Naming.copySourceStem("IMG_1_2.jpg"))
        assertNull(Naming.copySourceStem("2024_01_02.jpg"))
        assertEquals("2024_01_02", Naming.copySourceStem("2024_01_02_compressed.jpg"))
    }
}
