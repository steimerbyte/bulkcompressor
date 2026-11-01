package com.steimerbyte.bulkcompressor.engine.tag

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Der Marker-Leser unter den Bedingungen, unter denen er versagt hat.
 *
 * Der gemeldete Fehler: ein Ordner war komprimiert, der naechste Scan
 * meldete "nichts komprimiert". Ursache war nicht der Marker, sondern der
 * Weg zu ihm - `DescriptorIo.tail` springt ueber `Os.pread` ans Dateiende,
 * und ein Anbieter, der keine seekbare Datei liefert, gibt dafuer eine
 * Pipe. `pread` scheitert, `runCatching` machte `null` daraus, und `null`
 * hiess "nicht komprimiert".
 *
 * Der Anbieter in diesem Test liefert **ausschliesslich** Pipes. Damit ist
 * jeder Test hier eine Aussage ueber den Strom-Pfad: der Deskriptor-Weg
 * kann nicht der sein, der ihn rettet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagStoreProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * SAF-Anbieter, der Dokumente als Pipe ausliefert - so wie ein Cloud-
     * oder Downloads-Anbieter, bei dem der Seek ans Dateiende scheitert.
     */
    class PipeProvider : ContentProvider() {

        val inhalte = mutableMapOf<String, ByteArray>()

        /** Dokumente, deren Strom gar nicht erst geoeffnet werden kann. */
        val kaputt = mutableSetOf<String>()

        /** Abweichende Groesse in der Abfrage; -1 = die echte. */
        val groessen = mutableMapOf<String, Long>()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? {
            val id = uri.lastPathSegment ?: return null
            val bytes = inhalte[id] ?: return null
            val columns = projection ?: arrayOf(OpenableColumns.SIZE)
            val cursor = MatrixCursor(columns)
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.SIZE -> groessen[id] ?: bytes.size.toLong()
                        OpenableColumns.DISPLAY_NAME -> id
                        else -> null
                    }
                }
            )
            return cursor
        }

        override fun getType(uri: Uri): String = "application/octet-stream"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ): Int = 0

        /**
         * Der einzige Weg, den `openInputStream` und `openFileDescriptor`
         * gehen - beide laufen ueber `openFile`. Die Pipe ist der Punkt:
         * seekbar ist sie nicht.
         */
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val id = uri.lastPathSegment ?: throw FileNotFoundException("kein Dokument")
            if (id in kaputt) throw FileNotFoundException("Dokument nicht lesbar: $id")
            val bytes = inhalte[id] ?: throw FileNotFoundException(id)
            // Bewusst ohne eigenen Schreiber-Thread: die Nutzdaten sind
            // kleiner als der Pipe-Puffer, also passt alles hinein, und der
            // Test haengt nicht an der Reihenfolge zweier Threads. Ein
            // Hintergrundschreiber hat den Fall schon einmal flatterhaft
            // gemacht - ein halb gefuellter Kopf sah dann aus wie ein
            // unbekanntes Format.
            require(bytes.size <= PIPE_CAPACITY) { "Nutzdaten zu gross fuer die Pipe: ${bytes.size}" }
            val pipe = ParcelFileDescriptor.createPipe()
            FileOutputStream(pipe[1].fileDescriptor).use { out -> out.write(bytes) }
            pipe[1].close()
            return pipe[0]
        }
    }

    private val authority = "tagstore.test"

    private companion object {
        /** Puffer einer Linux-Pipe; mehr passt ohne Leser nicht hinein. */
        const val PIPE_CAPACITY = 64 * 1024
    }

    private fun provider(): PipeProvider =
        Robolectric.setupContentProvider(PipeProvider::class.java, authority)

    private fun document(anbieter: PipeProvider, id: String, bytes: ByteArray): Uri {
        anbieter.inhalte[id] = bytes
        return Uri.parse("content://$authority/$id")
    }

    private fun store(): TagStore =
        TagStore(RuntimeEnvironment.getApplication().contentResolver)

    // ------------------------------------------------------------------
    // Fixtures: MP4 und JPEG mit einem echten Marker aus der Produktion
    // ------------------------------------------------------------------

    private fun mp4MitMarker(): ByteArray {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val file = temp.newFile("video.mp4")
        file.writeBytes(ftyp + mdat)
        // Den Marker schreibt die Produktion selbst - so kann der Test
        // nicht an einer abweichenden Fixture bestehen.
        val reason = TagFile.write(file, CompressionTag(runs = 2, sourceBytes = 4096, resultBytes = 1024))
        assertNull("Marker muss schreibbar sein: $reason", reason)
        return file.readBytes()
    }

    private fun jpegMitMarker(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(16, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val file = temp.newFile("bild.jpg")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }
        val reason = TagFile.write(file, CompressionTag(runs = 1, sourceBytes = 8192))
        assertNull("Marker muss schreibbar sein: $reason", reason)
        return file.readBytes()
    }

    // ------------------------------------------------------------------
    // Der eigentliche Fehler
    // ------------------------------------------------------------------

    @Test
    fun `pipe liefert den mp4 marker ueber den strom`() {
        val anbieter = provider()
        val uri = document(anbieter, "video.mp4", mp4MitMarker())

        val reading = store().probe(uri)

        assertEquals("Grund: ${reading.reason}", MarkerState.MARKED, reading.state)
        assertEquals(2, reading.tag?.runs)
        assertEquals("", reading.reason)
    }

    @Test
    fun `pipe liefert den exif marker ueber den strom`() {
        val anbieter = provider()
        val uri = document(anbieter, "bild.jpg", jpegMitMarker())

        val reading = store().probe(uri)

        assertEquals(MarkerState.MARKED, reading.state)
        assertEquals(1, reading.tag?.runs)
    }

    @Test
    fun `datei ohne marker ist nicht markiert`() {
        val anbieter = provider()
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val uri = document(anbieter, "frisch.mp4", ftyp)

        val reading = store().probe(uri)

        assertEquals(MarkerState.CLEAR, reading.state)
        assertNull(reading.tag)
    }

    // ------------------------------------------------------------------
    // Der unbekannte Zustand darf nicht zum Nichtbefund werden
    // ------------------------------------------------------------------

    @Test
    fun `nicht lesbarer strom ergibt unreadable mit grund`() {
        val anbieter = provider()
        val uri = document(anbieter, "kaputt.mp4", ByteArray(0))
        anbieter.kaputt += "kaputt.mp4"

        val reading = store().probe(uri)

        assertEquals(MarkerState.UNREADABLE, reading.state)
        assertTrue("Ein unbekannter Zustand braucht einen Grund", reading.reason.isNotEmpty())
    }

    @Test
    fun `ohne groesse bleibt der mp4 marker unlesbar statt unmarkiert`() {
        // Eine Pipe ohne Groessenangabe: das Dateiende ist nicht
        // adressierbar. "Nicht lesbar" ist die einzige ehrliche Antwort -
        // "nicht komprimiert" wuerde die Datei ein zweites Mal verkleinern.
        val anbieter = provider()
        val uri = document(anbieter, "ohne-groesse.mp4", mp4MitMarker())
        anbieter.groessen["ohne-groesse.mp4"] = 0L

        val reading = store().probe(uri)

        assertEquals("Grund: ${reading.reason}", MarkerState.UNREADABLE, reading.state)
        assertTrue(reading.reason.contains("Dateiende") || reading.reason.contains("Strom"))
    }

    @Test
    fun `grund ist immer einzeilig und gedeckelt`() {
        val anbieter = provider()
        val uri = document(anbieter, "kaputt2.mp4", ByteArray(0))
        anbieter.kaputt += "kaputt2.mp4"

        val reading = store().probe(uri)

        assertTrue("Kein Umbruch im Grund", !reading.reason.contains('\n'))
        assertTrue("Grund bleibt kurz", reading.reason.length <= 120)
    }

    // ------------------------------------------------------------------
    // Formate ohne Markerort
    // ------------------------------------------------------------------

    @Test
    fun `heic kopf ergibt nicht markiert`() {
        // HEIC beginnt wie MP4. Eine uuid-Box gibt es dort nie - die App
        // schreibt solche Bilder als JPEG und ersetzt sie nie in-place.
        val ftyp = byteArrayOf(0, 0, 0, 0x14) + "ftypheic".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0)
        val anbieter = provider()
        val uri = document(anbieter, "foto.heic", ftyp)

        assertEquals(MarkerState.CLEAR, store().probe(uri).state)
    }

    @Test
    fun `unbekanntes format ist nicht markiert statt unlesbar`() {
        // Kein bekannter Container: es gibt keinen Ort fuer einen Marker.
        // Das ist ein Nichtbefund, kein Lesefehler - sonst zaehlte jeder
        // Sonderfall als Anbieterproblem.
        val anbieter = provider()
        val uri = document(anbieter, "notiz.bin", "kein container".toByteArray())

        val reading = store().probe(uri)

        assertEquals(MarkerState.CLEAR, reading.state)
        assertNull(reading.tag)
    }

    @Test
    fun `read und isMarked folgen demselben zustand`() {
        val anbieter = provider()
        val markiert = document(anbieter, "markiert.mp4", mp4MitMarker())
        val leer = document(anbieter, "leer.mp4", byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray() +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray())
        val kaputt = document(anbieter, "weg.mp4", ByteArray(0))
        anbieter.kaputt += "weg.mp4"

        val store = store()
        assertNotNull(store.read(markiert))
        assertTrue(store.isMarked(markiert))
        assertNull(store.read(leer))
        assertTrue(!store.isMarked(leer))
        assertNull("Unbekannt zaehlt nicht als markiert", store.read(kaputt))
        assertTrue(!store.isMarked(kaputt))
    }
}
