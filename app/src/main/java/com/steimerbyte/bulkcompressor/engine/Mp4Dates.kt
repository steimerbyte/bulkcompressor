package com.steimerbyte.bulkcompressor.engine

import com.steimerbyte.bulkcompressor.log.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Aufnahmezeitpunkt im MP4-Container setzen.
 *
 * Videos haben kein EXIF. Was die Galerie bei einem Video trotzdem
 * chronologisch einordnet, ist die `creation_time` in den Container-
 * Kopfboxen `mvhd` (Film), `tkhd` (Spur) und `mdhd` (Mediensegment). Und
 * genau diese Zeit setzt `MediaMuxer` beim Schreiben auf **jetzt** - nach
 * einem Lauf steht im Video also der Kompressionszeitpunkt, nicht die
 * Aufnahme.
 *
 * Der Dateizeitstempel allein reicht dafuer nicht: er ist die schwaechere
 * Angabe, wird von manchen Anbietern gar nicht geschrieben und sagt
 * nichts ueber das Video selbst. Die Kopfbox ist der Ort, an dem der
 * Zeitpunkt mit der Datei wandert - auch dann, wenn sie kopiert wird.
 *
 * Aufbau (ISO/IEC 14496-12): jede Box beginnt mit Groesse (4 Byte,
 * gross-endian) und Typ (4 Byte ASCII). Danach folgt bei `mvhd`/`mdhd`/
 * `tkhd` ein Byte Version, drei Byte Flags, dann die Zeitfelder:
 *
 * - Version 0: `creation_time` (4 Byte, Sekunden seit 1904-01-01 UTC),
 *   `modification_time` (4 Byte), dann Zeitskala und Dauer.
 * - Version 1: dieselben Felder als 8 Byte.
 *
 * Die Zeitfelder werden **unsigned** gelesen und geschrieben: ein Datum
 * aus dem Jahr 2024 liegt bei rund 3,8 Milliarden Sekunden und passt
 * damit nicht in ein vorzeichenbehaftetes 32-Bit-Int. `Int` als Behaelter
 * ist trotzdem richtig, weil Java die vier Bytes unveraendert abbildet -
 * nur darf man nicht mit dem Int rechnen.
 */
internal object Mp4Dates {

    /** 1904-01-01 bis 1970-01-01 in Sekunden. */
    private const val EPOCH_OFFSET_SECONDS = 2_082_844_800L

    /** Boxen mit Zeitstempel-Kopf. `mvhd` und `mdhd` sind gleich aufgebaut, `tkhd` ebenfalls. */
    private val TIME_BOXES = setOf("mvhd", "mdhd", "tkhd")

    private const val HEADER = 8
    private const val LARGE_HEADER = 16
    private const val UINT32_MASK = 0xFFFFFFFFL

    /**
     * Ergebnis des Schreibens.
     *
     * @param boxes Anzahl gefundener Zeit-Boxen (mvhd + tkhd + mdhd).
     * @param written Anzahl tatsaechlich gesetzter `creation_time`-Felder.
     * @param skipped Grund, wenn etwas nicht gesetzt wurde - nie leer,
     *                wenn `written < boxes`.
     */
    data class Patch(val boxes: Int, val written: Int, val skipped: String? = null) {
        val ok: Boolean get() = written > 0
    }

