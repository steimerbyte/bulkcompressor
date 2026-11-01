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
 */
object TagFile {

    /** Ablage je Container: EXIF bei Bildern, eigene Box bei Video. */
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
     * @return null, wenn der Marker danach nachweisbar drinsteht, sonst
     *         den Grund.
     */
    fun write(file: File, tag: CompressionTag): String? = when (familyOf(file)) {
        Family.MP4 -> writeBox(file, tag)
        Family.EXIF -> writeExif(file, tag)
        null -> "Container nicht erkannt"
    }

    private fun writeBox(file: File, tag: CompressionTag): String? {
        val failed = Mp4Tag.appendTag(file, tag.format())
        if (failed != null) return failed
        return if (CompressionTag.find(Mp4Tag.findTag(file))?.runs == tag.runs) {
            null
        } else {
            "Box geschrieben, aber nicht wiederlesbar"
        }
    }

    private fun exifComment(file: File): String? = runCatching {
        ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT)
    }.getOrNull()

    private fun writeExif(file: File, tag: CompressionTag): String? {
        val exif = runCatching { ExifInterface(file.absolutePath) }
            .getOrElse { return "EXIF nicht lesbar: ${it.message ?: it.javaClass.simpleName}" }
        val before = runCatching { exif.getAttribute(ExifInterface.TAG_USER_COMMENT) }.getOrNull()
        val merged = CompressionTag.merge(before, tag)
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
            CompressionTag.find(back)?.runs != tag.runs -> "UserComment danach nicht der erwartete Marker"
            else -> null
        }
    }

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
