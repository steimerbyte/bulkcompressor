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

    /**
     * Gegenrichtung zu [compressedName]: der Stamm der **Quelle**, aus dem
     * dieser Name hervorgegangen ist - oder `null`, wenn es kein
     * Kopie-Name ist.
     *
     * Gebraucht vom Scan fuer "Original behalten": dort bleibt das
     * Original unangetastet und traegt deshalb nie einen Marker. Erledigt
     * ist es nur, wenn daneben eine Kopie liegt, die einen Marker hat - und
     * die findet man ueber den Stamm der Quelle. `compressedName` allein
     * reicht dafuer nicht, weil der Job den tatsaechlichen Namen an zwei
     * Stellen anders bildet:
     *
     *  - Formatwechsel: `deliver(..., extension, ...)` uebergibt die neue
     *    Endung (HEIC -> JPEG ergibt `IMG_1_compressed.jpg`, nicht `.heic`),
     *  - Namenskollision: [uniqueName] haengt `_2`, `_3` ... an
     *    (`IMG_1_compressed_2.jpg`).
     *
     * Deshalb liefert diese Funktion nur den **Stamm der Quelle**, ohne
     * Endung. Welche Kopien zu welchem Original gehoeren, entscheidet der
     * Aufrufer: er vergleicht erst den vollen Soll-Namen und laesst den
     * Stamm nur gelten, wenn er im Ordner eindeutig ist - so bleibt eine
     * Uneindeutigkeit sichtbar, statt hier stillschweigend geraten zu
     * werden.
     */
    fun copySourceStem(copyName: String): String? {
        val base = baseNameOf(copyName)
        val direct = base.removeSuffix(COMPRESSED_SUFFIX)
        if (direct != base) return direct.ifEmpty { null }
        // Kollisionsfall aus [uniqueName]: "IMG_1_compressed_2" -> "IMG_1".
        // Der Ziffern-Rest wird nur hinter "_compressed" entfernt, damit
        // "IMG_1_2.jpg" (ein echtes Original) nicht zu "IMG_1" wird.
        val cut = base.lastIndexOf('_')
        if (cut <= 0) return null
        val suffix = base.substring(cut + 1)
        if (suffix.isEmpty() || !suffix.all { it in '0'..'9' }) return null
        val head = base.substring(0, cut)
        val stem = head.removeSuffix(COMPRESSED_SUFFIX)
        return stem.takeIf { it != head && it.isNotEmpty() }
    }

    private const val COMPRESSED_SUFFIX = "_compressed"

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
