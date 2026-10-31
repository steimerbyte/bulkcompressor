package com.steimerbyte.bulkcompressor.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Stellt das wieder her, was nach dem Neuschreiben sonst verloren geht:
 * den Dateizeitstempel und die EXIF-Daten.
 *
 * Der Zeitstempel ist der sensible Teil - er entscheidet in der Galerie
 * darueber, wo eine Datei in der Chronologie steht.
 *
 * Android bietet dafuer **keine** oeffentliche API auf einem
 * SAF-Dateideskriptor: `android.system.Os` hat weder futimens noch
 * utimensat, und die COLUMN_LAST_MODIFIED eines Dokuments ist
 * schreibgeschuetzt. Setzen laesst er sich nur ueber den Dateipfad, und
 * den darf die App ausschliesslich mit "Zugriff auf alle Dateien" lesen.
 * Ob der Aufruf gewirkt hat, wird deshalb nachgemessen statt angenommen -
 * der Rueckgabewert ist ein ehrliches Ja/Nein und landet im Protokoll.
 */
object MetaRestore {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Felder, die die App uebernimmt. Eine vollstaendige Kopie ist nicht
     * moeglich: die androidx-Bibliothek hat keine API, die vorhandenen
     * Felder aufzuzaehlen. Diese Liste deckt ab, was die Galerie zum
     * Sortieren und Anzeigen braucht - Aufnahmezeit, Kamera, Aufnahme-
     * einstellungen, Ort.
     */
    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    /**
     * Setzt den Aenderungszeitstempel der Zieldatei auf [millis].
     *
     * @return true nur, wenn der Wert danach tatsaechlich an der Datei
     *         steht (Toleranz 2 s, weil manche Dateisysteme runden).
     */
    fun applyTimestamp(resolver: ContentResolver, uri: Uri, millis: Long): Boolean {
        if (millis <= 0L) return false
        val path = filesystemPath(uri) ?: return false
        val file = File(path)
        if (!runCatching { file.setLastModified(millis) }.getOrDefault(false)) return false
        val actual = runCatching { file.lastModified() }.getOrDefault(0L)
        return actual > 0L && kotlin.math.abs(actual - millis) <= 2_000L
    }

    /**
     * Loest eine SAF-URI auf den Dateipfad auf, sofern er sich aus der
     * Dokument-ID ergibt. Mehr geht nicht: andere Provider (Downloads,
     * Cloud) haben keinen Pfad, und fuer Fremdvolumes aehnlich.
     */
    fun filesystemPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        val volume = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "")
        if (relative.isBlank()) return null
        val root = when (volume) {
            "primary" -> "/storage/emulated/0"
            "", "home" -> return null
            else -> "/storage/$volume"
        }
        return "$root/$relative"
    }

    /**
     * Kopiert die EXIF-Felder der Quelle in die Zieldatei. Ohne das
     * verliert die Galerie Aufnahmedatum und Ort, und das Bild rutscht
     * an die falsche Stelle der Zeitleiste.
     */
    fun copyExif(resolver: ContentResolver, source: Uri, target: File, copy: Boolean): Boolean {
        if (!copy || !target.exists()) return false
        val targetExif = runCatching { ExifInterface(target.absolutePath) }.getOrNull()
            ?: return false
        val descriptor = runCatching { resolver.openFileDescriptor(source, "r") }.getOrNull()
            ?: return false

        descriptor.use { pfd ->
            val sourceExif = runCatching { ExifInterface(pfd.fileDescriptor) }.getOrNull()
                ?: return false
            for (tag in COPIED_TAGS) {
                val value = runCatching { sourceExif.getAttribute(tag) }.getOrNull() ?: continue
                runCatching { targetExif.setAttribute(tag, value) }
            }
        }

        // Die Drehung ist beim Neu-Encoding in die Pixel gerechnet worden,
        // deshalb steht das Feld danach auf "normal".
        targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, OrientationUtil.NORMAL.toString())
        return runCatching { targetExif.saveAttributes() }.isSuccess
    }
}
