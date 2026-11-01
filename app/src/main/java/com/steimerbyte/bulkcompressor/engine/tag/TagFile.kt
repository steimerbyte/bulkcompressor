package com.steimerbyte.bulkcompressor.engine.tag

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

/**
 * Der Marker in einer Datei, die als Pfad vorliegt (Cache, Test).
 *
 * Getrennt von [TagStore], weil das Lesen und Schreiben einer Datei
 * keinen `ContentResolver` braucht - und ein `ContentResolver`, der nur
 * fuer die Container-Erkennung mitgeschleppt wird, ist eine Abhaengigkeit
 * ohne Aufgabe.
 *
 * Nach jedem Schreiben wird zurueckgelesen. Ein Marker, dessen Speichern
 * "geklappt" hat, ohne danach da zu sein, waere schlimmer als keiner: der
 * naechste Lauf wuerde die Datei erneut komprimieren.
 *
 * **Ablage je Container - geprueft, nicht angenommen** (androidx
 * `ExifInterface` 1.3.7, das die App benutzt):
 *
 * | Container | Ablage | Verhalten |
 * |---|---|---|
 * | JPEG | EXIF `UserComment` (APP1) | vorhandene APP1 wird ersetzt, alle anderen Segmente bleiben byte-gleich |
 * | PNG | `eXIf`-Chunk hinter `IHDR` | Chunk wird eingefuegt oder ersetzt, `IDAT` bleibt byte-gleich, CRCs neu berechnet |
 * | WebP | `EXIF`-Chunk hinter `VP8X` | `VP8X` wird angelegt, falls er fehlt, `VP8 `-Block bleibt byte-gleich, RIFF-Groesse wird fortgeschrieben |
 * | MP4/M4V/MOV | eigene `uuid`-Box am Dateiende | Box wird angehaengt oder ersetzt, Container-Kopf bleibt unberuehrt |
 *
 * Gemessen mit `TagFileTest`, nicht angenommen: der WebP-FourCC heisst
 * `"VP8 "` mit Leerzeichen, und PNG wie WebP lehnen ein Umschreiben ab,
 * dessen `UserComment` ausserhalb ASCII liegt - derselbe Grund wie bei
 * JPEG, weil alle drei ueber `ExifInterface` laufen.
 *
 * Kann fuer einen Container kein Marker geschrieben werden, ist der
 * Rueckgabewert von [write] der Grund - nie ein stilles Gelingen. Das ist
 * die einzige Stelle, an der ein Lauf noch abbrechen kann, ohne die Quelle
 * anzufassen, deshalb wird hier nicht geraten.
 */
object TagFile {

    /**
     * Ablage je Container: EXIF bei Standbildern, eigene Box bei Video.
     *
     * [EXIF] heisst "Marker steht in EXIF-Daten", nicht "die Datei ist ein
     * JPEG" - PNG und WebP tragen ihre EXIF-Daten in einem eigenen Chunk.
     */
    enum class Family { MP4, EXIF }

