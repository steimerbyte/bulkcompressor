package com.steimerbyte.bulkcompressor.engine.tag

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.steimerbyte.bulkcompressor.engine.DescriptorIo
import com.steimerbyte.bulkcompressor.engine.readAtMost
import com.steimerbyte.bulkcompressor.log.Log

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
 */
class TagStore(private val resolver: ContentResolver) {

    /** Steht ein Marker in diesem Dokument? */
    fun read(uri: Uri): CompressionTag? = runCatching {
        val head = head(uri, HEAD_BYTES) ?: return@runCatching null
        val raw = when (TagFile.sniff(head)) {
            TagFile.Family.MP4 ->
                // HEIC/HEIF/AVIF sind ebenfalls `ftyp`-Dateien, bekommen
                // aber nie eine `uuid`-Box: sie werden als JPEG geschrieben
                // und nie in-place ersetzt. Ohne diese Unterscheidung kostet
                // jedes Kamerafoto einen Deskriptor, einen Sprung ans
                // Dateiende und 8 KB - fuer ein garantiertes Nichts.
                if (isStillImageBrand(head)) null
                else Mp4Tag.findTagInTail(tail(uri, TAIL_BYTES) ?: return@runCatching null)
            TagFile.Family.EXIF -> resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                runCatching {
                    ExifInterface(pfd.fileDescriptor).getAttribute(ExifInterface.TAG_USER_COMMENT)
                }.getOrNull()
            }
            null -> null
        }
        CompressionTag.find(raw)
    }.getOrElse {
        // Nicht lesbar heisst nicht "nicht komprimiert" - aber "unbekannt"
        // ist hier die einzige ehrliche Antwort, und ein fehlgeschlagener
        // Leselauf darf keine Datei ueberspringen.
        Log.d("Tag", "Marker nicht lesbar: $uri · ${it.message ?: it.javaClass.simpleName}")
        null
    }

    /** Ist die Datei hinter [uri] bereits markiert? */
    fun isMarked(uri: Uri): Boolean = read(uri) != null

    private fun head(uri: Uri, count: Int): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input -> input.readAtMost(count) }
    }.getOrNull()

    /**
     * Die letzten [count] Bytes eines Dokuments. Gebraucht wird das fuer
     * die Box am Dateiende; ueber den Kanal geht es ohne die ganze Datei
     * zu lesen.
     */
    private fun tail(uri: Uri, count: Int): ByteArray? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd -> DescriptorIo.tail(pfd.fileDescriptor, count) }
    }.getOrNull()

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

    companion object {
        private const val HEAD_BYTES = 12

        /** Genug fuer die letzte Box; der Marker ist eine Zeile. */
        private const val TAIL_BYTES = 8192

        /** `ftyp`-Kennzeichen von Standbildern, die wie MP4 beginnen. */
        private val STILL_IMAGE_BRANDS = setOf(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "avif", "avis"
        )
    }
}
