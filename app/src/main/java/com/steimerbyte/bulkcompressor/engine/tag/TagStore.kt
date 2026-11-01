package com.steimerbyte.bulkcompressor.engine.tag

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.steimerbyte.bulkcompressor.engine.DescriptorIo
import com.steimerbyte.bulkcompressor.engine.readAtMost
import com.steimerbyte.bulkcompressor.log.Log
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Liest den Marker aus einem SAF-Dokument (Galerie).
 *
 * Der Weg je Container ist unterschiedlich, weil die Formate es sind:
 *
 * | Container | Ablage des Markers |
 * |---|---|
 * | JPEG, PNG, WebP | EXIF `UserComment`, ueber `ExifInterface` |
 * | MP4, M4V, MOV | eigene `uuid`-Box am Dateiende |
 *
 * `UserComment` ist bewusst gewaehlt und nicht `ImageDescription`: viele
 * Kameras und Werkzeuge belegen `ImageDescription` mit eigenem Text, den
 * diese App nicht ueberschreiben darf. `UserComment` ist fuer Freitext
 * gedacht - und vorhandener Text bleibt erhalten, weil
 * [CompressionTag.merge] den Marker davor setzt.
 *
 * Das Schreiben passiert in [TagFile] auf der Cache-Datei, bevor sie an
 * ihren Platz wandert. Hier wird nur noch gelesen.
 *
 * ## Strom zuerst, Deskriptor nur als Abkuerzung
 *
 * Der Grund fuer diese Reihenfolge ist ein gemeldeter Fehler: nach einem
 * Lauf meldete der naechste Scan "nichts komprimiert". Die Ursache lag
 * nicht im Marker, sondern im Leser - er sprang ueber den Deskriptor
 * (`Os.pread`) ans Dateiende, und Anbieter, die keine seekbare Datei
 * liefern (Cloud, Downloads, manche Geraeteanbieter), geben dafuer eine
 * Pipe. `pread` scheitert dort, `runCatching` machte daraus `null`, und
 * `null` hiess fuer den Aufrufer "nicht komprimiert".
 *
 * Deshalb ist der **Strom** der Weg, der entscheidet: `openInputStream`
 * funktioniert bei jedem Anbieter. Der Deskriptor wird zuerst versucht,
 * weil er bei einer echten Datei billiger ist (kein Durchlauf bis zum
 * Ende), aber scheitert er, uebernimmt der Strom.
 *
 * Jeder Fehlschlag erzeugt [MarkerState.UNREADABLE] mit Grund. Ein
 * unbekannter Zustand darf nie als "nicht komprimiert" durchgehen - dann
 * wuerde eine bereits verkleinerte Datei ein zweites Mal verkleinert, oder
 * der Nutzer saehe sie stillschweigend gar nicht mehr.
 */
class TagStore(private val resolver: ContentResolver) : MarkerProbe {

    /** Schon protokollierte Gruende - jeder erscheint einmal. */
    private val seenReasons = HashSet<String>()


    /**
     * Ordnet ein Dokument ein.
     *
     * Wirft nicht: jeder Fehlschlag ist ein Zustand mit Grund, damit Scan
     * und Job ihn sichtbar machen koennen, statt ihn zu verschlucken.
     */
    override fun probe(uri: Uri): MarkerReading {
        val head = try {
            readHead(uri, HEAD_BYTES)
        } catch (t: Throwable) {
            return unreadable(uri, "Kopf nicht lesbar: ${oneLine(t)}")
        }

        // Ein Kopf, der kuerzer ist als die Formaterkennung braucht, sagt
        // nichts ueber das Format - er sagt, dass der Strom nicht das
        // geliefert hat, was er sollte. Wuerde das als "Format nicht
        // erkannt" durchgehen, waere ein abgebrochener Strom wieder ein
        // stiller Nichtbefund.
        if (head.size < HEAD_BYTES) {
            return unreadable(uri, "Kopf zu kurz: ${head.size} von $HEAD_BYTES Bytes")
        }

        return when (TagFile.sniff(head)) {
            TagFile.Family.MP4 -> probeMp4(uri, head)
            TagFile.Family.EXIF -> probeExif(uri)
            null -> {
                // Kein bekanntes Containerformat: es gibt keinen Ort, an
                // dem ein Marker dieser App stehen koennte. Das ist kein
                // Lesefehler, sondern ein Nichtbefund.
                Log.d("Tag", "Format nicht erkannt, kein Marker moeglich: $uri")
                MarkerReading(MarkerState.CLEAR)
            }
        }
    }

