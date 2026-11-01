package com.steimerbyte.bulkcompressor.engine.verify

import android.graphics.Bitmap
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.TagFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Die Pruefung der Ausgabedatei.
 *
 * Der Punkt dieser Tests ist nicht der Erfolgsfall - der faellt auch ohne
 * Pruefung auf. Geprueft wird, dass die Pruefung **zuschlaegt**: eine Datei
 * im falschen Format, ein halber Container und ein fehlender Marker
 * muessen als Fehler ankommen. Genau diese drei Faelle wuerden sonst als
 * Erfolg ins Protokoll wandern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `gueltiges Bild mit Marker passiert`() {
        val file = jpegFile("bild.jpg")
        assertNull(TagFile.write(file, CompressionTag(runs = 1, resultBytes = 512)))

        val report = Verifier.verifyFile(file, "image/jpeg", expectTag = true)

        assertTrue("Bericht: ${report.summary()}", report.passed)
        assertTrue("Bildabmessungen fehlen im Bericht: ${report.summary()}", report.summary().contains("16x8"))
    }

    @Test
    fun `Bild im falschen Format faellt auf`() {
        val file = jpegFile("bild.png")

        val report = Verifier.verifyFile(file, "image/png", expectTag = false)

        assertFalse(report.passed)
        assertNotNull(report.reason())
        assertTrue("Grund nennt das Format: ${report.reason()}", report.reason()!!.contains("image/jpeg"))
    }

    @Test
    fun `fehlender Marker ist ein Fehler`() {
        val file = jpegFile("ohne-marker.jpg")

        val report = Verifier.verifyFile(file, "image/jpeg", expectTag = true)

        assertFalse(report.passed)
        assertEquals("Marker: fehlt nach dem Schreiben", report.reason())
    }

    @Test
    fun `leere Datei wird verworfen`() {
        val file = temp.newFile("leer.jpg")

        val report = Verifier.verifyFile(file, "image/jpeg", expectTag = false)

        assertFalse(report.passed)
        assertEquals("Groesse: leer oder nicht lesbar", report.reason())
    }

    @Test
    fun `Video ohne ftyp faellt auf`() {
        // Bildbytes, als Video deklariert - der Fall, den ein Muxer bei
        // einem abgebrochenen Schreibvorgang hinterlaesst.
        val file = temp.newFile("video.mp4").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
        }

        val report = Verifier.verifyFile(file, "video/mp4", expectTag = false)

        assertFalse(report.passed)
        assertTrue("Grund: ${report.reason()}", report.reason()!!.contains("image/jpeg statt video/mp4"))
    }

    @Test
    fun `vollstaendiger MP4-Container passiert die Boxpruefung`() {
        val file = mp4File("video.mp4")
        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val report = Verifier.verifyFile(file, "video/mp4", expectTag = true)

        assertTrue("Bericht: ${report.summary()}", report.passed)
        assertTrue("Boxliste fehlt: ${report.summary()}", report.summary().contains("ftyp"))
    }

    @Test
    fun `abgeschnittener MP4-Container faellt in der Boxpruefung auf`() {
        val file = mp4File("halb.mp4")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 6))

        val report = Verifier.verifyFile(file, "video/mp4", expectTag = false)

        assertFalse("Bericht: ${report.summary()}", report.passed)
        assertTrue("Grund: ${report.reason()}", report.reason()!!.contains("Box-Tabelle"))
    }

    @Test
    fun `Erkennung ordnet die Dateikoepfe zu und nennt das Format`() {
        assertEquals(
            Verifier.Format.JPEG,
            Verifier.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        )
        assertEquals(
            Verifier.Format.PNG,
            Verifier.sniff(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
                    byteArrayOf(0x0D, 0x0A, 0x1A, 0x0A)
            )
        )
        assertEquals(
            Verifier.Format.MP4,
            Verifier.sniff(
                byteArrayOf(0, 0, 0, 0x1C, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                    ByteArray(8)
            )
        )
        assertEquals(Verifier.Format.UNBEKANNT, Verifier.sniff("hallo welt".toByteArray()))
        assertEquals(Verifier.Format.UNBEKANNT, Verifier.sniff(null))
    }

    private fun jpegFile(name: String): File {
        val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        val file = File(temp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        return file
    }

    private fun mp4File(name: String): File {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        return File(temp.root, name).apply { writeBytes(ftyp + mdat) }
    }
}
