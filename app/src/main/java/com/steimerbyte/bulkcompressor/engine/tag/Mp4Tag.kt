package com.steimerbyte.bulkcompressor.engine.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * Der Marker fuer Video-Container (MP4/M4V/MOV).
 *
 * `MediaMuxer` kann beim Schreiben **keine** freien Metadaten setzen - nur
 * Drehung und Ort. Was bleibt, ist der Weg, den die Spezifikation dafuer
 * vorsieht: eine `uuid`-Box auf Dateiebene (ISO/IEC 14496-12, Abschnitt
 * "uuid box"), deren 16-Byte-Kennung den Absender identifiziert. Fremde
 * Player ignorieren eine unbekannte Box; die Datei bleibt abspielbar.
 *
 * Zwei Dinge muessen dabei stimmen, sonst ist der Container hinterher
 * kaputt:
 *
 * 1. **Angehaengt wird nur am Ende** - dann bleiben alle Offsets in
 *    `moov` gueltig, und nichts muss umgeschrieben werden.
 * 2. **Die Box-Tabelle muss vorher sauber sein.** Eine `mdat`-Box mit
 *    Groesse 0 ("reicht bis Dateiende") wuerde unsere Box verschlucken;
 *    ihr Groessenfeld wird deshalb vorher auf den tatsaechlichen Wert
 *    gesetzt. Alles andere wird nicht angefasst, sondern gemeldet.
 * 3. **Ein vorhandener Marker wird ersetzt, nicht verdoppelt.** Beim
 *    zweiten Lauf waechst der Zaehler von `runs=1` auf `runs=2`; haengte
 *    man einfach an, stuenden zwei Marker in derselben Datei, und welcher
 *    gewinnt, haengt davon ab, ob von vorne oder von hinten gesucht wird.
 *    [findTag] nimmt den ersten, [findTagInTail] den letzten - bei zwei
 *    Markern waeren sich Pfad- und Strom-Leser also uneinig, und genau
 *    darueber wuerde der Filter den Job anders einordnen als der Scan.
 */
internal object Mp4Tag {

    /**
     * Kennung der Box: 16 ASCII-Bytes, damit sie in einem Hexdump lesbar
     * ist und garantiert mit keinem anderen Hersteller kollidiert.
     */
    private val USER_TYPE = "NothingBulkComp!".toByteArray(Charsets.US_ASCII)

    /** Mehr als das wird vom Marker nicht gelesen - er ist eine Zeile. */
    private const val MAX_PAYLOAD = 4096

    /**
     * Kopf einer `uuid`-Box: 4 Byte Groesse, 4 Byte Typ, 16 Byte Kennung.
     * Dieselbe Zahl steht in [findTagInTail] als Untergrenze - die beiden
     * Stellen beschreiben dasselbe Format und muessen zusammenpassen.
     */
    private const val HEADER = 24

    private const val TYPE_UUID = "uuid"
    private const val TYPE_FTYP = "ftyp"

    /** Eine Box auf Dateiebene: Offset, Groesse, Typ. */
    internal data class Box(val offset: Long, val size: Long, val type: String) {
        val end: Long get() = offset + size
    }

