package com.steimerbyte.bulkcompressor.scan

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.TagFile
import java.io.File
import java.io.FileNotFoundException

/**
 * Rekursion und Ordnererkennung des Scanners.
 *
 * Der Fake bildet einen echten SAF-Anbieter nach: der Scanner fragt je
 * Ordner genau einen Kind-Cursor ab, mehr braucht er nicht. Geprueft wird,
 * was er daraus macht - und ob er Ordner als Ordner erkennt, auch wenn der
 * Anbieter dafuer keinen Standard-MIME-Typ liefert.
 */
@RunWith(RobolectricTestRunner::class)
class SafScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    data class Row(
        val id: String,
        val name: String,
        val mime: String? = DocumentsContract.Document.MIME_TYPE_DIR,
        val size: Long? = null
    )

    /** Kind-Eintraege je Ordner; fehlt ein Ordner, liefert der Anbieter nichts. */
    class Tree(
        val children: Map<String, List<Row>>
    )

    class TreeProvider : ContentProvider() {

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
            val rows = tree?.children?.get(documentId) ?: return null
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
            queries++
            return cursor
        }

        var queries = 0

        override fun getType(uri: Uri) = null
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ) = 0

        /**
         * Inhalt je Dokument-ID. Der Kind-Cursor allein reicht fuer den
         * Scanner; der Marker ("schon komprimiert") steht aber **in der
         * Datei**, also muss der Test sie auch ausliefern koennen.
         */
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val segments = uri.pathSegments
            val index = segments.indexOf("document")
            val documentId = if (index >= 0 && index + 1 < segments.size) segments[index + 1] else ""
            val file = files?.get(documentId) ?: throw FileNotFoundException("kein Inhalt fuer $documentId")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        companion object {
            private const val AUTHORITY = "com.android.externalstorage.documents"

            /** Der aktuell bediente Baum; je Test neu gesetzt. */
            internal var tree: Tree? = null

            /** Dateiinhalt je Dokument-ID; ohne Eintrag ist das Dokument leer. */
            internal var files: Map<String, File>? = null

            internal fun install(
                children: Map<String, List<Row>>,
                contents: Map<String, File> = emptyMap()
            ): ContentResolver {
                tree = Tree(children)
                files = contents
                Robolectric.setupContentProvider(TreeProvider::class.java, AUTHORITY)
                return RuntimeEnvironment.getApplication().contentResolver
            }
        }
    }

    private fun treeUri(root: String = "primary:DCIM") =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode(root))

    /** Ein Video mit und ohne Marker - der Container ist hier egal. */
    private fun mp4File(name: String, tagged: Boolean): File {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val file = temp.newFile(name)
        file.writeBytes(ftyp + mdat)
        if (tagged) {
            val error = TagFile.write(
                file,
                CompressionTag(runs = 1, sourceBytes = 4_000_000, resultBytes = 1_000_000, atMillis = 1_762_000_000_000)
            )
            assertNull("Marker muss sich schreiben lassen", error)
        }
        return file
    }

    private fun dir(id: String, name: String) = Row(id, name)
    private fun file(id: String, name: String, size: Long = 1024) =
        Row(id, name, "application/octet-stream", size)

    @Test
    fun `liest Dateien aus allen Unterordnern`() {
        val resolver = TreeProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    dir("primary:DCIM/Camera", "Camera"),
                    dir("primary:DCIM/Screenshots", "Screenshots"),
                    file("primary:DCIM/oben.jpg", "oben.jpg")
                ),
                "primary:DCIM/Camera" to listOf(
                    file("primary:DCIM/Camera/a.mp4", "a.mp4"),
                    dir("primary:DCIM/Camera/Burst", "Burst")
                ),
                "primary:DCIM/Camera/Burst" to listOf(
                    file("primary:DCIM/Camera/Burst/b.jpg", "b.jpg")
                ),
                "primary:DCIM/Screenshots" to listOf(
                    file("primary:DCIM/Screenshots/s.png", "s.png")
                )
            )
        )

        val result = SafScanner(resolver).scan(treeUri())

        assertEquals(
            listOf("a.mp4", "b.jpg", "oben.jpg", "s.png"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals("Ordner inkl. Wurzel", 4, result.folderCount)
        assertEquals(0, result.unreadableFolders)
    }

    @Test
    fun `ein Ordner, der sich selbst nennt, laeuft nicht im Kreis`() {
        val resolver = TreeProvider.install(
            mapOf(
                "primary:X" to listOf(dir("primary:X/a", "a")),
                "primary:X/a" to listOf(
                    dir("primary:X", "X"),
                    dir("primary:X/a", "a"),
                    file("primary:X/a/v.mp4", "v.mp4")
                )
            )
        )

        val result = SafScanner(resolver).scan(treeUri("primary:X"))

        assertEquals(listOf("v.mp4"), result.items.map { it.displayName })
        assertEquals(2, result.folderCount)
    }

    @Test
    fun `Ordner mit fremdem Verzeichnis-MIME werden trotzdem betreten`() {
        // Manche Dateimanager melden Ordner nicht als
        // vnd.android.document/directory, sondern als inode/directory o. ae.
        // Galt so ein Ordner als Datei ohne Medien-Typ, blieb der Scan auf
        // der obersten Ebene stehen - genau das sieht wie "nicht rekursiv"
        // aus.
        val foreign = listOf(
            "inode/directory",
            "resource/folder",
            "application/vnd.google-apps.folder"
        )
        for (mime in foreign) {
            val resolver = TreeProvider.install(
                mapOf(
                    "primary:DCIM" to listOf(
                        Row("primary:DCIM/Camera", "Camera", mime),
                        file("primary:DCIM/oben.jpg", "oben.jpg")
                    ),
                    "primary:DCIM/Camera" to listOf(
                        file("primary:DCIM/Camera/a.mp4", "a.mp4")
                    )
                )
            )

            val result = SafScanner(resolver).scan(treeUri())

            assertEquals(
                "Verzeichnis-MIME $mime",
                listOf("a.mp4", "oben.jpg"),
                result.items.map { it.displayName }.sorted()
            )
        }
    }

    @Test
    fun `Ordner ganz ohne MIME-Typ und ohne Groesse wird betreten`() {
        // Anbieter, die fuer Ordner gar nichts melden: kein Typ, keine
        // Groesse, keine Endung. Erkennbar nur am Kind-Abruf.
        val resolver = TreeProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    Row("primary:DCIM/Camera", "Camera", null, null),
                    file("primary:DCIM/oben.jpg", "oben.jpg")
                ),
                "primary:DCIM/Camera" to listOf(
                    file("primary:DCIM/Camera/a.mp4", "a.mp4")
                )
            )
        )

        val result = SafScanner(resolver).scan(treeUri())

        assertEquals(
            listOf("a.mp4", "oben.jpg"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(2, result.folderCount)
    }

    @Test
    fun `unlesbarer Unterordner stoppt den Lauf nicht`() {
        val resolver = TreeProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    dir("primary:DCIM/kaputt", "kaputt"),
                    file("primary:DCIM/ok.jpg", "ok.jpg")
                )
                // primary:DCIM/kaputt fehlt -> Anbieter liefert keinen Cursor
            )
        )

        val result = SafScanner(resolver).scan(treeUri())

        assertEquals(listOf("ok.jpg"), result.items.map { it.displayName })
        assertEquals(1, result.unreadableFolders)
        assertTrue(result.folderCount >= 2)
    }

    /**
     * **Der zweite Lauf ueber denselben Ordner.** Wer den Ordner noch
     * einmal auswaehlt, will die neuen Bilder - nicht alles, was beim
     * Start ohnehin uebersprungen wuerde. Der Marker steht in der Datei,
     * also entscheidet der Scanner, und der Job liest ihn zur Sicherheit
     * noch einmal.
     */
    @Test
    fun `bereits komprimierte dateien kommen nicht auf die liste`() {
        val resolver = TreeProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    file("primary:DCIM/alt.mp4", "alt.mp4"),
                    file("primary:DCIM/neu.mp4", "neu.mp4")
                )
            ),
            contents = mapOf(
                "primary:DCIM/alt.mp4" to mp4File("alt.mp4", tagged = true),
                "primary:DCIM/neu.mp4" to mp4File("neu.mp4", tagged = false)
            )
        )

        val marked = setOf("primary:DCIM/alt.mp4")
        val result = SafScanner(resolver, isMarked = { uri -> marked.any { uri.toString().endsWith(Uri.encode(it)) } })
            .scan(treeUri())

        assertEquals(
            "Nur die unkomprimierte Datei darf auf der Liste stehen",
            listOf("neu.mp4"),
            result.items.map { it.displayName }
        )
        assertEquals("Die uebersprungene Datei muss gezaehlt werden", 1, result.skippedTagged)
        assertEquals(1, result.videoCount)
    }

    @Test
    fun `mit abgeschaltetem marker bleiben alle dateien auf der liste`() {
        val resolver = TreeProvider.install(
            mapOf(
                "primary:DCIM" to listOf(
                    file("primary:DCIM/alt.mp4", "alt.mp4"),
                    file("primary:DCIM/neu.mp4", "neu.mp4")
                )
            ),
            contents = mapOf(
                "primary:DCIM/alt.mp4" to mp4File("alt.mp4", tagged = true),
                "primary:DCIM/neu.mp4" to mp4File("neu.mp4", tagged = false)
            )
        )

        val marked = setOf("primary:DCIM/alt.mp4")
        val result = SafScanner(
            resolver,
            isMarked = { uri -> marked.any { uri.toString().endsWith(Uri.encode(it)) } }
        ).scan(treeUri(), respectTag = false)

        assertEquals(
            listOf("alt.mp4", "neu.mp4"),
            result.items.map { it.displayName }.sorted()
        )
        assertEquals(0, result.skippedTagged)
    }

}