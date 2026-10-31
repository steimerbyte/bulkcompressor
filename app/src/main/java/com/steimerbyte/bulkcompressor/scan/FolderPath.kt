package com.steimerbyte.bulkcompressor.scan

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Lesbarer Name eines SAF-Ordners.
 *
 * Dieselbe Umsetzung fuer den Hauptbildschirm und die Statistik: der
 * Dienst schreibt den Ordner in die Laufhistorie, die Oberflaeche zeigt
 * ihn oben an. Zwei Kopien dieser Regel wuerden frueher oder spaeter
 * zwei Schreibweisen fuer denselben Ordner erzeugen.
 */
object FolderPath {

    fun display(uri: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "")
        val volumeName = if (volume == "primary") "Intern" else volume
        if (path.isBlank()) volumeName else "$volumeName / ${path.replace("/", " / ")}"
    }.getOrDefault(uri.toString())
}