    /**
     * Ist das ein Container, den diese Klasse versteht? Geprueft wird die
     * erste Box: ein ISO-BMFF-File beginnt mit `ftyp`.
     */
    fun looksLikeMp4(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return@use false
            raf.seek(4)
            readType(raf) == TYPE_FTYP
        }
    }.getOrDefault(false)

    /** Liest den Marker; `null`, wenn keiner drinsteht. */
    fun findTag(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val boxes = walk(raf) ?: return@use null
            for (box in boxes) {
                if (box.type != TYPE_UUID || box.size < 28) continue
                raf.seek(box.offset + 8)
                val userType = ByteArray(16)
                raf.readFully(userType)
                if (!userType.contentEquals(USER_TYPE)) continue
                val length = (box.size - 24).toInt().coerceAtMost(MAX_PAYLOAD)
                val payload = ByteArray(length)
                raf.readFully(payload)
                return@use String(payload, Charsets.UTF_8).trim()
            }
            null
        }
    }.getOrNull()

    /**
     * Liest den Marker aus den letzten Bytes einer Datei.
     *
     * Fuer ein SAF-Dokument gibt es keinen Pfad und damit kein
     * [RandomAccessFile] - dort wird der Dateiende-Bereich gelesen und der
     * Marker darin gesucht. Die Box steht immer am Ende, weil diese Klasse
     * sie nur dort anlegt.
     */
    fun findTagInTail(tail: ByteArray): String? {
        val signature = TYPE_UUID.toByteArray(Charsets.US_ASCII) + USER_TYPE
        var index = tail.size - signature.size
        while (index >= 4) {
            if (matches(tail, index, signature)) {
                val start = index - 4
                val size = readInt(tail, start)
                if (size in (24 + 1)..(start + tail.size) && start + size <= tail.size) {
                    val payload = tail.copyOfRange(start + 24, start + size)
                    return String(payload, Charsets.UTF_8).trim()
                }
                return null
            }
            index--
        }
        return null
    }

    private fun matches(haystack: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at < 0 || at + needle.size > haystack.size) return false
        for (i in needle.indices) if (haystack[at + i] != needle[i]) return false
        return true
    }

    private fun readInt(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > bytes.size) return -1
        return ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
    }

    /**
     * Schreibt den Marker: ersetzt einen vorhandenen, sonst angehaengt.
     *
     * Gibt `null` zurueck, wenn es geklappt hat - sonst den Grund, warum
     * nichts geschrieben wurde. Ein Container, der nicht sauber gelesen
     * werden kann, wird **nicht** beschnitten: eine Datei, die sich nicht
     * ganz verstehen laesst, ist kein Ort fuer Reparaturversuche.
     *
     * Ersetzen ist kein Um-Muxen: die bestehende Box hat eine feste
     * Laenge, und die neue Zeile ist laenger (der Zaehler waechst um eine
     * Stelle) oder kuerzer. Sie wird deshalb an derselben Stelle neu
     * geschrieben und die Datei dahinter nachgerueckt - alle anderen
     * Bytes bleiben in ihrer Reihenfolge, nur ihre Offsets verschieben
     * sich mit der Box.
     */
    fun appendTag(file: File, payload: String): String? {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD) return "Marker zu lang (${bytes.size} B)"
        val raf = runCatching { RandomAccessFile(file, "rw") }
            .getOrElse { return "nicht schreibbar: ${it.message ?: it.javaClass.simpleName}" }
        raf.use {
            val boxes = walk(raf) ?: return "Box-Tabelle unlesbar"
            if (boxes.isEmpty()) return "keine Box-Tabelle"
            if (boxes.first().type != TYPE_FTYP) return "beginnt nicht mit ftyp"
            closeOpenEnded(raf, boxes)?.let { reason -> return reason }
            // Nach dem Schliessen neu lesen: die letzte Box hat jetzt ihre
            // echte Groesse, und eine vorhandene Marker-Box steht damit
            // erstmals als regulaere Box in der Tabelle.
            val current = walk(raf) ?: return "Box-Tabelle nach dem Schliessen unlesbar"
            val existing = current.lastOrNull { it.type == TYPE_UUID && isOurBox(raf, it) }
            return runCatching {
                if (existing == null) append(raf, bytes) else replace(raf, existing, bytes)
                null
            }.getOrElse { "Box nicht schreibbar: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Haengt eine neue Box hinter das Dateiende. */
    private fun append(raf: RandomAccessFile, bytes: ByteArray) {
        raf.seek(raf.length())
        raf.writeInt(HEADER + bytes.size)
        raf.write(TYPE_UUID.toByteArray(Charsets.US_ASCII))
        raf.write(USER_TYPE)
        raf.write(bytes)
    }

    /**
     * Schreibt die Box [box] mit neuem Inhalt und rueckt den Rest nach.
     *
     * Der Marker steht immer am Dateiende (so legt ihn [append] an), der
     * Rest ist also leer und das Nachruecken entfaellt. Der allgemeine
     * Weg bleibt trotzdem drin, weil eine fremde Datei die Box auch
     * woanders tragen kann - dann muss die Verschiebung stimmen.
     */
    private fun replace(raf: RandomAccessFile, box: Box, bytes: ByteArray) {
        val replacement = HEADER + bytes.size
        val tail = box.end
        val suffix = if (raf.length() > tail) {
            raf.seek(tail)
            ByteArray((raf.length() - tail).toInt()).also { raf.readFully(it) }
        } else {
            ByteArray(0)
        }
        raf.seek(box.offset)
        raf.writeInt(replacement)
        raf.write(TYPE_UUID.toByteArray(Charsets.US_ASCII))
        raf.write(USER_TYPE)
        raf.write(bytes)
        raf.write(suffix)
        raf.setLength(box.offset + replacement + suffix.size)
    }

    /** Traegt diese `uuid`-Box unsere Kennung? */
    private fun isOurBox(raf: RandomAccessFile, box: Box): Boolean {
        if (box.size < HEADER) return false
        raf.seek(box.offset + 8)
        val userType = ByteArray(USER_TYPE.size)
        raf.readFully(userType)
        return userType.contentEquals(USER_TYPE)
    }

    /**
     * Setzt eine Box mit Groesse 0 ("bis Dateiende") auf ihre echte
     * Groesse, damit Platz fuer eine weitere Box entsteht.
     *
     * @return null bei Erfolg (auch wenn nichts zu tun war), sonst Grund.
     */
    private fun closeOpenEnded(raf: RandomAccessFile, boxes: List<Box>): String? {
        val open = boxes.lastOrNull() ?: return "keine Box"
        if (open.size != 0L) return null
        val actual = raf.length() - open.offset
        if (actual > 0xFFFFFFFFL) return "Box zu gross fuer ein 32-Bit-Feld"
        raf.seek(open.offset)
        raf.writeInt(actual.toInt())
        return null
    }

    /**
     * Liest die Box-Tabelle auf Dateiebene.
     *
     * @return die Boxen, oder `null`, wenn die Tabelle nicht stimmt.
     */
    internal fun walk(raf: RandomAccessFile): List<Box>? {
        val length = raf.length()
        val boxes = ArrayList<Box>(8)
        var offset = 0L
        while (offset < length) {
            if (length - offset < 8) return null
            raf.seek(offset)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = readType(raf)
            if (size == 1L) {
                if (length - offset < 16) return null
                size = raf.readLong()
            } else if (size == 0L) {
                // "Bis Dateiende": gueltig, aber es gibt danach nichts mehr.
                // Die 0 bleibt in der Box stehen - sie ist der einzige
                // Hinweis darauf, dass das Groessenfeld noch geschrieben
                // werden muss ([closeOpenEnded]); wer sie hier schon
                // ersetzen wuerde, koennte den Unterschied hinterher nicht
                // mehr sehen.
                boxes.add(Box(offset, 0L, type))
                return boxes
            }
            if (size < 8 || offset + size > length) return null
            boxes.add(Box(offset, size, type))
            offset += size
        }
        return boxes
    }

    /** Die Box-Tabelle als Text - fuer Protokoll und Pruefbericht. */
    fun describe(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val boxes = walk(raf) ?: return@use "Box-Tabelle unlesbar"
            boxes.joinToString(" ") {
                val size = if (it.size == 0L) raf.length() - it.offset else it.size
                "${it.type}@${it.offset}+$size${if (it.size == 0L) "(offen)" else ""}"
            }
        }
    }.getOrDefault("nicht lesbar")

    private fun readType(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
