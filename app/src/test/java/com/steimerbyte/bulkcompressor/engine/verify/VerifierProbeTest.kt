package com.steimerbyte.bulkcompressor.engine.verify

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.MarkerReading
import com.steimerbyte.bulkcompressor.engine.tag.MarkerState
import com.steimerbyte.bulkcompressor.engine.tag.TagFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * Der Marker-Befund in [Verifier.verifyDocument] kommt von aussen.
 *
 * Das ist die eigentliche Zusage dieser Pruefung: was der Job als
 * "geprueft" protokolliert, muss derselbe Befund sein, den der naechste
 * Scan liest. Solange [Verifier] sich seinen eigenen Leser baut, koennen
 * beide auseinanderlaufen - und genau das war der Fehler (Job schrieb
 * "komprimiert", Scan meldete den Ordner als unberuehrt).
 *
 * Deshalb pruefen diese Tests **nicht** das Format des Markers, sondern
 * die Weitergabe des Befunds: kommt ein `UNREADABLE` als Fehlbefund mit
 * Grund an, wird ein `MARKED` als Pflicht behandelt, und bleibt der
 * Aufruf ohne `probe` beim bisherigen Verhalten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifierProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    /**
     * `MARKED` genuegt, wenn der Marker Pflicht ist. Der Befund kommt aus
     * `probe`, nicht aus einem Leser im Verifier: die `uri` zeigt auf
     * nichts, trotzdem faellt die Pruefung nicht auf den Dateipfad
     * zurueck.
     */
    @Test
    fun `MARKED aus der Probe passiert die Markerpruefung`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = true,
            probe = { tagged(CompressionTag(runs = 2, appVersion = "1.9.1")) }
        )

        assertTrue("Bericht: ${report.summary()}", report.passed)
        assertTrue("Markerzeile fehlt: ${report.summary()}", report.summary().contains("runs=2"))
    }

    /**
     * `UNREADABLE` ist ein Fehlbefund **mit Grund**. "Nicht lesbar" und
     * "nicht da" sind verschiedene Aussagen - ein Bericht, der beides als
     * "Marker fehlt" meldet, schickt den Nutzer auf die falsche Suche.
     */
    @Test
    fun `UNREADABLE ist ein Fehler mit dem Grund der Probe`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = true,
            probe = { MarkerReading(MarkerState.UNREADABLE, reason = "Deskriptor ist eine Pipe") }
        )

        assertFalse("Bericht: ${report.summary()}", report.passed)
        assertNotNull(report.reason())
        assertTrue("Grund nennt den Lesefehler: ${report.reason()}", report.reason()!!.contains("Deskriptor ist eine Pipe"))
    }

    /**
     * `UNREADABLE` ohne Grund darf nicht als leerer Fehlbefund
     * durchgehen - er braucht trotzdem einen Text, sonst steht im
     * Protokoll eine Zeile ohne Aussage.
     */
    @Test
    fun `UNREADABLE ohne Grund nennt trotzdem einen`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = true,
            probe = { MarkerReading(MarkerState.UNREADABLE) }
        )

        assertFalse(report.passed)
        assertTrue("Grund fehlt: ${report.summary()}", report.summary().contains("nicht lesbar"))
        assertTrue("Grund ist ohne Aussage: ${report.summary()}", report.summary().contains("ohne Angabe"))
    }

    /**
     * `CLEAR` bei verlangtem Marker ist der Fehler, den der Filter
     * verhindern sollte: die Datei wurde gerade geschrieben, aber niemand
     * findet den Marker. Muss als Fehler ankommen, nicht als "egal".
     */
    @Test
    fun `CLEAR bei erwartetem Marker ist ein Fehler`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = true,
            probe = { MarkerReading(MarkerState.CLEAR) }
        )

        assertFalse("Bericht: ${report.summary()}", report.passed)
        assertEquals("Marker: fehlt nach dem Schreiben", report.reason())
    }

    /** Ohne Markerpflicht darf auch `CLEAR` durchgehen - die Pruefung ist uebersprungen. */
    @Test
    fun `ohne Markerpflicht wird die Probe nicht ausgewertet`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = false,
            probe = { MarkerReading(MarkerState.UNREADABLE, reason = "egal") }
        )

        assertTrue("Bericht: ${report.summary()}", report.passed)
        // Ein uebersprungener Befund erscheint als "<Name> nicht pruefbar
        // (Grund)" - nicht als OK, damit "nicht geprueft" nie wie
        // "in Ordnung" aussieht.
        assertTrue(
            "Markerzeile fehlt: ${report.summary()}",
            report.summary().contains("Marker nicht pruefbar (nicht angefordert)")
        )
    }

    /**
     * Die Probe wird mit **derselben** `uri` gerufen, die geprueft wird.
     * Ein Leser, der etwas anderes liest als die Pruefung, waere wieder
     * eine zweite Wahrheit.
     */
    @Test
    fun `die Probe bekommt die gepruefte Uri`() {
        var seen: Uri? = null
        val file = jpegFile("bild.jpg")
        val uri = FileProvider.install(file)

        verify(file = file, expectTag = true, uri = uri, probe = { seen = it; tagged() })

        assertEquals(uri, seen)
    }

    /**
     * Ein Leser, der **wirft**, wird zum Befund mit Grund statt zur
     * Ausnahme. Genau dieser Fall wurde vorher still verschluckt: ein
     * nicht-seekbarer Deskriptor liess das Lesen scheitern, und der Filter
     * las daraus "nicht komprimiert".
     */
    @Test
    fun `werfende Probe wird zum Fehlbefund mit Grund`() {
        val report = verify(
            file = jpegFile("bild.jpg"),
            expectTag = true,
            probe = { throw IllegalStateException("pread nicht erlaubt") }
        )

        assertFalse(report.passed)
        assertTrue("Grund: ${report.summary()}", report.summary().contains("pread nicht erlaubt"))
    }

    /**
     * Ohne `probe` bleibt der bisherige Weg: der Verifier liest ueber
     * [com.steimerbyte.bulkcompressor.engine.tag.TagStore]. Der Aufruf
     * darf nicht werfen - ein bestehender Aufrufer (der Job) kompiliert
     * unveraendert, und eine Uri ohne Inhalt ist hier der billigste Fall
     * fuer "es gibt nichts zu lesen".
     */
    @Test
    fun `ohne Probe bleibt der Vorgabeweg und wirft nicht`() {
        val file = jpegFile("bild.jpg")

        val report = verify(file = file, expectTag = false, probe = null)

        assertTrue("Bericht: ${report.summary()}", report.passed)
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private fun tagged(tag: CompressionTag = CompressionTag(runs = 1)) =
        MarkerReading(MarkerState.MARKED, tag = tag)

    /**
     * Ruft [Verifier.verifyDocument] gegen ein Dokument aus [FileProvider].
     *
     * Ein `file://`-Uri taugt hier nicht: Robolectrics Resolver oeffnet
     * ihn nicht, und dann steht im Bericht "Kopfzeile nicht lesbar" -
     * der Test wuerde die Markerpruefung gar nicht mehr erreichen.
     */
    private fun verify(
        file: File,
        expectTag: Boolean,
        probe: ((Uri) -> MarkerReading)?,
        uri: Uri = FileProvider.install(file)
    ): VerifyReport = Verifier.verifyDocument(
        resolver = resolver,
        uri = uri,
        expectMime = "image/jpeg",
        expectedBytes = 0,
        expectTag = expectTag,
        probeTracks = { null },
        probe = probe
    )

    private fun jpegFile(name: String): File {
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        val file = File(temp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        assertTrue("Bild muss geschrieben sein", file.length() > 0)
        assertNull(TagFile.write(file, CompressionTag(runs = 1)))
        return file
    }

    /**
     * Liefert genau eine Datei unter einem `content://`-Uri - und zwar
     * so, wie es ein echter Anbieter tut: Groesse in der Spalte,
     * Kopfzeile ueber den Strom.
     */
    class FileProvider : ContentProvider() {

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ): Cursor? {
            val document = current ?: return null
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_SIZE)
            val cursor = MatrixCursor(columns)
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_SIZE -> document.length()
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> "image/jpeg"
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "datei"
                        else -> null
                    }
                }
            )
            return cursor
        }

        override fun getType(uri: Uri) = "image/jpeg"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                current ?: throw FileNotFoundException("keine Datei: $uri"),
                ParcelFileDescriptor.MODE_READ_ONLY
            )

        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
        ) = 0

        companion object {
            private const val AUTHORITY = "com.steimerbyte.bulkcompressor.test.files"

            /** Die aktuell bediente Datei; je Test neu gesetzt. */
            internal var current: File? = null

            internal fun install(file: File): Uri {
                current = file
                Robolectric.setupContentProvider(FileProvider::class.java, AUTHORITY)
                return Uri.parse("content://$AUTHORITY/document/datei")
            }
        }
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}
