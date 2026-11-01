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
import java.util.Locale

/**
 * Durchsucht den vom Benutzer gewaehlten SAF-Ordner.
 *
 * Pro Ordner genau eine Cursor-Abfrage, nicht eine pro Datei - auf
 * langsamen Geraeten mit tausenden Bildern ist das der Unterschied
 * zwischen Sekunden und Minuten. Es wird nichts dekodiert und kein
 * Vorschaubild geladen, der Speicherbedarf bleibt bei wenigen kB.
 */
class SafScanner(private val resolver: ContentResolver) {

    /** Kind-Abrufe, die dieser Lauf fuer die Ordnererkennung ausgegeben hat. */
    private var probes = 0

    data class Progress(val folders: Int, val files: Int)

    /**
     * @param cancelled Wird vor jedem Kindeintrag gefragt. Waehlt der
     *                  Benutzer waehrend eines laufenden Scans einen
     *                  anderen Ordner, muss der alte Lauf aufhoeren - sonst
     *                  ueberschreibt sein Ergebnis die Anzeige des neuen.
     * @param onProgress Fortschritt, auch mitten im Ordner: ein DCIM-
     *                  Ordner mit tausenden Bildern liefert sonst minutenlang
     *                  gar keine Meldung und die Oberflaeche steht still.
     */
    fun scan(
        treeUri: Uri,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): ScanResult {
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
        val folderNames = HashMap<String, String>()
        folderNames[rootId] = readableFolderName(rootId)
        val startedAt = System.currentTimeMillis()
        var folders = 0
        var skipped = 0
        var skippedNoType = 0
        var skippedZeroBytes = 0
        var entriesWithoutMime = 0
        var unreadableFolders = 0
        var foldersOverLimit = 0
        var probedFolders = 0
        probes = 0
        var truncated = false
        var cancelledRun = false
        var sinceProgress = 0

        while (queue.isNotEmpty()) {
            if (folders >= MAX_FOLDERS || items.size >= MAX_FILES) {
                truncated = true
                break
            }
            if (cancelled()) {
                cancelledRun = true
                break
            }
            val folderId = queue.removeFirst()
            if (!visited.add(folderId)) continue
            folders++
            val folderStartedAt = System.currentTimeMillis()
            var folderVideos = 0
            var folderImages = 0
            var folderBytes = 0L
            var folderNoType = 0
            var folderNoMime = 0
            var folderZeroBytes = 0

            val children = queryChildren(treeUri, folderId) { unreadableFolders++ }
            for (child in children) {
                val directory = child.isDirectory ||
                    isUnmarkedDirectory(treeUri, child).also { if (it) probedFolders++ }
                if (directory) {
                    folderNames[child.documentId] = child.name
                    if (queue.size + visited.size < MAX_FOLDERS) {
                        queue.add(child.documentId)
                    } else {
                        foldersOverLimit++
                    }
                } else {
                    if (child.mime.isBlank()) folderNoMime++
                    val extension = Naming.extensionOf(child.name)
                    val kind = when {
                        Naming.isVideo(extension) -> MediaKind.VIDEO
                        Naming.isImage(extension) -> MediaKind.IMAGE
                        else -> null
                    }
                    if (kind == null) {
                        skipped++
                        skippedNoType++
                        folderNoType++
                        continue
                    }
                    // Eine fehlende Groesse im Kind-Cursor ist kein Grund,
                    // die Datei zu verschweigen: manche Provider fuellen
                    // COLUMN_SIZE nicht, und dann waere ein ganzer Ordner
                    // unsichtbar. Sie zaehlt als "Groesse unbekannt" (0) und
                    // wird komprimiert wie jede andere.
                    if (child.size <= 0) {
                        skippedZeroBytes++
                        folderZeroBytes++
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
                            sizeBytes = child.size.coerceAtLeast(0L),
                            lastModified = child.lastModified,
                            kind = kind
                        )
                    )
                    if (kind == MediaKind.VIDEO) folderVideos++ else folderImages++
                    folderBytes += child.size
                    sinceProgress++
                    if (sinceProgress >= PROGRESS_EVERY) {
                        sinceProgress = 0
                        onProgress(Progress(folders, items.size))
                    }
                }
            }
            entriesWithoutMime += folderNoMime
            Log.i(
                "Scan",
                "Ordner $folders: ${folderNames[folderId] ?: folderId} · $folderVideos Videos" +
                    " · $folderImages Bilder · ${folderBytes} B (${mb(folderBytes)} MB)" +
                    " · $folderNoType uebersprungen ohne Medien-Typ" +
                    " · $folderZeroBytes ohne Groessenangabe" +
                    " · $folderNoMime Eintraege ohne MIME" +
                    " · ${System.currentTimeMillis() - folderStartedAt} ms"
            )
            onProgress(Progress(folders, items.size))
        }

