package com.steimerbyte.bulkcompressor.scan

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.Naming
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult

/**
 * Durchsucht den vom Benutzer gewaehlten SAF-Ordner.
 *
 * Pro Ordner genau eine Cursor-Abfrage, nicht eine pro Datei - auf
 * langsamen Geraeten mit tausenden Bildern ist das der Unterschied
 * zwischen Sekunden und Minuten. Es wird nichts dekodiert und kein
 * Vorschaubild geladen, der Speicherbedarf bleibt bei wenigen kB.
 */
class SafScanner(private val resolver: ContentResolver) {

    data class Progress(val folders: Int, val files: Int)

    fun scan(treeUri: Uri, onProgress: (Progress) -> Unit = {}): ScanResult {
        val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        val rootId = root.getOrNull()
        if (rootId == null) {
            Log.w("Scan", "Wurzelordner nicht lesbar: $treeUri", root.exceptionOrNull())
            return ScanResult(emptyList(), 0, false)
        }
        Log.i("Scan", "Start: $treeUri")

        val items = ArrayList<MediaItem>(256)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val visited = HashSet<String>()
        var folders = 0
        var skipped = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (folders >= MAX_FOLDERS || items.size >= MAX_FILES) {
                truncated = true
                break
            }
            val folderId = queue.removeFirst()
            if (!visited.add(folderId)) continue
            folders++

            val children = queryChildren(treeUri, folderId)
            for (child in children) {
                if (child.isDirectory) {
                    if (queue.size + visited.size < MAX_FOLDERS) queue.add(child.documentId)
                } else {
                    val extension = Naming.extensionOf(child.name)
                    val kind = when {
                        Naming.isVideo(extension) -> MediaKind.VIDEO
                        Naming.isImage(extension) -> MediaKind.IMAGE
                        else -> null
                    }
                    if (kind == null) {
                        skipped++
                        continue
                    }
                    if (child.size <= 0) {
                        skipped++
                        continue
                    }
                    if (items.size >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    items.add(
                        MediaItem(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                            docId = child.documentId,
                            parentId = folderId,
                            displayName = child.name,
                            mime = child.mime,
                            sizeBytes = child.size,
                            lastModified = child.lastModified,
                            kind = kind
                        )
                    )
                }
            }
            onProgress(Progress(folders, items.size))
        }

        items.sortWith(compareBy({ it.kind }, { it.displayName.lowercase() }))
        val videos = items.count { it.kind == MediaKind.VIDEO }
        val bytes = items.sumOf { it.sizeBytes }
        Log.i(
            "Scan",
            "Fertig: $folders Ordner · $videos Videos · ${items.size - videos} Bilder · $bytes B" +
                " · $skipped uebersprungen" +
                if (truncated) " · durch Grenze abgeschnitten" else ""
        )
        if (truncated) {
            Log.w(
                "Scan",
                "Grenze erreicht: MAX_FILES=$MAX_FILES / MAX_FOLDERS=$MAX_FOLDERS - Rest nicht gelesen"
            )
        }
        return ScanResult(items, folders, truncated, skipped)
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean
    )

    private fun queryChildren(treeUri: Uri, folderId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = ArrayList<Child>(32)

        var cursor: Cursor? = null
        try {
            val query = resolver.query(childrenUri, projection, null, null, null)
            if (query == null) {
                Log.w("Scan", "Ordner nicht lesbar (kein Cursor): $folderId")
                return result
            }
            cursor = query
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIndex < 0 || nameIndex < 0) return result

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) ?: "" else ""
                result.add(
                    Child(
                        documentId = id,
                        name = name,
                        mime = mime,
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                        lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            cursor.getLong(modifiedIndex)
                        } else 0L,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        } catch (t: Throwable) {
            // Ein nicht lesbarer Unterordner darf den ganzen Lauf nicht
            // verhindern (z. B. geloeschter Ordner oder fehlende Rechte).
            Log.w("Scan", "Ordner nicht lesbar: $folderId", t)
        } finally {
            cursor?.close()
        }
        return result
    }

    companion object {
        const val MAX_FILES = 4000
        const val MAX_FOLDERS = 400
    }
}
