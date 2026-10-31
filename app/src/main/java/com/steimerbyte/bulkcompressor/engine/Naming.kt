package com.steimerbyte.bulkcompressor.engine

/** Dateinamen und Endungen. Rein textlich, damit pruefbar. */
object Naming {

    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "avi", "ts", "mts")
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    /** Endungen, deren Datei ein MediaMuxer als MP4 in-place ersetzen darf. */
    private val MP4_COMPATIBLE = setOf("mp4", "m4v", "mov")

    /** Leer, wenn der Name keine Endung hat - ein Punkt am Anfang zaehlt nicht. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    fun baseNameOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    fun isMp4Compatible(extension: String): Boolean = extension in MP4_COMPATIBLE

    fun isVideo(extension: String): Boolean = extension in VIDEO_EXTENSIONS

    fun isImage(extension: String): Boolean = extension in IMAGE_EXTENSIONS

    /** "IMG_01.jpg" -> "IMG_01_compressed.jpg" */
    fun compressedName(name: String, extensionOverride: String? = null): String {
        val base = baseNameOf(name)
        val ext = extensionOverride ?: extensionOf(name)
        return if (ext.isEmpty()) "${base}_compressed" else "${base}_compressed.$ext"
    }

    /** Haengt _2, _3 ... an, bis der Name in [exists] noch nicht vorkommt. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val base = baseNameOf(name)
        val ext = extensionOf(name)
        var index = 2
        while (index < 10_000) {
            val candidate = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
            if (!exists(candidate)) return candidate
            index++
        }
        return name
    }
}