        val elapsed = System.currentTimeMillis() - startedAt
        items.sortWith(compareBy({ it.kind }, { it.displayName.lowercase() }))
        val videos = items.count { it.kind == MediaKind.VIDEO }
        val bytes = items.sumOf { it.sizeBytes }
        val truncNote = if (truncated) " · durch Grenze abgeschnitten" else ""
        val cancelNote = if (cancelledRun) " · abgebrochen" else ""
        Log.i(
            "Scan",
            "Fertig: $folders Ordner · $videos Videos · ${items.size - videos} Bilder · $bytes B" +
                " (${mb(bytes)} MB)" +
                " · $skipped uebersprungen" +
                " (davon $skippedNoType ohne Medien-Typ)," +
                " $skippedZeroBytes ohne Groessenangabe (trotzdem gelistet)," +
                " $entriesWithoutMime ohne MIME)" +
                " · $unreadableFolders Ordner nicht lesbar" +
                (if (probedFolders > 0) " · $probedFolders Ordner ohne Verzeichnis-Typ am Kind-Abruf erkannt" else "") +
                " · $foldersOverLimit Unterordner wegen der Ordnergrenze nicht gelesen" +
                " · $elapsed ms Laufzeit" +
                truncNote +
                cancelNote
        )
        if (truncated) {
            Log.w(
                "Scan",
                "Grenze erreicht: MAX_FILES=$MAX_FILES / MAX_FOLDERS=$MAX_FOLDERS - Rest nicht gelesen"
            )
        }
        return ScanResult(items, folders, truncated, skipped, unreadableFolders)
    }

    /** Bytes als MB-Text mit einer Nachkommastelle, deutsch geschrieben. */
    private fun mb(bytes: Long): String =
        String.format(Locale.GERMANY, "%.1f", bytes / 1024.0 / 1024.0)

    /**
     * Lesbarer Name des Wurzelordners. Der Dokument-ID fehlt der Anzeigename,
     * deshalb bleibt es beim Pfadanteil hinter dem Doppelpunkt.
     */
    private fun readableFolderName(documentId: String): String {
        val path = documentId.substringAfter(':', documentId)
        return path.ifBlank { documentId }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean
    )

    /**
     * Ordner, die der Anbieter nicht als Ordner kennzeichnet.
     *
     * Die Welt ist nicht nur der AOSP-Speicheranbieter: manche Dateimanager
     * melden Ordner als `inode/directory`, `resource/folder` oder ohne
     * jeden Typ. Wurde so ein Eintrag als Datei behandelt, fiel er durch die
     * Endungspruefung, galt als "ohne Medien-Typ" - und der Scan blieb auf
     * der obersten Ebene stehen, obwohl der Baum tiefer reicht.
     *
     * Entscheidend ist deshalb der Kind-Abruf: liefert der Anbieter fuer
     * diese Dokument-ID Eintraege, ist es ein Ordner. Das kostet eine
     * Abfrage - aber nur fuer Eintraege, die weder einen echten Typ noch
     * eine Groesse haben, also weder als Datei noch als Ordner erkennbar
     * sind. Danach greift eine Ersatzregel, damit ein Anbieter, der auf
     * jeden Abruf antwortet, den Lauf nicht ausbremst.
     */
    private fun isUnmarkedDirectory(treeUri: Uri, child: Child): Boolean {
        // Ein Typ, der weder Verzeichnis noch "unbekannt" ist, spricht fuer
        // eine Datei; eine gemeldete Groesse ebenfalls.
        if (child.size > 0) return false
        val unknown = child.mime.isBlank() ||
            child.mime == UNKNOWN_MIME ||
            child.mime == BINARY_MIME
        if (!unknown) return false
        if (probes >= MAX_PROBES) {
            // Notbremse: Ordnernamen tragen in der Regel keine Endung.
            return Naming.extensionOf(child.name).isEmpty()
        }
        probes++
        return hasChildren(treeUri, child.documentId)
    }

    /** Fragt einen Kind-Cursor ab, ohne Fehler zu protokollieren. */
    private fun hasChildren(treeUri: Uri, documentId: String): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            cursor != null && cursor.count > 0
        } catch (t: Throwable) {
            false
        } finally {
            cursor?.close()
        }
    }

    private fun queryChildren(
        treeUri: Uri,
        folderId: String,
        onUnreadable: () -> Unit = {}
    ): List<Child> {
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
                onUnreadable()
                Log.w("Scan", "Ordner nicht lesbar (kein Cursor): $folderId")
                return result
            }
            cursor = query
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIndex < 0 || nameIndex < 0) {
                onUnreadable()
                return result
            }

            var rowSkipped = 0
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                if (id == null || name == null) {
                    rowSkipped++
                    continue
                }
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
                        isDirectory = isDirectoryMime(mime)
                    )
                )
            }
            if (rowSkipped > 0) {
                Log.w(
                    "Scan",
                    "Ordner $folderId: $rowSkipped Eintraege ohne Dokument-ID oder Namen uebersprungen"
                )
            }
        } catch (t: Throwable) {
            // Ein nicht lesbarer Unterordner darf den ganzen Lauf nicht
            // verhindern (z. B. geloeschter Ordner oder fehlende Rechte).
            onUnreadable()
            Log.w("Scan", "Ordner nicht lesbar: $folderId", t)
        } finally {
            cursor?.close()
        }
        return result
    }

    companion object {
        /**
         * Obergrenzen. Sie sind keine Schutzmauer gegen Fehlbedienung,
         * sondern gegen einen Provider, der nie endet; ein Kamerafolger mit
         * fuenftausend Bildern muss vollstaendig erscheinen. Bei 20 000
         * Eintraegen liegt die Liste bei rund 7 MB - das ist die Grenze, ab
         * der ein Telefon mit 4 GB knapp wird.
         */
        const val MAX_FILES = 20_000
        const val MAX_FOLDERS = 2_000

        /**
         * MIME-Typen, mit denen Anbieter einen Ordner melden. Der
         * AOSP-Speicheranbieter nimmt `vnd.android.document/directory`;
         * fremde Anbieter (Dateimanager, Cloud) nehmen eigene Typen, und
         * ohne diese Liste fiel der Unterordner stillschweigend weg.
         */
        private val DIRECTORY_MIMES = setOf(
            DocumentsContract.Document.MIME_TYPE_DIR,
            "inode/directory",
            "resource/folder",
            "application/vnd.google-apps.folder",
            "application/x-directory",
            "text/directory"
        )

        internal fun isDirectoryMime(mime: String): Boolean {
            if (mime.isBlank()) return false
            return mime in DIRECTORY_MIMES ||
                mime.endsWith("/directory") ||
                mime.endsWith("/folder") ||
                mime.startsWith("application/vnd.google-apps.folder")
        }

        /** So viele Dateien zwischen zwei Fortschrittsmeldungen. */
        private const val PROGRESS_EVERY = 64

        /**
         * So oft darf ein Scan einen Ordner per Kind-Abruf pruefen. Danach
         * entscheidet die Ersatzregel - ein Anbieter, der auf jede Anfrage
         * mit einem leeren Cursor antwortet, wuerde den Lauf sonst mit einer
         * Abfrage je Datei verlangsamen.
         */
        private const val MAX_PROBES = 2_000

        private const val UNKNOWN_MIME = "application/unknown"
        private const val BINARY_MIME = "application/octet-stream"
    }
}
