package com.steimerbyte.bulkcompressor.engine

/**
 * EXIF-Orientierung als reine Abbildung, ohne Android-Abhaengigkeit.
 * Wird von Tests geprueft und von der Bildkompression angewendet.
 */
object OrientationUtil {

    /** Gradzahl im Uhrzeigersinn, die das Bild gedreht werden muss. */
    fun rotationDegrees(orientation: Int): Int = when (orientation) {
        6, 7 -> 90
        3, 4 -> 180
        5, 8 -> 270
        else -> 0
    }

    /** Spiegeln an der horizontalen Achse noetig? */
    fun flipHorizontal(orientation: Int): Boolean = orientation in setOf(2, 4, 5, 7)

    /**
     * Nach dem Beschneiden/Skalieren ist das Bild bereits in Anzeigelage,
     * die Orientierung in der Ausgabedatei muss also "normal" sein.
     */
    const val NORMAL = 1
}