    fun familyOf(file: File): Family? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(12)
            val read = raf.read(bytes)
            if (read < 4) null else sniff(bytes.copyOf(read))
        }
    }.getOrNull()

    /** Steht ein Marker in dieser Datei? */
    fun read(file: File): CompressionTag? = when (familyOf(file)) {
        Family.MP4 -> CompressionTag.find(Mp4Tag.findTag(file))
        Family.EXIF -> CompressionTag.find(exifComment(file))
        null -> null
    }

    /**
     * Schreibt den Marker und prueft ihn anschliessend.
     *
     * Ein zweiter Lauf **ersetzt** den Marker, statt einen zweiten daneben
     * zu legen: sonst stuenden in derselben Datei zwei Marker mit
     * verschiedenen Zaehlern, und je nachdem, ob der Leser von vorne oder
     * von hinten sucht, kaeme ein anderer heraus.
     *
     * Ein Schreibversuch, der **abgelehnt** wird (Kommentar waere nicht
     * verlustfrei umzuschreiben), laesst die Datei unberuehrt.
     *
     * Ein Schreibversuch, der **scheitert**, laesst sie nicht zwingend
     * unberuehrt: `ExifInterface` 1.3.7 traegt bei einem unlesbaren JPEG
     * das APP1-Segment erst ein und wirft dann
     * `IOException("Failed to save new file")` - gemessen an einem
     * abgeschnittenen JPEG, das von 11 auf 135 Byte wuchs. Der Vertrag ist
     * deshalb nicht "unveraendert", sondern: der Aufrufer **verwirft**
     * seine Datei. Bei diesem Aufrufer ist das die Temp-Datei des Laufs,
     * das Original sieht diesen Pfad nie.
     *
     * @return null, wenn der Marker danach nachweisbar drinsteht, sonst
     *         den Grund. Bei einem Grund ist das Ergebnis zu verwerfen.
     */
    fun write(file: File, tag: CompressionTag): String? = when (familyOf(file)) {
        Family.MP4 -> writeBox(file, tag)
        Family.EXIF -> writeExif(file, tag)
        null -> "Container nicht erkannt"
    }

    private fun writeBox(file: File, tag: CompressionTag): String? {
        val payload = tag.format()
        val failed = Mp4Tag.appendTag(file, payload)
        if (failed != null) return failed
        // Der ganze Marker muss zurueckkommen, nicht nur der Zaehler: sonst
        // ginge eine falsch geschriebene Zeile als Erfolg durch, und der
        // naechste Lauf liest etwas anderes, als dieser geschrieben hat.
        val back = runCatching { Mp4Tag.findTag(file)?.trim() }.getOrNull()
        return if (back == payload) null else "Box geschrieben, aber nicht wiederlesbar"
    }

    private fun exifComment(file: File): String? = runCatching {
        ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT)
    }.getOrNull()

    private fun writeExif(file: File, tag: CompressionTag): String? {
        val exif = runCatching { ExifInterface(file.absolutePath) }
            .getOrElse { return "EXIF nicht lesbar: ${it.message ?: it.javaClass.simpleName}" }
        val before = runCatching { exif.getAttribute(ExifInterface.TAG_USER_COMMENT) }.getOrNull()
        val raw = runCatching { exif.getAttributeBytes(ExifInterface.TAG_USER_COMMENT) }.getOrNull()
        val merged = CompressionTag.merge(before, tag)
        // Vor dem Schreiben pruefen, nicht danach: was hier verloren ginge,
        // waere der Kommentar des Nutzers, und der ist nicht wiederherstellbar.
        commentRewriteProblem(raw, merged)?.let { return it }
        if (runCatching { exif.setAttribute(ExifInterface.TAG_USER_COMMENT, merged) }.isFailure) {
            return "UserComment nicht setzbar"
        }
        val saved = runCatching { exif.saveAttributes() }
        if (saved.isFailure) {
            return "EXIF nicht speicherbar: ${saved.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName } ?: "unbekannt"}"
        }
        val back = exifComment(file)
        return when {
            back == null -> "EXIF nach dem Speichern nicht lesbar"
            // Zeichen fuer Zeichen, nicht nur der Zaehler: ein Kommentar,
            // den die Bibliothek beim Umschreiben normalisiert (etwa ein
            // abgeschnittenes Leerzeichen), ist ein Datenverlust am Text
            // des Nutzers.
            back != merged -> "UserComment nach dem Speichern veraendert"
            CompressionTag.find(back)?.runs != tag.runs -> "UserComment danach nicht der erwartete Marker"
            else -> null
        }
    }

    /**
     * Warum ein Umschreiben den vorhandenen `UserComment` nicht
     * unbeschaedigt lassen wuerde - `null`, wenn es verlustfrei geht.
     *
     * `UserComment` haelt in `ExifInterface` 1.3.7 nur ASCII: Zeichen
     * ausserhalb werden beim Speichern zu `?`. Ein Kommentar mit Umlaut
     * kaeme also als "Gr??e" zurueck - der Marker waere drin, der Text
     * des Nutzers aber weg, und der Verlust faellt niemandem auf, weil
     * die Datei weiterhin lesbar ist. Drei Faelle sind hier belegt:
     *
     * 1. Der neue Text hat ein Zeichen ausserhalb ASCII (etwa weil der
     *    vorhandene Kommentar beim Lesen schon zu `?` geworden ist).
     * 2. Der vorhandene Wert hat Bytes ueber 0x7F - dann stand dort
     *    Latin-1 o. ae., und das Umschreiben macht daraus `?`.
     * 3. Der vorhandene Wert traegt die EXIF-Zeichensatz-Kennung fuer
     *    UTF-16 (`UNICODE\0`). `getAttribute` liefert dann nur die
     *    Kennung selbst, waehrend der eigentliche Text dahinter steht -
     *    wer das Ergebnis speichert, schreibt "UNICODE" zurueck und
     *    loescht den Text, ohne dass jemand es kommen sieht.
     *
     * Alle drei werden abgelehnt, **bevor** geschrieben wird. Der Lauf
     * verwirft dann sein Ergebnis und laesst das Original stehen: eine
     * Datei ohne Marker ist besser als eine mit zerschriebenem Kommentar.
     * Die Kennung fuer ASCII (`ASCII\0\0\0`) und fuer JIS sind harmlos -
     * bei ASCII steht der Text dahinter lesbar, und die Kennung selbst
     * ist kein Inhalt.
     */
    internal fun commentRewriteProblem(raw: ByteArray?, merged: String): String? {
        val wide = merged.firstOrNull { it.code < 0x20 || it.code > 0x7E }
        if (wide != null) {
            return "UserComment enthaelt U+%04X, das ausserhalb ASCII liegt".format(wide.code)
        }
        if (raw == null || raw.isEmpty()) return null
        if (raw.size >= CHARSET_MARKER_BYTES) {
            // Auf die vollen acht Bytes vergleichen, nicht auf den Anfang:
            // ein Fremdkommentar "JIS-Kamera 2024" beginnt mit denselben
            // drei Buchstaben und ist trotzdem gewoehnlicher Text.
            val head = raw.copyOfRange(0, CHARSET_MARKER_BYTES)
            val marker = WIDE_CHARSET_MARKERS.firstOrNull { (_, bytes) -> bytes.contentEquals(head) }
            if (marker != null) {
                return "vorhandener UserComment ist ${marker.first}-kodiert" +
                    " und waere nach dem Umschreiben leer"
            }
        }
        val high = raw.count { (it.toInt() and 0xFF) > 0x7F }
        if (high > 0) {
            return "vorhandener UserComment hat $high Bytes ausserhalb ASCII" +
                " und waere nach dem Umschreiben veraendert"
        }
        return null
    }

    /**
     * Laenge der Zeichensatz-Kennung am Anfang eines EXIF-`UserComment`.
     * Die EXIF-Spezifikation legt dafuer 8 Byte fest.
     */
    private const val CHARSET_MARKER_BYTES = 8

    /**
     * Kennungen, deren Text `getAttribute` nicht zurueckgibt: es liefert
     * nur die Kennung selbst. Ein Umschreiben wuerde den Text dahinter
     * loeschen - deshalb wird es abgelehnt.
     *
     * `ASCII` fehlt hier bewusst: dort steht der Text lesbar dahinter,
     * und das Umschreiben ist verlustfrei. Eigenes Schreiben setzt gar
     * keine Kennung - `ExifInterface` legt den Text direkt ab, und
     * [read] liest ihn von dort.
     */
    private val WIDE_CHARSET_MARKERS = listOf(
        "UNICODE\u0000" to "UNICODE",
        "JIS\u0000\u0000\u0000\u0000\u0000" to "JIS"
    ).map { (raw, name) -> name to raw.toByteArray(Charsets.US_ASCII) }

    /** Formaterkennung an den ersten Bytes. */
    internal fun sniff(bytes: ByteArray): Family? {
        fun at(offset: Int, text: String): Boolean {
            val needle = text.toByteArray(Charsets.US_ASCII)
            if (offset + needle.size > bytes.size) return false
            for (i in needle.indices) if (bytes[offset + i] != needle[i]) return false
            return true
        }
        return when {
            at(4, "ftyp") -> Family.MP4
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> Family.EXIF
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && at(1, "PNG") -> Family.EXIF
            bytes.size >= 12 && at(0, "RIFF") && at(8, "WEBP") -> Family.EXIF
            else -> null
        }
    }
}
