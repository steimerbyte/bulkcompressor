package com.steimerbyte.bulkcompressor.engine.verify

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.DescriptorIo
import com.steimerbyte.bulkcompressor.engine.readAtMost
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.Mp4Tag
import com.steimerbyte.bulkcompressor.engine.tag.TagFile
import com.steimerbyte.bulkcompressor.engine.tag.TagStore
import java.io.File
import java.io.RandomAccessFile

/**
 * Eine Einzelpruefung am Ergebnis einer Datei.
 *
 * [State.SKIPPED] gibt es, weil manche Pruefungen auf einem echten Geraet
 * etwas koennen, was im Test nicht nachstellbar ist (Codec-Spuren). Ein
 * "nicht pruefbar" darf nie als "in Ordnung" durchgehen - es steht als
 * eigener Zustand im Bericht und im Protokoll, damit niemand eine
 * ungepruefte Datei fuer eine gepruefte haelt.
 */
data class Check(val name: String, val state: State, val detail: String = "") {
    enum class State { OK, FAILED, SKIPPED }
}

/** Das Ergebnis einer Pruefung: alle Einzelbefunde, nicht nur ein Ja/Nein. */
data class VerifyReport(val checks: List<Check>) {

    val failures: List<Check> get() = checks.filter { it.state == Check.State.FAILED }

    val passed: Boolean get() = failures.isEmpty()

    /** Grund fuer das Protokoll, `null` wenn alles in Ordnung ist. */
    fun reason(): String? = failures
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { "${it.name}: ${it.detail}" }

    /** Einzeiler fuer Log und Oberflaeche. */
    fun summary(): String = checks.joinToString(" · ") { check ->
        when (check.state) {
            Check.State.OK -> check.detail.ifBlank { "${check.name} OK" }
            Check.State.FAILED -> "FEHLER ${check.name}: ${check.detail}"
            Check.State.SKIPPED -> "${check.name} nicht pruefbar" +
                if (check.detail.isBlank()) "" else " (${check.detail})"
        }
    }
}

/**
 * Prueft nach dem Komprimieren, ob das Ergebnis noch eine **gueltige
 * Datei** ist - Container, Format und Marker.
 *
 * Der Anlass ist nicht Misstrauen gegen den Encoder, sondern gegen die
 * Kette danach: `MediaMuxer` kann einen Container hinterlassen, den nur
 * der eigene Player oeffnet, `Bitmap.compress` kann eine Datei mit
 * richtiger Kopfzeile und abgeschnittenem Rest schreiben, und der
 * SAF-Provider kann beim Kopieren kuerzen. Keine dieser drei Stellen
 * meldet sich hinterher freiwillig.
 *
 * Geprueft wird auf zwei Ebenen:
 *
 * - **[verifyFile]**: die erzeugte Cache-Datei, bevor sie an ihren Platz
 *   wandert. Hier ist eine Beanstandung billig - die Zieldatei ist noch
 *   unberuehrt, und die Datei wird gar nicht erst geschrieben.
 * - **[verifyDocument]**: die Datei an ihrem Platz, nachdem sie
 *   geschrieben wurde. Hier wird nur gelesen, was wenig kostet: Kopf
 *   (Format), Groesse, Marker, bei Videos die Spur.
 */
object Verifier {

    /** Mehr als der Kopf wird fuer die Formaterkennung nicht gelesen. */
    private const val HEAD_BYTES = 16

    // ------------------------------------------------------------------
    // Vor dem Schreiben: die Temp-Datei
    // ------------------------------------------------------------------

    /**
     * Vollpruefung der erzeugten Datei.
     *
     * @param expectMime der MIME, den der Encoder zugesagt hat.
     * @param expectTag  ob der Marker drinstehen muss.
     */
    fun verifyFile(file: File, expectMime: String, expectTag: Boolean): VerifyReport {
        val checks = ArrayList<Check>(5)
        val length = runCatching { file.length() }.getOrDefault(-1L)
        checks += if (length > 0) {
            Check("Groesse", Check.State.OK, "$length B")
        } else {
            Check("Groesse", Check.State.FAILED, "leer oder nicht lesbar")
        }
        if (length <= 0) return VerifyReport(checks)

        val head = runCatching { headFile(file) }.getOrNull()
        val family = expectedFamily(expectMime)
        checks += containerCheck(expectMime, head)

        if (family == Family.MP4) {
            val boxes = runCatching { RandomAccessFile(file, "r").use { Mp4Tag.walk(it) } }.getOrNull()
            checks += if (boxes == null) {
                Check("Box-Tabelle", Check.State.FAILED, Mp4Tag.describe(file))
            } else {
                Check("Box-Tabelle", Check.State.OK, "${boxes.size} Boxen · ${Mp4Tag.describe(file)}")
            }
        }

        if (family == Family.IMAGE) {
            checks += imageCheck { options -> BitmapFactory.decodeFile(file.absolutePath, options) }
        }

        checks += markerCheck(expectTag, TagFile.read(file))
        return VerifyReport(checks)
    }

