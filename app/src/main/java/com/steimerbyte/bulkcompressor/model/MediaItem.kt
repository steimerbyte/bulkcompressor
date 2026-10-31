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
    val skipped: Int = 0
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
    val keepExif: Boolean = true
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
                keepExif = prefs.getBoolean("keep_exif", d.keepExif)
            )
        }
    }
}
