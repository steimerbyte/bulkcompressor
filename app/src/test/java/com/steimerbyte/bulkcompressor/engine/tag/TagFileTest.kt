package com.steimerbyte.bulkcompressor.engine.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Der Marker in einer Datei - der einzige Mechanismus, der verhindert,
 * dass ein zweiter Lauf dieselben Dateien noch einmal verlustbehaftet
 * komprimiert. Wenn er kaputt ist, faellt das nicht beim Komprimieren auf,
 * sondern erst beim zweiten Lauf, und dann als Qualitaetsverlust in der
 * Galerie.
 *
 * Deshalb hier der volle Weg: schreiben, lesen, zaehlen, und bei
 * wiederholtem Lauf den Zaehler fortschreiben.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Ein echtes JPEG: `ExifInterface` schreibt nur in Dateien, die es
     * parsen kann, und ein Kopf ohne Bilddaten ist es nicht.
     */
    private fun jpegFile(name: String = "bild.jpg"): File {
        val bitmap = android.graphics.Bitmap.createBitmap(16, 8, android.graphics.Bitmap.Config.ARGB_8888)
        return File(temp.root, name).apply {
            outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }
        }
    }

    private fun mp4File(name: String = "video.mp4"): File {
        // ftyp-Box, danach eine mdat - mehr braucht die Erkennung nicht.
        // Groesse 0x1C = 28 Bytes: 4 Header + 4 "ftyp" + 4 Brand + 4 Minor
        // + 12 kompatible Brands.
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        return temp.newFile(name).apply { writeBytes(ftyp + mdat) }
    }

    @Test
    fun `erkennt den Container an den ersten Bytes`() {
        assertEquals(TagFile.Family.EXIF, TagFile.familyOf(jpegFile()))
        assertEquals(TagFile.Family.MP4, TagFile.familyOf(mp4File()))
        assertEquals(null, TagFile.familyOf(temp.newFile("notiz.txt").apply { writeText("hallo welt") }))
    }

    @Test
    fun `Marker ueberlebt Schreiben und Lesen`() {
        val file = jpegFile()
        val tag = CompressionTag(runs = 1, sourceBytes = 4_194_304, resultBytes = 1_048_576, atMillis = 1_762_000_000_000)

        assertNull("Schreiben darf keinen Grund melden", TagFile.write(file, tag))
        val back = TagFile.read(file)
        assertNotNull("Marker muss danach lesbar sein", back)
        assertEquals(1, back!!.runs)
        assertEquals(4_194_304L, back.sourceBytes)
        assertEquals(1_048_576L, back.resultBytes)
    }

    @Test
    fun `zweiter Lauf zaehlt hoch statt bei eins zu beginnen`() {
        val file = jpegFile()
        TagFile.write(file, CompressionTag(runs = 1, resultBytes = 100))
        val first = TagFile.read(file)!!
        TagFile.write(file, CompressionTag(runs = first.runs + 1, resultBytes = 50))

        val second = TagFile.read(file)!!
        assertEquals(2, second.runs)
        assertEquals(50L, second.resultBytes)
    }

    @Test
    fun `fremder Kommentar bleibt beim Schreiben erhalten`() {
        // Manche Kameras schreiben einen eigenen UserComment. Den darf
        // diese App nicht ueberschreiben - er gehoert dem Nutzer.
        val file = jpegFile()
        TagFile.write(file, CompressionTag(runs = 1))
        val merged = CompressionTag.merge("Belichtungsreihe 3/3", CompressionTag(runs = 1))

        assertTrue(merged.contains("Belichtungsreihe 3/3"))
        assertTrue(merged.contains(CompressionTag.MAGIC))
        assertNotEquals("Belichtungsreihe 3/3", merged)
    }

    @Test
    fun `MP4-Marker steht am Dateiende`() {
        val file = mp4File()
        assertNull(TagFile.write(file, CompressionTag(runs = 1, sourceBytes = 999)))

        // Die Box muss hinter der mdat liegen, sonst wuerde sie beim Lesen
        // der Spuren ins Leere zeigen.
        val bytes = file.readBytes()
        val boxAt = indexOfUserType(bytes)
        assertTrue("uuid-Box fehlt in der Datei", boxAt > 0)
        assertTrue(
            "Marker steht vor der mdat statt dahinter",
            boxAt > indexOfAscii(bytes, "mdat")
        )
        assertEquals(999L, TagFile.read(file)!!.sourceBytes)
    }

    @Test
    fun `Datei ohne Marker liefert null`() {
        assertNull(TagFile.read(jpegFile()))
        assertNull(TagFile.read(mp4File()))
    }

    private fun indexOfAscii(bytes: ByteArray, needle: String): Int {
        val target = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - target.size) {
            for (j in target.indices) if (bytes[i + j] != target[j]) continue@outer
            return i
        }
        return -1
    }

    private fun indexOfUserType(bytes: ByteArray): Int = indexOfAscii(bytes, "uuid")

    /**
     * Der Weg, den die Galerie geht: kein Pfad, kein `RandomAccessFile` -
     * nur die letzten Bytes des Dokuments. Genau dieser Parser liest beim
     * naechsten Lauf den Marker, deshalb wird er am echten Dateiende
     * geprueft und nicht an einem erfundenen Puffer.
     */
    @Test
    fun `Marker aus den letzten Bytes lesbar`() {
        val file = mp4File()
        assertNull(TagFile.write(file, CompressionTag(runs = 3, resultBytes = 4242)))

        val tail = java.io.RandomAccessFile(file, "r").use { raf ->
            val read = minOf(8192L, raf.length()).toInt()
            raf.seek(raf.length() - read)
            ByteArray(read).also { raf.readFully(it) }
        }
        val tag = CompressionTag.find(Mp4Tag.findTagInTail(tail))

        assertNotNull("Marker fehlt im Dateiende", tag)
        assertEquals(3, tag!!.runs)
        assertEquals(4242L, tag.resultBytes)
    }

    @Test
    fun `Dateiende ohne Marker liefert nichts`() {
        val file = mp4File()
        val tail = java.io.RandomAccessFile(file, "r").use { raf ->
            val read = minOf(8192L, raf.length()).toInt()
            raf.seek(raf.length() - read)
            ByteArray(read).also { raf.readFully(it) }
        }

        assertNull(Mp4Tag.findTagInTail(tail))
    }
}
