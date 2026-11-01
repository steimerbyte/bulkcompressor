package com.steimerbyte.bulkcompressor.engine.tag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Die Box-Tabelle. Ein Container, der hier falsch gelesen wird, ist
 * hinterher nicht mehr abspielbar - dieser Teil darf deshalb nicht
 * raten, sondern muss ablehnen.
 */
class Mp4TagTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `Datei ohne ftyp am Anfang wird abgelehnt`() {
        // Gueltige Box-Tabelle, aber der Container beginnt nicht mit ftyp.
        val bytes = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val file = temp.newFile("kein-ftyp.mp4").apply { writeBytes(bytes) }

        val reason = Mp4Tag.appendTag(file, CompressionTag(runs = 1).format())

        assertEquals("beginnt nicht mit ftyp", reason)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `unvollstaendige Box-Tabelle wird abgelehnt und nichts geschrieben`() {
        // ftyp mit Groesse 0x1C, danach eine mdat, deren Groesse ueber das
        // Dateiende hinauszeigt: so sieht ein abgebrochener Schreiber aus.
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0xFF.toByte(), 0x00) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val bytes = ftyp + mdat
        val file = temp.newFile("halb.mp4").apply { writeBytes(bytes) }

        val reason = Mp4Tag.appendTag(file, CompressionTag(runs = 1).format())

        assertNotNull("Ein kaputter Container muss abgelehnt werden", reason)
        assertArrayEquals("Die Datei darf nicht angefasst werden", bytes, file.readBytes())
        assertNull(Mp4Tag.findTag(file))
    }

    @Test
    fun `Marker wird hinter der letzten Box gefunden`() {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdat = byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val file = temp.newFile("ganz.mp4").apply { writeBytes(ftyp + mdat) }

        assertNull(Mp4Tag.appendTag(file, CompressionTag(runs = 2).format()))
        val tag = CompressionTag.find(Mp4Tag.findTag(file))

        assertNotNull(tag)
        assertEquals(2, tag!!.runs)
        // Die Box-Tabelle muss auch mit der angehaengten Box noch lesbar
        // sein - sonst haette die Pruefung sie nicht bemerkt, der Player
        // aber schon.
        assertNotNull(Mp4Tag.describe(file).takeIf { !it.contains("unlesbar") })
    }

    @Test
    fun `ueberlanger Marker wird abgelehnt`() {
        val file = temp.newFile("lang.mp4").apply {
            writeBytes(
                byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
            )
        }
        val lang = "x".repeat(5000)

        val reason = Mp4Tag.appendTag(file, lang)

        assertNotNull(reason)
        assertEquals("Marker zu lang (5000 B)", reason)
        assertEquals(28L, file.length())
    }
}