    /** Steht ein Marker in diesem Dokument? UNREADABLE zaehlt nicht als markiert. */
    fun read(uri: Uri): CompressionTag? = probe(uri).tag

    /** Ist die Datei hinter [uri] bereits markiert? */
    fun isMarked(uri: Uri): Boolean = probe(uri).state == MarkerState.MARKED

    private fun probeMp4(uri: Uri, head: ByteArray): MarkerReading {
        // HEIC/HEIF/AVIF sind ebenfalls `ftyp`-Dateien, bekommen aber nie
        // eine `uuid`-Box: sie werden als JPEG geschrieben und nie
        // in-place ersetzt. Ohne diese Unterscheidung kostet jedes
        // Kamerafoto einen Deskriptor und einen Sprung ans Dateiende.
        if (isStillImageBrand(head)) return MarkerReading(MarkerState.CLEAR)

        val tail = try {
            readTail(uri, TAIL_BYTES)
        } catch (t: Throwable) {
            return unreadable(uri, "Dateiende nicht lesbar: ${oneLine(t)}")
        } ?: return unreadable(uri, "Dateiende nicht lesbar: weder Deskriptor noch Strom lieferten Daten")

        val tag = CompressionTag.find(Mp4Tag.findTagInTail(tail))
        return if (tag != null) MarkerReading(MarkerState.MARKED, tag) else MarkerReading(MarkerState.CLEAR)
    }

    private fun probeExif(uri: Uri): MarkerReading {
        val raw = try {
            openStream(uri).use { input ->
                ExifInterface(input).getAttribute(ExifInterface.TAG_USER_COMMENT)
            }
        } catch (t: Throwable) {
            return unreadable(uri, "EXIF nicht lesbar: ${oneLine(t)}")
        }
        val tag = CompressionTag.find(raw)
        return if (tag != null) MarkerReading(MarkerState.MARKED, tag) else MarkerReading(MarkerState.CLEAR)
    }

    /** Kurzer Kopf fuer die Formaterkennung; nur so viel wie noetig. */
    private fun readHead(uri: Uri, count: Int): ByteArray =
        openStream(uri).use { input -> input.readAtMost(count) }

    /**
     * Die letzten [count] Bytes. Zuerst ueber den Deskriptor (billig, wenn
     * der Anbieter eine Datei liefert), danach ueber den Strom.
     */
    private fun readTail(uri: Uri, count: Int): ByteArray? =
        tailViaDescriptor(uri, count) ?: tailViaStream(uri, count)

