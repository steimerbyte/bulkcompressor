package com.steimerbyte.bulkcompressor.engine.tag

import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.ByteArrayOutputStream
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

    /**
     * Ein echtes 2x2-PNG. Bewusst als Bytes und nicht ueber
     * `Bitmap.compress`: Robolectrics Bildschreiber kennt nur JPEG und PNG,
     * und WebP gar nicht. Feste Bytes sind hier auch das ehrlichere
     * Vergleichsmass - der Punkt der Tests ist, dass die Bilddaten
     * **byte-gleich** bleiben, und das laesst sich nur gegen ein
     * unveraenderliches Original pruefen.
     */
    private fun pngFile(name: String = "bild.png"): File = File(temp.root, name).apply {
        writeBytes(Base64.decode(PNG_2X2, Base64.DEFAULT))
    }

    /** Ein echtes 2x2-WebP (VP8-Verlustbehaftet, ohne VP8X-Kopf). */
    private fun webpFile(name: String = "bild.webp"): File = File(temp.root, name).apply {
        writeBytes(Base64.decode(WEBP_2X2, Base64.DEFAULT))
    }

    // ------------------------------------------------------------------
    // Lesehilfen fuer die Container-Struktur
    // ------------------------------------------------------------------

    /** Ein PNG-Chunk: Typ, Laenge, Nutzdaten und die gespeicherte CRC. */
    private data class PngChunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    private fun pngChunks(bytes: ByteArray): List<PngChunk> {
        val chunks = ArrayList<PngChunk>(6)
        var at = 8 // Signatur
        while (at + 12 <= bytes.size) {
            val length = readInt(bytes, at)
            if (length < 0 || at + 12 + length > bytes.size) break
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(at + 8, at + 8 + length)
            val stored = readInt(bytes, at + 8 + length)
            val crc = java.util.zip.CRC32()
            crc.update(bytes, at + 4, 4 + length)
            chunks += PngChunk(type, data, crc.value.toInt() == stored)
            at += 12 + length
        }
        return chunks
    }

    /** Ein WebP-Chunk ab dem RIFF-Kopf, inklusive gerader Ausrichtung. */
    private data class WebpChunk(val type: String, val data: ByteArray)

    private fun webpChunks(bytes: ByteArray): List<WebpChunk> {
        val chunks = ArrayList<WebpChunk>(4)
        var at = 12
        while (at + 8 <= bytes.size) {
            val type = String(bytes, at, 4, Charsets.US_ASCII)
            val length = readIntLe(bytes, at + 4)
            if (length < 0 || at + 8 + length > bytes.size) break
            chunks += WebpChunk(type, bytes.copyOfRange(at + 8, at + 8 + length))
            at += 8 + length + (length and 1)
        }
        return chunks
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun readIntLe(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /**
     * Zerlegt ein JPEG in seine Segmente: Marke, Nutzdaten und - ab `SOS` -
     * den Bilddatenstrom. Damit laesst sich pruefen, welche Segmente
     * unangetastet geblieben sind, statt nur "es sieht noch aus wie ein
     * JPEG".
     */
    private fun jpegSegments(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val segments = ArrayList<Pair<Int, ByteArray>>(12)
        var at = 2 // SOI
        while (at + 4 <= bytes.size) {
            if ((bytes[at].toInt() and 0xFF) != 0xFF) break
            val marker = bytes[at + 1].toInt() and 0xFF
            val length = ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                // Ab hier ist der Rest Bilddaten, kein Segment mehr.
                segments += marker to bytes.copyOfRange(at, bytes.size)
                break
            }
            if (length < 2 || at + 2 + length > bytes.size) break
            segments += marker to bytes.copyOfRange(at, at + 2 + length)
            at += 2 + length
        }
        return segments
    }

    /** Setzt einen Fremdkommentar so, wie eine Kamera ihn hinterlaesst. */
    private fun jpegWithForeignComment(name: String, comment: String): File {
        val file = jpegFile(name)
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_MAKE, "Nothing")
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:01 12:00:00")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Kamera A Beschreibung")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
        exif.saveAttributes()
        return file
    }

    private fun userComment(file: File): String? =
        runCatching { ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT) }
            .getOrNull()

    private fun tail(file: File, count: Int = 8192): ByteArray =
        java.io.RandomAccessFile(file, "r").use { raf ->
            val read = minOf(count.toLong(), raf.length()).toInt()
            raf.seek(raf.length() - read)
            ByteArray(read).also { raf.readFully(it) }
        }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
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

    /**
     * Manche Schreiber lassen die letzte Box offen (Groesse 0 = "bis
     * Dateiende"), weil sie beim Schreiben die Endgroesse noch nicht
     * kannten. Haengt man einfach an, verschluckt diese Box den Marker -
     * er waere geschrieben und trotzdem unsichtbar, und jede Datei wuerde
     * beim naechsten Lauf erneut komprimiert.
     */
    @Test
    fun `offene letzte Box wird geschlossen bevor der Marker angehaengt wird`() {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        val mdatInhalt = ByteArray(32) { 0x5A }
        val file = temp.newFile("offen.mp4").apply {
            // mdat mit Groesse 0 - genau der Fall, der die Endgroesse offen laesst.
            writeBytes(ftyp + byteArrayOf(0, 0, 0, 0) + "mdat".toByteArray(Charsets.US_ASCII) + mdatInhalt)
        }
        val vorher = file.length()

        assertNull(TagFile.write(file, CompressionTag(runs = 1, resultBytes = 77)))

        // Die mdat muss jetzt ihre echte Groesse tragen: Kopf + Inhalt,
        // aber ohne die angehaengte Marker-Box.
        val geschlossen = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(ftyp.size.toLong())
            raf.readInt()
        }
        assertEquals(8 + mdatInhalt.size, geschlossen)
        assertTrue("Datei muss gewachsen sein", file.length() > vorher)
        assertEquals(77L, TagFile.read(file)!!.resultBytes)
    }

    @Test
    fun `Container ohne ftyp wird nicht angefasst`() {
        val original = "das ist kein video, sondern text".toByteArray()
        val file = temp.newFile("falsch.mp4").apply { writeBytes(original) }

        val reason = TagFile.write(file, CompressionTag(runs = 1))

        assertNotNull("Ein Fremdformat muss abgelehnt werden", reason)
        assertArrayEquals(original, file.readBytes())
    }

    // ==================================================================
    // Byte-genaue Beweise je Container
    //
    // Die Tests oben pruefen den Marker. Die folgenden pruefen, was das
    // Schreiben mit dem **Container** macht: ein Marker, der die Bilddaten
    // oder den Video-Kopf zerlegt, waere der teuerste Erfolg dieses
    // Projekts - die Datei liesse sich weiterhin oeffnen, und der Schaden
    // fiele erst dem Nutzer auf.
    // ==================================================================

    /**
     * JPEG: Das APP1-Segment wird ersetzt, **jedes andere Segment bleibt
     * byte-gleich**, und die Bilddaten ab `SOS` sind identisch.
     *
     * Gefangen wird damit der Fall, dass die Bibliothek beim Umschreiben
     * neu serialisiert und dabei etwa den EXIF-Block umsortiert oder ein
     * Kommentar-Segment fallenlaesst. Ein Bild mit einem fehlenden
     * APP0/Tabellensegment kann manchmal noch dekodieren - aber es ist
     * nicht mehr dieselbe Datei.
     */
    @Test
    fun `JPEG-Schreiben laesst jedes andere Segment byte-gleich`() {
        val file = jpegFile("segmente.jpg")
        val before = jpegSegments(file.readBytes())

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))
        val after = jpegSegments(file.readBytes())

        // Vorher gab es kein APP1 - die Bibliothek legt es an.
        assertTrue("vorher darf kein APP1 drin sein", before.none { it.first == 0xE1 })
        assertTrue("nachher muss ein APP1 drin sein", after.any { it.first == 0xE1 })
        val beforeRest = before.filter { it.first != 0xE1 }
        val afterRest = after.filter { it.first != 0xE1 }
        assertEquals("Segmentanzahl darf sich nur um APP1 aendern", beforeRest.size, afterRest.size)
        for (i in beforeRest.indices) {
            assertEquals(
                "Segment FF${"%02X".format(beforeRest[i].first)} wurde veraendert",
                beforeRest[i].first,
                afterRest[i].first
            )
            assertArrayEquals(
                "Segment FF${"%02X".format(beforeRest[i].first)} ist nicht mehr byte-gleich",
                beforeRest[i].second,
                afterRest[i].second
            )
        }
    }

    /**
     * JPEG: Ein Kamera-Kommentar ueberlebt **Zeichen fuer Zeichen**, und
     * der Marker steht davor.
     *
     * Der Vergleich ist ein `assertEquals` auf den ganzen Text, nicht ein
     * `contains("Belichtungsreihe")`: genau so faellt ein Umschreiben auf,
     * das Leerzeichen am Ende abschneidet, Zeilenumbrueche normalisiert
     * oder den Text auf eine Laenge kuerzt.
     */
    @Test
    fun `JPEG bewahrt fremden UserComment Zeichen fuer Zeichen`() {
        val fremd = "Belichtungsreihe 3/3 - ISO 100, 1/250 s  "
        val file = jpegWithForeignComment("fremd.jpg", fremd)

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val comment = userComment(file)
        assertNotNull("UserComment ist verschwunden", comment)
        assertTrue("Fremdtext fehlt: <$comment>", comment!!.endsWith(fremd))
        assertEquals("Marker muss vorne stehen", 0, comment.indexOf(CompressionTag.MAGIC))
        assertEquals("Der Fremdtext muss unveraendert am Ende stehen", fremd, comment.substring(comment.indexOf(" | ") + 3))
    }

    /**
     * JPEG: Die uebrigen EXIF-Felder bleiben erhalten. Der Marker wird in
     * das vorhandene APP1-Segment geschrieben - ein Werkzeug, das dabei
     * das ganze Segment neu aufbaut, verliert alles, was es nicht kennt.
     */
    @Test
    fun `JPEG bewahrt die uebrigen EXIF-Felder`() {
        val file = jpegWithForeignComment("felder.jpg", "Notiz")
        val before = ExifInterface(file.absolutePath)

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val after = ExifInterface(file.absolutePath)
        for (tag in listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_IMAGE_DESCRIPTION
        )) {
            assertEquals("Feld $tag ist veraendert", before.getAttribute(tag), after.getAttribute(tag))
        }
    }

    /**
     * JPEG: Ein vorhandener Kommentar mit Umlaut wird **nicht**
     * beschrieben. `ExifInterface` kann in `UserComment` nur ASCII, und
     * der Text kaeme als "Gr??e" zurueck - der Marker waere drin, der
     * Kommentar des Nutzers aber weg.
     *
     * Der Vertrag sagt: dann ist der Rueckgabewert der Grund und die
     * Datei bleibt unangetastet. Der Test prueft beides, weil ein
     * abgelehnter Schreibvorgang, der die Datei trotzdem anfasst, genau
     * der Datenverlust ist, den die Ablehnung verhindern soll.
     */
    @Test
    fun `Kommentar mit Umlaut wird abgelehnt statt zerschrieben`() {
        // Ueber eine Latin-1-kodierte Datei nachstellen: ExifInterface
        // liest sie als "Gr??e", und so sieht ein echter Kamera-Kommentar
        // aus, der nicht ASCII ist.
        val file = jpegFile("umlaut.jpg")
        writeLatin1Comment(file, "Kamera Gr\u00f6\u00dfe")
        val before = file.readBytes()
        val gelesen = userComment(file)
        assertNotNull(gelesen)

        val reason = TagFile.write(file, CompressionTag(runs = 1))

        assertNotNull("Ein nicht schreibbarer Kommentar muss einen Grund liefern", reason)
        assertTrue("Grund nennt den Kommentar: $reason", reason!!.contains("UserComment"))
        assertArrayEquals("Die Datei darf dabei nicht angefasst werden", before, file.readBytes())
    }

    /**
     * PNG: Der `eXIf`-Chunk wird hinter `IHDR` eingefuegt, **IDAT bleibt
     * byte-gleich**, alle CRCs stimmen, und ein zweiter Lauf legt keinen
     * zweiten Chunk an.
     *
     * Die CRC-Pruefung ist der eigentliche Punkt: ein eingefuegter Chunk
     * mit falscher Pruefsumme macht die Datei fuer strenge Dekoder
     * unlesbar, waehrend sie in der Galerie noch angezeigt wird.
     */
    @Test
    fun `PNG fuegt eXIf hinter IHDR ein und laesst IDAT unberuehrt`() {
        val file = pngFile()
        val before = pngChunks(file.readBytes())
        assertEquals(listOf("IHDR", "IDAT", "IEND"), before.map { it.type })

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val after = pngChunks(file.readBytes())
        assertEquals(listOf("IHDR", "eXIf", "IDAT", "IEND"), after.map { it.type })
        assertTrue(
            "Alle Chunks brauchen eine gueltige CRC: " +
                after.filterNot { it.crcOk }.joinToString { it.type },
            after.all { it.crcOk }
        )
        assertArrayEquals("IHDR ist veraendert", before[0].data, after[0].data)
        assertArrayEquals("IDAT ist veraendert", before[1].data, after[2].data)
        assertEquals(TagFile.read(file)!!.runs, 1)
    }

    /** PNG: Ein zweiter Lauf ersetzt den Chunk, statt einen zweiten anzulegen. */
    @Test
    fun `PNG bekommt bei drei Laeufen genau einen eXIf-Chunk`() {
        val file = pngFile()
        repeat(3) { run ->
            assertNull(TagFile.write(file, CompressionTag(runs = run + 1)))
        }

        val chunks = pngChunks(file.readBytes())
        assertEquals("eXIf darf nur einmal vorkommen", 1, chunks.count { it.type == "eXIf" })
        assertEquals(3, TagFile.read(file)!!.runs)
        assertTrue("CRCs nach dem Ersetzen", chunks.all { it.crcOk })
    }

    /**
     * WebP: Beim ersten Lauf entsteht ein `VP8X`-Kopf, weil EXIF in WebP
     * nur dort angekuendigt werden kann. Der `VP8`-Datenblock bleibt
     * byte-gleich, die RIFF-Groesse passt zur Datei, und der `EXIF`-Chunk
     * steht genau einmal drin.
     *
     * Ohne die Groessenpruefung waere die Datei fuer den Dekoder
     * abgeschnitten - Chrome liest dann nur den Anfang und zeigt ein
     * halbes Bild.
     */
    @Test
    fun `WebP legt VP8X an und laesst den Bildblock unberuehrt`() {
        val file = webpFile()
        val before = webpChunks(file.readBytes())
        // Der FourCC heisst "VP8 " - mit Leerzeichen, vier Zeichen.
        assertEquals(listOf("VP8 "), before.map { it.type })

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val bytes = file.readBytes()
        val after = webpChunks(bytes)
        assertEquals(listOf("VP8X", "VP8 ", "EXIF"), after.map { it.type })
        assertArrayEquals("Der Bildblock ist veraendert", before[0].data, after[1].data)
        assertEquals(
            "RIFF-Groesse passt nicht zur Datei",
            bytes.size - 8,
            readIntLe(bytes, 4)
        )
        assertEquals(1, TagFile.read(file)!!.runs)
    }

    /** WebP: Der EXIF-Chunk wird ersetzt, nicht verdoppelt. */
    @Test
    fun `WebP bekommt bei drei Laeufen genau einen EXIF-Chunk`() {
        val file = webpFile()
        repeat(3) { run ->
            assertNull(TagFile.write(file, CompressionTag(runs = run + 1)))
        }

        val bytes = file.readBytes()
        assertEquals("EXIF darf nur einmal vorkommen", 1, webpChunks(bytes).count { it.type == "EXIF" })
        assertEquals(bytes.size - 8, readIntLe(bytes, 4))
        assertEquals(3, TagFile.read(file)!!.runs)
    }

    /**
     * MP4: Der Container-Kopf bleibt **byte-gleich**, die Datei waechst
     * genau um die Box, und die Box deckt genau diesen Anhang.
     *
     * "Ohne Neu-Muxung" ist die eigentliche Zusage: alle Offsets in `moov`
     * zeigen weiter auf ihre Daten. Verschoebe das Anhaengen auch nur ein
     * Byte im Kopf, waere die Datei fuer den Player ein anderer Film - und
     * das faellt bei einem Testvideo ohne `moov` nicht auf.
     */
    @Test
    fun `MP4-Anhang laesst den Kopf unberuehrt und waechst nur um die Box`() {
        val file = mp4File()
        val original = file.readBytes()

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val patched = file.readBytes()
        assertTrue("Datei muss gewachsen sein", patched.size > original.size)
        assertArrayEquals(
            "Der Container-Kopf ist veraendert",
            original,
            patched.copyOf(original.size)
        )
        val boxSize = readInt(patched, original.size)
        assertEquals("Die Box deckt nicht genau den Anhang", patched.size - original.size, boxSize)
        assertEquals("uuid", String(patched, original.size + 4, 4, Charsets.US_ASCII))
        assertEquals("NothingBulkComp!", String(patched, original.size + 8, 16, Charsets.US_ASCII))
    }

    /**
     * MP4: Drei Laeufe hinterlassen genau eine Box. Zwei Boxen waeren
     * schlimmer als eine: [Mp4Tag.findTag] nimmt die erste,
     * [Mp4Tag.findTagInTail] die letzte - Pfad- und Strom-Leser wuerden
     * sich also widersprechen, und Job und Scan waeren wieder uneinig.
     */
    @Test
    fun `MP4 hat nach drei Laeufen genau eine Marker-Box`() {
        val file = mp4File()
        repeat(3) { run ->
            assertNull(TagFile.write(file, CompressionTag(runs = run + 1)))
        }

        val bytes = file.readBytes()
        assertEquals("uuid darf nur einmal vorkommen", 1, countOf(bytes, "uuid"))
        assertEquals(3, TagFile.read(file)!!.runs)
        assertEquals(3, CompressionTag.find(Mp4Tag.findTagInTail(tail(file)))!!.runs)
    }

    /**
     * MP4: Schreiben und Strom-Lesen stimmen ueberein. Das ist der Kern
     * des urspruenglichen Fehlers in klein: der Job schreibt ueber den
     * Pfad, der Scan liest die letzten Bytes - liefern die beiden
     * verschiedene Zaehler, ist der Filter kaputt, obwohl beide fuer sich
     * "funktionieren".
     */
    @Test
    fun `MP4-Pfadleser und Stromleser liefern denselben Marker`() {
        val file = mp4File()
        assertNull(TagFile.write(file, CompressionTag(runs = 2, sourceBytes = 4096, resultBytes = 1024)))

        val fromPath = TagFile.read(file)
        val fromTail = CompressionTag.find(Mp4Tag.findTagInTail(tail(file)))

        assertEquals(fromPath, fromTail)
        assertEquals(2, fromTail!!.runs)
    }

    /**
     * Ein Container, in den kein Marker passt, muss einen **Grund**
     * liefern - keinen stillen Erfolg. Hier: ein JPEG-Kopf vor Text, den
     * `ExifInterface` nicht als JPEG parsen kann. Der Rueckgabewert ist
     * die einzige Stelle, an der der Job noch erfaehrt, dass er das
     * Ergebnis verwerfen muss.
     *
     * **Gemessen** (androidx 1.3.7): bei so einer Datei schreibt die
     * Bibliothek ein APP1-Segment in die Bytes und **wirft danach**
     * `IOException("Failed to save new file")`. Der Marker steht also in
     * der Datei, und trotzdem ist der Rueckgabewert ein Grund. Genau
     * dafuer gibt es den Vertrag "der Aufrufer verwirft sein Ergebnis":
     * ein Grund heisst **nicht** "die Datei ist unberuehrt", sondern
     * "verlass dich nicht auf diese Datei". Der Job schreibt in eine
     * Temp-Datei und verwirft sie - das Original sieht dieser Pfad nie.
     */
    @Test
    fun `nicht schreibbarer EXIF-Container liefert einen Grund`() {
        val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00) +
            "kein bild".toByteArray(Charsets.US_ASCII)
        val file = temp.newFile("kaputt.jpg").apply { writeBytes(original) }

        val reason = TagFile.write(file, CompressionTag(runs = 1))

        assertNotNull("Stiller Erfolg waere hier ein Datenverlust", reason)
        assertTrue("Grund ist leer: <$reason>", reason!!.isNotBlank())
        assertTrue("Grund nennt EXIF: <$reason>", reason.contains("EXIF"))
        assertNotEquals("Die Bytes muessen sich geaendert haben", original.size, file.length())
    }

    /**
     * MP4: Die Zusage "ohne Neu-Muxung" gilt fuer die ganze Datei, nicht
     * nur fuer den Kopf. Hier liegt eine `moov` zwischen `ftyp` und
     * `mdat` - die Offsets darin zeigen auf die Daten hinten. Wird auch
     * nur ein Byte davor verschoben, zeigt jeder Eintrag ins Leere, und
     * der Player findet die Spur nicht mehr, obwohl die Datei
     * syntaktisch in Ordnung aussieht.
     *
     * Deshalb: **jedes** Byte der Originaldatei bleibt an seinem Platz,
     * und die Box kommt ausschliesslich dahinter.
     */
    @Test
    fun `MP4-Anhang laesst eine vorhandene moov unberuehrt`() {
        val ftyp = byteArrayOf(0, 0, 0, 0x1C) + "ftypisom".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray(Charsets.US_ASCII)
        // moov mit mvhd-artigem Inhalt: 16 Nutzbytes, die auf die mdat
        // zeigen sollen.
        val moovInhalt = byteArrayOf(0, 0, 0, 8) + ByteArray(8) +
            byteArrayOf(0, 0, 0, 0x3C, 0, 0, 0, 0) + ByteArray(4)
        val moov = byteArrayOf(0, 0, 0, (8 + moovInhalt.size).toByte()) +
            "moov".toByteArray(Charsets.US_ASCII) + moovInhalt
        val mdat = byteArrayOf(0, 0, 0, 0x18) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(16)
        val file = temp.newFile("mit-moov.mp4").apply { writeBytes(ftyp + moov + mdat) }
        val original = file.readBytes()

        assertNull(TagFile.write(file, CompressionTag(runs = 1)))

        val patched = file.readBytes()
        assertTrue("Die Datei muss gewachsen sein", patched.size > original.size)
        assertArrayEquals(
            "ftyp, moov oder mdat sind veraendert",
            original,
            patched.copyOf(original.size)
        )
        assertEquals("Wachstum ist nicht genau die Box", readInt(patched, original.size), patched.size - original.size)
        // Die Box-Tabelle muss die moov weiterhin finden - sonst haette
        // die Erkennung sie nur uebersprungen.
        assertTrue(
            "moov fehlt in der Boxliste: ${Mp4Tag.describe(file)}",
            Mp4Tag.describe(file).contains("moov")
        )
    }

    /**
     * Dieselbe Ablage fuer alle Video-Marken: M4V und QuickTime (MOV)
     * beginnen ebenfalls mit `ftyp` und bekommen dieselbe Box. Die
     * Erweiterung entscheidet nichts, der Dateikopf schon.
     */
    @Test
    fun `M4V und MOV bekommen dieselbe Box wie MP4`() {
        for ((name, brand) in listOf("video.m4v" to "M4V ", "video.mov" to "qt  ")) {
            // ftyp: 4 Groesse + 4 Typ + 4 Brand + 4 Minor = 16 Bytes. Die
            // Groesse wird aus der Nutzlast berechnet, nicht gezaehlt -
            // eine falsche Groesse macht die Box-Tabelle unlesbar, und
            // dann testet der Fall nur die Ablehnung.
            val ftypInhalt = brand.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0)
            val ftyp = byteArrayOf(0, 0, 0, (8 + ftypInhalt.size).toByte()) +
                "ftyp".toByteArray(Charsets.US_ASCII) + ftypInhalt
            val file = temp.newFile(name).apply {
                writeBytes(ftyp + byteArrayOf(0, 0, 0, 0x10) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8))
            }

            assertEquals("Container $name nicht erkannt", TagFile.Family.MP4, TagFile.familyOf(file))
            assertNull("Schreiben in $name", TagFile.write(file, CompressionTag(runs = 2)))
            assertEquals("Ruecklesen aus $name", 2, TagFile.read(file)!!.runs)
        }
    }

    /** Ein Container, den die App gar nicht kennt, wird abgelehnt. */
    @Test
    fun `unbekannter Container liefert einen Grund`() {
        val original = "nur text".toByteArray()
        val file = temp.newFile("notiz.txt").apply { writeBytes(original) }

        assertEquals("Container nicht erkannt", TagFile.write(file, CompressionTag(runs = 1)))
        assertArrayEquals(original, file.readBytes())
    }

    /**
     * Ein erfolgreicher Schreibvorgang liefert `null` - fuer **alle drei**
     * Familien. Ein Grund, der bei Erfolg zurueckkommt, wuerde den Job
     * eine gesunde Datei verwerfen lassen.
     */
    @Test
    fun `Erfolg liefert fuer jede Familie null`() {
        assertNull("JPEG", TagFile.write(jpegFile("a.jpg"), CompressionTag(runs = 1)))
        assertNull("PNG", TagFile.write(pngFile("b.png"), CompressionTag(runs = 1)))
        assertNull("WebP", TagFile.write(webpFile("c.webp"), CompressionTag(runs = 1)))
        assertNull("MP4", TagFile.write(mp4File("d.mp4"), CompressionTag(runs = 1)))
    }

    /**
     * Umlaute im **neuen** Text sind ebenfalls ein Grund: sie kaemen als
     * `?` zurueck, und der Ruecklesevergleich wuerde den Marker nicht
     * finden. Der Fall entsteht, wenn eine App-Version den Marker mit
     * einem Umlaut im App-Namen schreibt.
     */
    @Test
    fun `Marker mit Nicht-ASCII wird abgelehnt statt mit Fragezeichen zu landen`() {
        val file = jpegFile("umlaut-marker.jpg")
        val before = file.readBytes()

        val reason = TagFile.write(file, CompressionTag(runs = 1, appVersion = "Gr\u00f6\u00dfe"))

        assertNotNull("Ein Marker, der als '?' zurueckkommt, ist kein Erfolg", reason)
        assertArrayEquals(before, file.readBytes())
    }

    /** Die Kennung fuer UTF-16 wird erkannt, gewoehnlicher Text nicht. */
    @Test
    fun `UTF-16-Kennung wird als Datenverlust erkannt, JIS-Text nicht`() {
        // "UNICODE\0" plus Text in UTF-16: getAttribute liefert nur die
        // Kennung, der Text dahinter waere beim Umschreiben weg.
        val wide = "UNICODE\u0000".toByteArray(Charsets.US_ASCII) +
            "Kamera".toByteArray(Charsets.UTF_16BE)
        assertNotNull(
            "UTF-16-Kommentar muss abgelehnt werden",
            TagFile.commentRewriteProblem(wide, "nothing-bulk:1 runs=1 | UNICODE")
        )

        // Ein gewoehnlicher Kommentar, der zufaellig mit "JIS" beginnt,
        // ist kein Kennzeichen und darf nicht abgelehnt werden.
        val harmlos = "JIS-Kamera 2024".toByteArray(Charsets.US_ASCII)
        assertNull(
            "Gewoehnlicher Text darf nicht als Kennung gelten",
            TagFile.commentRewriteProblem(harmlos, "nothing-bulk:1 runs=1 | JIS-Kamera 2024")
        )
    }

    // ==================================================================
    // Toleranz beim Finden
    //
    // Der Marker steht in einer Datei, die auch andere Werkzeuge
    // beschreiben. Eine strenge Grammatik wuerde bei der ersten
    // Format-Aenderung jede alte Datei als "nicht komprimiert" einstufen -
    // und genau das ist der Fehler, den der Marker verhindern soll. Diese
    // Tests halten das Format offen, ohne es zu veraendern.
    // ==================================================================

    /**
     * Ein Marker mit Schluesseln, die dieses Programm nicht kennt, wird
     * gelesen. Sonst waere jede neue Version ein Rueckschritt: eine Datei
     * von morgen saehe heute wie "nie komprimiert" aus.
     */
    @Test
    fun `find ignoriert unbekannte Schluessel`() {
        val tag = CompressionTag.find("nothing-bulk:1 runs=3 src=10 out=5 future=42 x=yz")

        assertNotNull("Marker mit fremden Schluesseln muss lesbar bleiben", tag)
        assertEquals(3, tag!!.runs)
        assertEquals(10L, tag.sourceBytes)
        assertEquals(5L, tag.resultBytes)
    }

    /**
     * Eine fremde Versionsnummer macht den Marker nicht ungueltig. Die
     * Zahl hinter dem Doppelpunkt zaehlt nicht mehr - der Marker schon.
     */
    @Test
    fun `find akzeptiert eine andere Formatversion`() {
        assertEquals(7, CompressionTag.find("nothing-bulk:2 runs=7")!!.runs)
        assertEquals(4, CompressionTag.find("nothing-bulk:99 runs=4 out=8")!!.runs)
    }

    /**
     * Der Marker darf irgendwo in einem laengeren Kommentar stehen, und
     * der Bereich endet am `|` - ein angehaengter Fremdtext wird nicht
     * als Schluessel gelesen. Sonst wuerde aus "mein Text=Wert" ein
     * Phantasiefeld.
     */
    @Test
    fun `find liest nur bis zum Trenner`() {
        val tag = CompressionTag.find("Kamera A | nothing-bulk:1 runs=2 | Belichtung 3/3")

        assertNotNull(tag)
        assertEquals(2, tag!!.runs)
        assertEquals("", tag.appVersion)
    }

    /** Ein Kommentar ohne Marker liefert `null`, nicht einen Marker mit Vorgaben. */
    @Test
    fun `find liefert ohne Marker null`() {
        assertNull(CompressionTag.find(null))
        assertNull(CompressionTag.find(""))
        assertNull(CompressionTag.find("   "))
        assertNull(CompressionTag.find("Belichtungsreihe 3/3"))
        assertNull(CompressionTag.find("nothing-bulkish:1 runs=2"))
    }

    /**
     * Fehlt der Zaehler, ist der Marker trotzdem ein Marker: er sagt
     * "diese Datei lief schon durch". Ein fehlendes Feld auf einen
     * Standardwert zu legen ist besser, als die Datei erneut zu
     * komprimieren.
     */
    @Test
    fun `find ohne Zaehler gilt trotzdem als Marker`() {
        val tag = CompressionTag.find("nothing-bulk:1 src=100")

        assertNotNull(tag)
        assertEquals(1, tag!!.runs)
        assertEquals(100L, tag.sourceBytes)
    }

    /** Ein Zaehler unter eins wird auf eins gehoben - "null Laeufe" gibt es nicht. */
    @Test
    fun `find hebt einen Zaehler unter eins auf eins`() {
        assertEquals(1, CompressionTag.find("nothing-bulk:1 runs=0")!!.runs)
        assertEquals(1, CompressionTag.find("nothing-bulk:1 runs=-4")!!.runs)
        assertEquals(1, CompressionTag.find("nothing-bulk:1 runs=abc")!!.runs)
    }

    /**
     * `merge` ersetzt einen vorhandenen Marker, statt einen zweiten
     * anzulegen - und laesst den Fremdtext davor und dahinter stehen.
     */
    @Test
    fun `merge ersetzt den Marker in der Mitte eines Kommentars`() {
        val vorhanden = "Belichtungsreihe 3/3 | nothing-bulk:1 runs=1 | Rest"
        val merged = CompressionTag.merge(vorhanden, CompressionTag(runs = 2))

        assertEquals("Belichtungsreihe 3/3 | nothing-bulk:1 runs=2 | Rest", merged)
        assertEquals(1, Regex(CompressionTag.MAGIC).findAll(merged).count())
    }

    /**
     * Ohne vorhandenen Marker kommt der neue davor. Das ist Absicht: ein
     * langer Kamerakommentar darf den Marker nicht aus dem gelesenen
     * Bereich schieben.
     */
    @Test
    fun `merge setzt den Marker vor fremden Text`() {
        val merged = CompressionTag.merge("Belichtungsreihe", CompressionTag(runs = 1))

        assertTrue("Marker fehlt: $merged", merged.startsWith(CompressionTag.MAGIC))
        assertTrue("Fremdtext fehlt: $merged", merged.endsWith("Belichtungsreihe"))
    }

    /**
     * Das Schreiben veraendert das Format nicht: was `format` erzeugt,
     * liest `find` mit denselben Werten zurueck. Dieser Test haelt die
     * Zusage "Format unveraendert" fest - er bricht, sobald jemand ein
     * Feld umbenennt.
     */
    @Test
    fun `format und find sind zueinander passend`() {
        val tag = CompressionTag(
            runs = 5,
            sourceBytes = 4_194_304,
            resultBytes = 1_048_576,
            atMillis = 1_762_000_000_000,
            appVersion = "1.9.1"
        )

        assertEquals(
            "nothing-bulk:1 runs=5 src=4194304 out=1048576 at=1762000000000 app=1.9.1",
            tag.format()
        )
        assertEquals(tag, CompressionTag.find(tag.format()))
    }

    /** Ein reiner ASCII-Kommentar mit `ASCII`-Kennung ist verlustfrei. */
    @Test
    fun `ASCII-Kennung gilt nicht als Datenverlust`() {
        val ascii = "ASCII\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII) + "Kamera A".toByteArray(Charsets.US_ASCII)

        assertNull(
            "Ein ASCII-Kommentar laesst sich verlustfrei umschreiben",
            TagFile.commentRewriteProblem(ascii, "nothing-bulk:1 runs=1 | Kamera A")
        )
    }

    /** Ohne vorhandenen Kommentar gibt es nichts zu verlieren. */
    @Test
    fun `ohne vorhandenen Kommentar ist das Umschreiben unbedenklich`() {
        assertNull(TagFile.commentRewriteProblem(null, "nothing-bulk:1 runs=1"))
        assertNull(TagFile.commentRewriteProblem(ByteArray(0), "nothing-bulk:1 runs=1"))
    }

    // ------------------------------------------------------------------
    // Lesehilfen fuer die neuen Tests
    // ------------------------------------------------------------------

    /**
     * Schreibt einen Kommentar als Latin-1 in ein sonst leeres APP1. So
     * sieht ein Kamera-Kommentar aus, der nicht ASCII ist - und genau den
     * darf das Umschreiben nicht zu `?` machen.
     */
    private fun writeLatin1Comment(file: File, comment: String) {
        val bytes = comment.toByteArray(Charsets.ISO_8859_1)
        val app1 = exifWithUserComment(bytes)
        val original = file.readBytes()
        file.writeBytes(concat(original.copyOf(2), app1, original.copyOfRange(2, original.size)))
    }

    /**
     * Ein minimales EXIF: IFD0 mit Verweis auf den EXIF-IFD, dort ein
     * `UserComment` (0x9286) als undefinierter Typ. Die Kennung fehlt
     * bewusst - so steht der Text nackt in der Datei, wie es
     * `ExifInterface` selbst schreibt.
     */
    private fun exifWithUserComment(comment: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        fun le32(value: Int) = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        val ifd0 = 8
        val exifIfd = ifd0 + 2 + 12 + 4
        val dataAt = exifIfd + 2 + 12 + 4
        out.write("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))
        out.write("II".toByteArray(Charsets.US_ASCII))
        out.write(le16(42))
        out.write(le32(ifd0))
        // IFD0: ein Eintrag, Verweis auf den EXIF-IFD
        out.write(le16(1))
        out.write(le16(0x8769))
        out.write(le16(4))
        out.write(le32(1))
        out.write(le32(exifIfd))
        out.write(le32(0))
        // EXIF-IFD: ein Eintrag, UserComment
        out.write(le16(1))
        out.write(le16(0x9286))
        out.write(le16(7))
        out.write(le32(comment.size))
        out.write(le32(dataAt))
        out.write(le32(0))
        out.write(comment)
        val payload = out.toByteArray()
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) +
            payload
    }

    private fun countOf(bytes: ByteArray, needle: String): Int {
        val target = needle.toByteArray(Charsets.US_ASCII)
        var count = 0
        outer@ for (i in 0..bytes.size - target.size) {
            for (j in target.indices) if (bytes[i + j] != target[j]) continue@outer
            count++
        }
        return count
    }

    private companion object {
        /** 2x2-PNG, 73 Bytes, gueltige CRCs. */
        const val PNG_2X2 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR42mM4IScHRAwQCgAfJgQRSo6NIAAAAABJRU5ErkJggg=="

        /** 2x2-WebP (verlustbehaftetes VP8), 66 Bytes. */
        const val WEBP_2X2 =
            "UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoCAAIAAUAmJaACdLoB+AADsAD+5fo/+ema8wf5Of/WV/Mr+ZX/DwAA"
    }
}
