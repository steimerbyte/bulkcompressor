package com.steimerbyte.bulkcompressor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * Aufnahmezeit im MP4-Kopf.
 *
 * Der Punkt dieser Tests: `MediaMuxer` setzt beim Schreiben "jetzt" in
 * `mvhd`/`tkhd`/`mdhd`. Nach einem Lauf steht im Video also der
 * Kompressionszeitpunkt, und die Galerie sortiert das Video nach oben.
 * Diese Boxen muessen danach wieder die Aufnahme tragen - ohne dass die
 * benachbarten Felder im selben Kopf (Modifikationszeit, Zeitskala)
 * verrutschen. Genau das prueft Byte fuer Byte.
 */
class Mp4DatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val epochSeconds = 2_082_844_800L
    private val aufnahme = 1_710_508_800_000L // 2024-03-15 12:00 UTC

    private fun seconds(millis: Long): Long = millis / 1000L + epochSeconds

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size)
        out.putInt(8 + payload.size)
        out.put(type.toByteArray(Charsets.ISO_8859_1))
        out.put(payload)
        return out.array()
    }

    /** mvhd/mdhd/tkhd Version 0: Version+Flags, creation, modification, timescale, duration. */
    private fun timeBoxV0(type: String, creation: Long, modification: Long, timescale: Int): ByteArray {
        val payload = ByteBuffer.allocate(24)
        payload.put(0)                                  // version
        payload.put(byteArrayOf(0, 0, 0))               // flags
        payload.putInt(creation.toInt())                // unsigned 32 Bit
        payload.putInt(modification.toInt())
        payload.putInt(timescale)
        payload.putInt(1000)                            // duration - bleibt stehen
        return box(type, payload.array())
    }

    private fun timeBoxV1(type: String, creation: Long, modification: Long, timescale: Int): ByteArray {
        val payload = ByteBuffer.allocate(36)
        payload.put(1)
        payload.put(byteArrayOf(0, 0, 0))
        payload.putLong(creation)
        payload.putLong(modification)
        payload.putInt(timescale)
        payload.putLong(1000L)
        return box(type, payload.array())
    }

    private var zaehler = 0

    private fun mp4(vararg parts: ByteArray): File {
        val f = File(tmp.root, "clip-${zaehler++}.mp4")
        f.outputStream().use { out ->
            parts.forEach { out.write(it) }
        }
        return f
    }

    private fun kleinesMp4(v1: Boolean = false): File {
        val mvhd = if (v1) timeBoxV1("mvhd", 0, 7, 1000) else timeBoxV0("mvhd", 0, 7, 1000)
        val tkhd = timeBoxV0("tkhd", 0, 7, 1000)
        val mdhd = timeBoxV0("mdhd", 0, 7, 48_000)
        val mdia = box("mdia", mdhd)
        val trak = box("trak", tkhd + mdia)
        val moov = box("moov", mvhd + trak)
        return mp4(box("ftyp", "isom".toByteArray()), moov)
    }

    private fun bytes(file: File): ByteArray = file.readBytes()

    @Test
    fun `setzt die Aufnahmezeit in Film Spur und Medienbox`() {
        val file = kleinesMp4()
        val patch = Mp4Dates.setCreationTime(file, aufnahme)

        assertEquals("mvhd + tkhd + mdhd", 3, patch.boxes)
        assertEquals(3, patch.written)
        assertNull(patch.skipped)
        assertTrue(patch.ok)
    }

    @Test
    fun `laesst die Nachbarfelder im Kopf stehen`() {
        val vorher = bytes(kleinesMp4())
        val file = kleinesMp4()
        Mp4Dates.setCreationTime(file, aufnahme)
        val nachher = bytes(file)

        // Layout: ftyp 12 Byte, dann moov-Kopf (8) -> mvhd-Box ab 20,
        // deren Payload ab 28; creation_time liegt bei Payload + 4 = 32.
        val creationAt = 32
        assertEquals(seconds(aufnahme), ByteBuffer.wrap(nachher, creationAt, 4).int.toLong() and 0xFFFFFFFFL)
        // Modifikationszeit (7) und Zeitskala (1000) duerfen sich nicht verschieben.
        assertEquals(7L, ByteBuffer.wrap(nachher, creationAt + 4, 4).int.toLong())
        assertEquals(1000, ByteBuffer.wrap(nachher, creationAt + 8, 4).int)
        // Vor dem Schreiben war die Aufnahmezeit 0 - der Test beweist also,
        // dass sich wirklich etwas geaendert hat.
        assertEquals(0L, ByteBuffer.wrap(vorher, creationAt, 4).int.toLong())
    }

    @Test
    fun `liest die gesetzte Zeit zurueck`() {
        val file = kleinesMp4()
        assertNull("ohne Aufnahmezeit gibt es nichts zu lesen", Mp4Dates.readCreationTime(file))
        Mp4Dates.setCreationTime(file, aufnahme)
        assertEquals(aufnahme, Mp4Dates.readCreationTime(file))
    }

    @Test
    fun `Version 1 schreibt acht Byte`() {
        val file = kleinesMp4(v1 = true)
        val patch = Mp4Dates.setCreationTime(file, aufnahme)
        assertTrue(patch.ok)
        // Die 64-Bit-Variante traegt dasselbe Datum; der Wert steht in
        // mvhd (Index 0) und nicht in den anderen Boxen.
        assertEquals(aufnahme, Mp4Dates.readCreationTime(file))
    }

    @Test
    fun `Container ohne Zeitbox meldet den Grund`() {
        val file = mp4(box("ftyp", "isom".toByteArray()), box("free", ByteArray(16)))
        val patch = Mp4Dates.setCreationTime(file, aufnahme)
        assertEquals(0, patch.boxes)
        assertEquals(0, patch.written)
        assertNotNull("Ein stiller Fehlschlag ist verboten", patch.skipped)
        assertTrue(patch.skipped!!.isNotEmpty())
    }

    @Test
    fun `ohne Datum bleibt die Datei unangetastet`() {
        val file = kleinesMp4()
        val vorher = bytes(file)
        val patch = Mp4Dates.setCreationTime(file, 0L)
        assertEquals(0, patch.written)
        assertEquals("kein Datum", patch.skipped)
        assertTrue("Bytes duerfen sich nicht aendern", vorher.contentEquals(bytes(file)))
    }

    @Test
    fun `abgeschnittene Datei wird nicht beschaedigt`() {
        val mvhd = timeBoxV0("mvhd", 0, 7, 1000)
        // Boxkopf verspricht 8 + 24 Byte, geliefert werden nur 20.
        val file = mp4(mvhd.copyOf(20))
        val vorher = bytes(file)
        val patch = Mp4Dates.setCreationTime(file, aufnahme)
        assertEquals(0, patch.written)
        assertTrue(vorher.contentEquals(bytes(file)))
    }

    @Test
    fun `Datum ausserhalb des 32-Bit-Bereichs wird abgelehnt`() {
        val file = kleinesMp4()
        val vorher = bytes(file)
        // 2130 liegt jenseits dessen, was ein 32-Bit-Feld in Sekunden
        // seit 1904 fassen kann.
        val patch = Mp4Dates.setCreationTime(file, 5_000_000_000_000L)
        assertEquals(0, patch.written)
        assertNotNull(patch.skipped)
        assertTrue(vorher.contentEquals(bytes(file)))
    }

    @Test
    fun `fehlende Datei wird gemeldet statt zu werfen`() {
        val patch = Mp4Dates.setCreationTime(File(tmp.root, "gibtsnicht.mp4"), aufnahme)
        assertEquals(0, patch.written)
        assertEquals("Datei nicht schreibbar", patch.skipped)
    }
}
