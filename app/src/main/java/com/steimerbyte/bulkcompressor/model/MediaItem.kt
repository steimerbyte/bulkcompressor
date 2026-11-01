package com.steimerbyte.bulkcompressor.model

import android.net.Uri

enum class MediaKind { VIDEO, IMAGE }

/**
 * Eine gefundene Datei im vom Benutzer gewaehlten Ordner.
 *
 * [docId] und [parentId] sind die SAF-Dokument-IDs. Sie sind der Grund,
 * warum die App in-place arbeiten kann: ueber [parentId] laesst sich eine
 * neue Datei exakt im selben Ordner anlegen, und ueber [uri] laesst sich
 * die vorhandene Datei ueberschreiben, ohne dass sie ihren Platz in der
 * Galerie verliert.
 */
data class MediaItem(
    val uri: Uri,
    val docId: String,
    val parentId: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val kind: MediaKind
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    /** MediaStore-MIME der Ausgabe, den der DocumentsProvider erwartet. */
    fun outputMime(fallback: String): String = mime.ifBlank { fallback }
}

data class ScanResult(
    val items: List<MediaItem>,
    val folderCount: Int,
    val truncated: Boolean,
    val skipped: Int = 0,
    /**
     * Dateien, die schon einen Marker tragen und deshalb gar nicht erst
     * auf der Liste stehen. Sie werden gezaehlt, weil eine kuerzere Liste
     * sonst wie ein Fehler aussieht.
     */
    val skippedTagged: Int = 0,
    /**
     * Ordner, deren Kind-Cursor nicht (vollstaendig) gelesen werden konnte.
     * Sie sind der Grund, wenn eine Liste vollstaendig aussieht, aber
     * Dateien fehlen - der Nutzer muss das erfahren, nicht nur das Log.
     */
    val unreadableFolders: Int = 0,
    /**
     * Dateien, deren Marker **nicht lesbar** war. Sie stehen auf der Liste,
     * weil "unbekannt" nicht "komprimiert" ist - ein Lauf wuerde sie nur
     * ueberspringen, wenn der Marker lesbar waere. Gezaehlt wird trotzdem:
     * eine Zahl ueber null bedeutet, dass der Filter fuer diesen Anbieter
     * nicht greift, und das muss sichtbar sein statt wie "alles neu"
     * auszusehen.
     */
    val markersUnreadable: Int = 0,
    /**
     * Der Grund, warum die Marker nicht lesbar waren - einmal, nicht je
     * Datei. Bei 500 Fotos mit derselben Ursache stand auf dem Geraet nur
     * "500 Marker nicht lesbar" und im Protokoll 500 identische Zeilen
     * ohne Ursache; der Nutzer musste ein Kabel anschliessen, um zu
     * erfahren, was los ist. Ursache und Zahl gehoeren zusammen auf den
     * Bildschirm.
     */
    val markerReason: String = "",
    /** Einmal gezaehlt beim Scan - nicht bei jeder Anzeige neu gefiltert. */
    val videoCount: Int = items.count { it.kind == MediaKind.VIDEO },
    val imageCount: Int = items.size - videoCount
) {
    val videos: List<MediaItem> get() = items.filter { it.kind == MediaKind.VIDEO }
    val images: List<MediaItem> get() = items.filter { it.kind == MediaKind.IMAGE }
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
}

enum class VideoMode { BITRATE, SCALE, BOTH }
enum class VideoCodec { AUTO, AVC, HEVC }
enum class ImageFormat { KEEP, JPEG, WEBP }
enum class OutputMode { OVERWRITE, KEEP_ORIGINAL }

/**
 * Alle Einstellungen eines Laufs. Wird in den SharedPreferences abgelegt,
 * damit der naechste Start dieselbe Konfiguration vorfindet.
 */
data class CompressSettings(
    val videoMode: VideoMode = VideoMode.SCALE,
    val bitratePercent: Int = 60,
    /** Kurze Kante in Pixeln; 0 = Originalgroesse beibehalten. */
    val videoShortEdge: Int = 1080,
    val codec: VideoCodec = VideoCodec.AUTO,
    val imageQuality: Int = 80,
    val imageShortEdge: Int = 0,
    val imageFormat: ImageFormat = ImageFormat.KEEP,
    val outputMode: OutputMode = OutputMode.OVERWRITE,
    val keepTimestamps: Boolean = true,
    val keepExif: Boolean = true,
    /** Klickgeraeusche bei Knoepfen, Schaltern und Slidern. */
    val clickSounds: Boolean = true,
    /**
     * Marker "schon komprimiert" in den Metadaten ignorieren.
     *
     * Aus: eine Datei, die schon durch einen Lauf ging, wird
     * uebersprungen. An: sie wird trotzdem komprimiert - fuer den Fall,
     * dass der erste Lauf zu schwach eingestellt war. Der Marker wird
     * dabei nicht geloescht, sondern mitgezaehlt (`runs`).
     */
    val ignoreCompressionTag: Boolean = false
) {
    fun save(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putString("video_mode", videoMode.name)
            .putInt("bitrate_percent", bitratePercent)
            .putInt("video_short_edge", videoShortEdge)
            .putString("codec", codec.name)
            .putInt("image_quality", imageQuality)
            .putInt("image_short_edge", imageShortEdge)
            .putString("image_format", imageFormat.name)
            .putString("output_mode", outputMode.name)
            .putBoolean("keep_timestamps", keepTimestamps)
            .putBoolean("keep_exif", keepExif)
            .putBoolean("click_sounds", clickSounds)
            .putBoolean("ignore_compression_tag", ignoreCompressionTag)
            .apply()
    }

    companion object {
        const val PREFS = "bulk_compressor_settings"

        fun load(prefs: android.content.SharedPreferences): CompressSettings {
            val d = CompressSettings()
            fun <T : Enum<T>> enum(name: String?, values: Array<T>, fallback: T): T =
                values.firstOrNull { it.name == name } ?: fallback
            return CompressSettings(
                videoMode = enum(prefs.getString("video_mode", null), VideoMode.entries.toTypedArray(), d.videoMode),
                bitratePercent = prefs.getInt("bitrate_percent", d.bitratePercent),
                videoShortEdge = prefs.getInt("video_short_edge", d.videoShortEdge),
                codec = enum(prefs.getString("codec", null), VideoCodec.entries.toTypedArray(), d.codec),
                imageQuality = prefs.getInt("image_quality", d.imageQuality),
                imageShortEdge = prefs.getInt("image_short_edge", d.imageShortEdge),
                imageFormat = enum(prefs.getString("image_format", null), ImageFormat.entries.toTypedArray(), d.imageFormat),
                outputMode = enum(prefs.getString("output_mode", null), OutputMode.entries.toTypedArray(), d.outputMode),
                keepTimestamps = prefs.getBoolean("keep_timestamps", d.keepTimestamps),
                keepExif = prefs.getBoolean("keep_exif", d.keepExif),
                clickSounds = prefs.getBoolean("click_sounds", d.clickSounds),
                ignoreCompressionTag = prefs.getBoolean("ignore_compression_tag", d.ignoreCompressionTag)
            )
        }
    }
}