    private fun tailViaDescriptor(uri: Uri, count: Int): ByteArray? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd -> DescriptorIo.tail(pfd.fileDescriptor, count) }
    }.onFailure {
        Log.d("Tag", "Deskriptor-Weg nicht nutzbar, Strom uebernimmt: $uri · ${oneLine(it)}")
    }.getOrNull()

    /**
     * Das Dateiende ueber den Strom. Dafuer muss die Groesse bekannt sein:
     * eine Pipe liefert kein `SEEK_END`, und die ganze Datei zu lesen, nur
     * um acht Kilobyte zu bekommen, waere bei einem langen Video absurd.
     */
    private fun tailViaStream(uri: Uri, count: Int): ByteArray? {
        val size = sizeOf(uri) ?: return null
        if (size <= 0L) return null
        val wanted = minOf(count.toLong(), size).toInt()
        val skip = size - wanted
        openStream(uri).use { input ->
            skipFully(input, skip)
            val tail = ByteArray(wanted)
            var read = 0
            while (read < wanted) {
                val step = input.read(tail, read, wanted - read)
                if (step <= 0) break
                read += step
            }
            // Ein kurzer Rest darf nicht als "das Dateiende" gelesen
            // werden: dann fehlt genau der Teil, in dem der Marker steht,
            // und der Befund waere faelschlich "nicht komprimiert".
            return if (read == wanted) tail else null
        }
    }

    /**
     * Bis zu [count] Bytes ueberspringen. `skip` darf bei einem Strom, der
     * keine Datei ist, weniger ueberspringen als verlangt - deshalb wird
     * nachgefasst, statt dem Rueckgabewert zu glauben.
     */
    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(SKIP_BUFFER)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val read = input.read(scratch)
            if (read <= 0) return
            left -= read
        }
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
    }.getOrNull()

    /**
     * Der Lesestrom zu einem Dokument - **werfend**.
     *
     * Vorher stand hier `runCatching { … }.getOrNull()`, und der Grund ging
     * verloren: ein `SecurityException` (abgelaufene Ordner-Freigabe) sah
     * genauso aus wie ein `FileNotFoundException` (Datei weg) und wie ein
     * Anbieter, der wirklich nichts liefert. Im Protokoll des Geraets
     * stand dann fuer 500 Dateien dieselbe Vermutung ("Anbieter lieferte
     * keinen Lesestrom") und keine Ursache. Der Grund muss bis in die
     * Statuszeile durchkommen - er ist die einzige Information, mit der
     * der Nutzer etwas anfangen kann.
     */
    private fun openStream(uri: Uri): InputStream =
        resolver.openInputStream(uri)
            ?: throw FileNotFoundException("Anbieter lieferte keinen Lesestrom")

    /**
     * Traegt der `ftyp`-Kopf ein Standbild-Kennzeichen? Dann ist die Datei
     * ein Bild (HEIC/HEIF/AVIF) und kein Video - ein Marker in einer
     * `uuid`-Box kommt dort nie vor.
     */
    private fun isStillImageBrand(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val brand = String(head, 8, 4, Charsets.US_ASCII).lowercase()
        return brand in STILL_IMAGE_BRANDS
    }

    private fun unreadable(uri: Uri, reason: String): MarkerReading {
        val line = oneLine(reason)
        // Einmal je Grund, nicht einmal je Datei. Bei 500 Fotos mit
        // derselben Ursache war das Protokoll 500 Zeilen lang und die
        // Ursache darin nicht zu finden; der Scan schreibt ohnehin eine
        // Zusammenfassung. Neue Gruende erscheinen weiterhin sofort.
        val isNew = synchronized(seenReasons) { seenReasons.add(line) }
        if (isNew) {
            Log.w("Tag", "Marker nicht lesbar · $uri · $line")
        }
        return MarkerReading(MarkerState.UNREADABLE, reason = line)
    }

    /**
     * Ein einzeiliger Grund. Die Zeile landet im Protokoll und in der
     * Statusanzeige des Scans; ein Umbruch aus einer Fremdmeldung wuerde
     * das Layout sprengen.
     */
    /**
     * Eine Zeile fuer ein geworfenes Problem - **mit Klasse**. Nur die
     * Meldung zu nehmen reicht nicht: `SecurityException` und
     * `FileNotFoundException` sind fuer den Nutzer verschiedene Lagen
     * (Freigabe erneuern / Datei ist weg), und manche Anbieter werfen
     * ohne Meldung, dann blieb bisher nur der Klassenname uebrig.
     */
    private fun oneLine(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() }
        val text = if (message == null) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
        return oneLine(text)
    }

    private fun oneLine(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_REASON_CHARS) flat else flat.take(MAX_REASON_CHARS - 1) + "…"
    }

    companion object {
        private const val HEAD_BYTES = 12

        /** Genug fuer die letzte Box; der Marker ist eine Zeile. */
        private const val TAIL_BYTES = 8192

        /** Blockgroesse fuer das Ueberspringen im Strom. */
        private const val SKIP_BUFFER = 64 * 1024

        /** Laengster Grund, der noch in eine Statuszeile passt. */
        private const val MAX_REASON_CHARS = 120

        /** `ftyp`-Kennzeichen von Standbildern, die wie MP4 beginnen. */
        private val STILL_IMAGE_BRANDS = setOf(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "avif", "avis"
        )
    }
}