    /**
     * Setzt `creation_time` in allen Zeit-Boxen auf [millis].
     *
     * `modification_time` bleibt unangetastet: sie beschreibt, wann der
     * Container zuletzt geschrieben wurde, und das ist nach einem Lauf
     * korrekt "jetzt". Nur die Aufnahmezeit ist die falsche.
     *
     * Keine Methode wirft: ein nicht schreibbarer Container darf keinen
     * Lauf abbrechen - aber der Fehlschlag steht im Protokoll.
     */
    fun setCreationTime(file: File, millis: Long): Patch {
        if (millis <= 0L) return Patch(0, 0, "kein Datum")
        if (!file.isFile || !file.canWrite()) return Patch(0, 0, "Datei nicht schreibbar")
        val seconds = millis / 1000L + EPOCH_OFFSET_SECONDS
        if (seconds > UINT32_MASK) return Patch(0, 0, "Datum ausserhalb des MP4-Bereichs")

        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val timeBoxes = walk(raf, 0L, file.length()).filter { it.type in TIME_BOXES }
                var written = 0
                val failures = mutableListOf<String>()
                for (found in timeBoxes) {
                    if (patchCreation(raf, found, seconds)) written++ else failures += "${found.type}@${found.offset}"
                }
                val reason = when {
                    timeBoxes.isEmpty() -> "keine Zeit-Box im Container gefunden"
                    failures.isEmpty() -> null
                    else -> "nicht gesetzt: ${failures.joinToString(", ")}"
                }
                if (written == 0) {
                    Log.w("Meta", "MP4-Aufnahmezeit nicht gesetzt · ${file.name} · ${reason ?: "unbekannt"}")
                }
                Patch(timeBoxes.size, written, reason)
            }
        } catch (t: Throwable) {
            Log.w("Meta", "MP4-Aufnahmezeit nicht setzbar · ${file.name}", t)
            Patch(0, 0, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Liest die Aufnahmezeit aus `mvhd` - die Gegenprobe nach dem
     * Schreiben und die Grundlage dafuer, dass der Verifier pruefen kann,
     * ob die Chronologie wirklich erhalten ist.
     *
     * @return Zeitpunkt in Millisekunden oder null, wenn keine lesbare
     *         `mvhd` existiert.
     */
    fun readCreationTime(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            // walk liefert die Hierarchie flach - die mvhd ist damit ohne
            // Suche nach der moov-Huelle erreichbar.
            val mvhd = walk(raf, 0L, file.length()).firstOrNull { it.type == "mvhd" }
                ?: return null
            raf.seek(mvhd.payloadOffset)
            val version = raf.readUnsignedByte()
            raf.seek(mvhd.payloadOffset + 4)
            val seconds = if (version == 1) readUint64(raf) ?: return null else readUint32(raf)
            if (seconds <= EPOCH_OFFSET_SECONDS) return null
            (seconds - EPOCH_OFFSET_SECONDS) * 1000L
        }
    }.getOrNull()

    private data class Found(val type: String, val offset: Long, val payloadOffset: Long, val payloadEnd: Long)

    /**
     * Alle Boxen flach, in Dateireihenfolge. Container (`moov`, `trak`,
     * `mdia`, `minf`, `stbl`, `edts`) werden rekursiv geoeffnet, damit der
     * Aufrufer keine Huelle kennen muss.
     */
    private fun walk(raf: RandomAccessFile, from: Long, to: Long): List<Found> {
        val result = mutableListOf<Found>()
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")
        var at = from
        while (at + HEADER <= to) {
            raf.seek(at)
            var size = raf.readInt().toLong() and UINT32_MASK
            val type = readType(raf) ?: break
            var header = HEADER.toLong()
            if (size == 1L) {
                size = readUint64(raf) ?: break
                header = LARGE_HEADER.toLong()
            } else if (size == 0L) {
                size = to - at
            }
            if (size < header || at + size > to) break
            val payload = at + header
            val end = at + size
            result += Found(type, at, payload, end)
            if (type in containers) result += walk(raf, payload, end)
            at = end
        }
        return result
    }

    private fun patchCreation(raf: RandomAccessFile, box: Found, seconds: Long): Boolean {
        raf.seek(box.payloadOffset)
        val version = raf.readUnsignedByte()
        raf.seek(box.payloadOffset + 4)
        return when (version) {
            0 -> {
                if (seconds > UINT32_MASK) return false
                raf.writeInt(seconds.toInt())
                true
            }
            1 -> {
                raf.writeLong(seconds)
                true
            }
            else -> false
        }
    }

    private fun readType(raf: RandomAccessFile): String? {
        val bytes = ByteArray(4)
        if (raf.read(bytes) != 4) return null
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun readUint32(raf: RandomAccessFile): Long = raf.readInt().toLong() and UINT32_MASK

    /**
     * 64-Bit-Feld. Ein Wert ab 2^63 kann kein Container sein - die
     * Rueckgabe ist dort null statt einer negativen Groesse, mit der die
     * Schleife endlos weiterliefe.
     */
    private fun readUint64(raf: RandomAccessFile): Long? {
        val value = raf.readLong()
        return if (value < 0L) null else value
    }
}