    // ------------------------------------------------------------------
    // Nach dem Schreiben: die Datei an ihrem Platz
    // ------------------------------------------------------------------

    /**
     * Pruefung der abgelegten Datei. Der Aufrufer entscheidet ueber die
     * Video-Spur-Pruefung: sie braucht einen echten Extraktor, den es im
     * Test nicht gibt.
     *
     * @param probeTracks liefert `null`, wenn die Spur in Ordnung ist,
     *                    sonst den Grund. Im Test steht hier `{ null }`,
     *                    nie der echte Extraktor.
     */
    fun verifyDocument(
        resolver: ContentResolver,
        uri: Uri,
        expectMime: String,
        expectedBytes: Long,
        expectTag: Boolean,
        probeTracks: (Uri) -> String?
    ): VerifyReport {
        val checks = ArrayList<Check>(4)
        val family = expectedFamily(expectMime)

        val size = runCatching { documentSize(resolver, uri) }.getOrNull()
        checks += when {
            // 0 ist keine Groesse, sondern eine fehlende Angabe: manche
            // Provider liefern die Spalte erst verzoegert, und daraus einen
            // Fehlbefund zu machen hiesse, eine gesunde Ausgabe zu verwerfen.
            size == null || size <= 0 -> Check("Groesse", Check.State.SKIPPED, "Provider nennt sie nicht")
            expectedBytes > 0 && size != expectedBytes ->
                Check("Groesse", Check.State.FAILED, "$size B statt $expectedBytes B")
            else -> Check("Groesse", Check.State.OK, "$size B")
        }

        val head = runCatching {
            resolver.openInputStream(uri)?.use { it.readAtMost(HEAD_BYTES) }
        }.getOrNull()
        checks += containerCheck(expectMime, head)

        when (family) {
            Family.MP4 -> {
                val problem = runCatching { probeTracks(uri) }.getOrElse {
                    "Pruefung warf ${it.message ?: it.javaClass.simpleName}"
                }
                checks += if (problem == null) {
                    Check("Spur", Check.State.OK, "Video-Spur lesbar")
                } else {
                    Check("Spur", Check.State.FAILED, problem)
                }
            }
            Family.IMAGE -> {
                val options = runCatching {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, it)
                        }
                    }
                }.getOrNull()
                checks += imageFromOptions(options)
            }
            Family.UNKNOWN -> Unit
        }

        checks += markerCheck(expectTag, TagStore(resolver).read(uri))
        return VerifyReport(checks)
    }

    /**
     * Die Spur-Pruefung fuer den echten Betrieb: der Extraktor muss den
     * Container oeffnen und eine Video-Spur finden. Findet er nichts, ist
     * der Container kaputt - unabhaengig davon, was der Muxer behauptet.
     */
    fun trackProblem(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                var videos = 0
                for (i in 0 until extractor.trackCount) {
                    val type = runCatching {
                        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    }.getOrNull().orEmpty()
                    if (type.startsWith("video/")) videos++
                }
                if (videos == 0) "keine Video-Spur im Container" else null
            } finally {
                runCatching { extractor.release() }
            }
        } ?: "Dokument nicht lesbar"
    }.getOrElse { "nicht pruefbar: ${it.message ?: it.javaClass.simpleName}" }

    // ------------------------------------------------------------------
    // Bausteine
    // ------------------------------------------------------------------

    internal enum class Family { MP4, IMAGE, UNKNOWN }

    /**
     * Das Format, wie es **in den Bytes steht** - nicht, wie es heisst.
     *
     * Der MIME, den der Encoder zurueckgibt, ist eine Zusage ueber die
     * Absicht. Fuer die Frage "ist die Datei das, was sie sein soll" zaehlt
     * die Kopfzeile; `BitmapFactory.Options.outMimeType` waere dafuer die
     * zweite Quelle und im Test nicht nachstellbar.
     */
    internal enum class Format(val mime: String, val family: Family) {
        JPEG("image/jpeg", Family.IMAGE),
        PNG("image/png", Family.IMAGE),
        WEBP("image/webp", Family.IMAGE),
        MP4("video/mp4", Family.MP4),
        UNBEKANNT("", Family.UNKNOWN);

        companion object {
            /** Welches Format dieser MIME verlangt; [UNBEKANNT] wenn keins. */
            fun forMime(mime: String): Format =
                entries.firstOrNull { it.mime.equals(mime, ignoreCase = true) } ?: UNBEKANNT
        }
    }

    internal fun expectedFamily(mime: String): Family = Format.forMime(mime).family

    /**
     * Vergleicht die Kopfzeile mit dem zugesagten Format. Eine MP4-Box in
     * einer Datei, die als `image/jpeg` gelten soll, ist genau der Fehler,
     * den diese Pruefung finden soll - ein Blick auf die Dateigroesse
     * findet ihn nicht.
     */
    private fun containerCheck(expectMime: String, head: ByteArray?): Check {
        val expected = Format.forMime(expectMime)
        if (expected == Format.UNBEKANNT) {
            return Check("Container", Check.State.SKIPPED, "Format ohne Praegung")
        }
        val detected = sniff(head)
        if (detected == Format.UNBEKANNT) {
            return Check("Container", Check.State.FAILED, "kein bekannter Dateikopf")
        }
        if (detected != expected) {
            return Check("Container", Check.State.FAILED, "${detected.mime} statt $expectMime")
        }
        val brand = if (detected == Format.MP4 && head != null && head.size >= 12) {
            val text = String(head, 8, 4, Charsets.US_ASCII).filter { it.isLetterOrDigit() }
            if (text.isBlank()) "" else " · Brand $text"
        } else {
            ""
        }
        return Check("Container", Check.State.OK, "${detected.mime}$brand")
    }

    private fun markerCheck(expectTag: Boolean, tag: CompressionTag?): Check = when {
        !expectTag -> Check("Marker", Check.State.SKIPPED, "nicht angefordert")
        tag == null -> Check("Marker", Check.State.FAILED, "fehlt nach dem Schreiben")
        else -> Check("Marker", Check.State.OK, tag.describe())
    }

    /**
     * Prueft, ob sich das Bild dekodieren laesst und welche Abmessungen es
     * hat. Das Format prueft [containerCheck] an den Bytes - hier zaehlt,
     * dass die Bilddaten nicht abgeschnitten sind.
     */
    private fun imageCheck(decode: (BitmapFactory.Options) -> Unit): Check {
        val options = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also(decode)
        }.getOrNull()
        return imageFromOptions(options)
    }

    private fun imageFromOptions(options: BitmapFactory.Options?): Check {
        if (options == null) return Check("Bild", Check.State.FAILED, "Kopfzeile nicht lesbar")
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return Check(
                "Bild",
                Check.State.FAILED,
                "Abmessungen fehlen (${options.outWidth}x${options.outHeight})"
            )
        }
        return Check("Bild", Check.State.OK, "${options.outWidth}x${options.outHeight}")
    }

    private fun headFile(file: File): ByteArray = file.inputStream().use { it.readAtMost(HEAD_BYTES) }

    /** Formaterkennung an den ersten Bytes - reine Bytes, kein Android. */
    internal fun sniff(bytes: ByteArray?): Format {
        if (bytes == null) return Format.UNBEKANNT
        fun at(offset: Int, text: String): Boolean {
            val needle = text.toByteArray(Charsets.US_ASCII)
            if (offset + needle.size > bytes.size) return false
            for (i in needle.indices) if (bytes[offset + i] != needle[i]) return false
            return true
        }
        return when {
            at(4, "ftyp") -> Format.MP4
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> Format.JPEG
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && at(1, "PNG") -> Format.PNG
            bytes.size >= 12 && at(0, "RIFF") && at(8, "WEBP") -> Format.WEBP
            else -> Format.UNBEKANNT
        }
    }

    private fun documentSize(resolver: ContentResolver, uri: Uri): Long? {
        val fromColumns = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull()
        if (fromColumns != null && fromColumns > 0) return fromColumns
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd -> DescriptorIo.size(pfd.fileDescriptor) }
        }.getOrNull()
    }
}
