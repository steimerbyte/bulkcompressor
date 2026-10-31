package com.steimerbyte.bulkcompressor.job

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.ImageCompressor
import com.steimerbyte.bulkcompressor.engine.JobCancelledException
import com.steimerbyte.bulkcompressor.engine.MetaRestore
import com.steimerbyte.bulkcompressor.engine.Naming
import com.steimerbyte.bulkcompressor.engine.PresetEngine
import com.steimerbyte.bulkcompressor.engine.VideoTranscoder
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.OutputMode
import com.steimerbyte.bulkcompressor.model.VideoMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fuehrt einen Auftrag Datei fuer Datei aus - bewusst nacheinander.
 * Parallel zu arbeiten wuerde mehrere Encoder gleichzeitig starten und
 * auf Geraeten mit wenig RAM genau das kaputt machen, was hier wichtig
 * ist: der Speicherbedarf bleibt bei einem einzigen Transcode konstant.
 */
class CompressionJob(
    private val context: Context,
    private val treeUri: Uri,
    private val items: List<MediaItem>,
    private val settings: CompressSettings,
    private val cancelled: AtomicBoolean
) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val transcoder = VideoTranscoder(context)
    private val images = ImageCompressor(context)

    private var beforeBytes = 0L
    private var afterBytes = 0L
    private var done = 0
    private var failed = 0
    private var skipped = 0
    private val folderNames = HashMap<String, MutableSet<String>>()
    private var timestampFailureLogged = false

    fun run() {
        JobBus.publish(
            JobBus.State(
                running = true,
                total = items.size,
                log = listOf("Start: ${items.size} Dateien", "Ziel: ${describeSettings()}")
            )
        )

        for ((index, item) in items.withIndex()) {
            if (cancelled.get()) break
            publish(index, item.displayName, 0f)
            try {
                process(item, index)
            } catch (e: JobCancelledException) {
                break
            } catch (t: Throwable) {
                failed++
                JobBus.appendLog("FEHLER ${item.displayName}: ${t.message ?: t.javaClass.simpleName}")
            }
            done = index + 1
        }

        val cancelledNow = cancelled.get()
        JobBus.publish(
            JobBus.state.copy(
                running = false,
                finished = !cancelledNow,
                cancelled = cancelledNow,
                done = done,
                failed = failed,
                skipped = skipped,
                overall = if (cancelledNow) JobBus.state.overall else 1f,
                beforeBytes = beforeBytes,
                afterBytes = afterBytes,
                currentName = ""
            )
        )
        JobBus.appendLog(
            if (cancelledNow) "Abgebrochen nach $done von ${items.size} Dateien"
            else "Fertig: $done bearbeitet, $skipped uebersprungen, $failed fehlgeschlagen"
        )
    }

    private fun describeSettings(): String {
        val video = when (settings.videoMode) {
            VideoMode.BITRATE -> "Bitrate ${settings.bitratePercent}%"
            VideoMode.SCALE ->
                if (settings.videoShortEdge > 0) "Aufloesung ${settings.videoShortEdge}p" else "Aufloesung unveraendert"
            VideoMode.BOTH ->
                "${settings.bitratePercent}% Bitrate + ${settings.videoShortEdge}p"
        }
        val output = if (settings.outputMode == OutputMode.OVERWRITE) "Original ersetzen" else "Original behalten"
        return "$video, Bilder Q${settings.imageQuality}, $output"
    }

    private fun process(item: MediaItem, index: Int) {
        val temp = File(context.cacheDir, "bulk_${System.currentTimeMillis()}.tmp")
        try {
            when (item.kind) {
                MediaKind.VIDEO -> processVideo(item, temp, index)
                MediaKind.IMAGE -> processImage(item, temp, index)
            }
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------

    private fun processVideo(item: MediaItem, temp: File, index: Int) {
        val info = transcoder.probe(item.uri)
        val plan = PresetEngine.videoPlan(
            width = info.width,
            height = info.height,
            sourceBitrate = info.bitrate,
            frameRate = info.frameRate,
            sourceMime = info.videoMime,
            settings = settings
        )
        if (plan.isNoOp) {
            skipped++
            JobBus.appendLog("SKIP ${item.displayName}: kein Unterschied zu den Einstellungen")
            return
        }

        val actual = transcoder.transcode(
            uri = item.uri,
            target = temp,
            plan = plan,
            durationMs = info.durationMs,
            orientationHint = info.rotation,
            onProgress = { publish(index, item.displayName, it) },
            isCancelled = { cancelled.get() }
        )

        val requested = "${actual.width}x${actual.height} · ${actual.bitrate / 1000} kbit/s"
        val details = buildString {
            append(requested)
            if (actual.width != plan.width || actual.height != plan.height) {
                append(" (Encoder-Limit)")
            }
            append(" · ${if (actual.codecMime == PresetEngine.MIME_HEVC) "H.265" else "H.264"}")
        }

        val extension = if (Naming.isMp4Compatible(item.extension)) item.extension else "mp4"
        deliver(item, temp, extension, "video/mp4", inPlaceSafe = Naming.isMp4Compatible(item.extension), details)
    }

    // ------------------------------------------------------------------
    // Bilder
    // ------------------------------------------------------------------

    private fun processImage(item: MediaItem, temp: File, index: Int) {
        publish(index, item.displayName, 0.3f)
        val result = images.compress(item.uri, item.extension, temp, settings)
        publish(index, item.displayName, 0.8f)

        // EXIF auf die Zieldatei, bevor sie an ihren Platz wandert.
        val exifOk = MetaRestore.copyExif(resolver, item.uri, temp, settings.keepExif)
        val inPlaceSafe = images.isInPlaceCompatible(item.extension, result.format)
        val details = "${result.width}x${result.height} · ${result.format.kind.uppercase()}" +
            if (exifOk) " · EXIF" else ""
        deliver(item, temp, result.format.extension, result.format.mime, inPlaceSafe, details)
    }

    // ------------------------------------------------------------------
    // Ablage
    // ------------------------------------------------------------------

    private fun deliver(
        item: MediaItem,
        temp: File,
        extension: String,
        mime: String,
        inPlaceSafe: Boolean,
        details: String
    ) {
        val after = temp.length()
        if (after <= 0) {
            failed++
            JobBus.appendLog("FEHLER ${item.displayName}: leere Ausgabedatei")
            return
        }
        if (after >= item.sizeBytes) {
            skipped++
            JobBus.appendLog(
                "SKIP ${item.displayName}: Ergebnis waere ${formatSize(after)} statt ${formatSize(item.sizeBytes)}"
            )
            return
        }

        val replaceInPlace = settings.outputMode == OutputMode.OVERWRITE && inPlaceSafe
        val target: Uri?
        if (replaceInPlace) {
            target = item.uri
            writeDocument(target, temp)
        } else {
            val newName = Naming.compressedName(item.displayName, extension.takeIf { it != item.extension })
            val unique = Naming.uniqueName(newName) { candidate -> existsInFolder(item.parentId, candidate) }
            target = createSibling(item.parentId, mime, unique)
            if (target == null) {
                failed++
                JobBus.appendLog("FEHLER ${item.displayName}: Zieldatei liess sich nicht anlegen")
                return
            }
            writeDocument(target, temp)
            rememberInFolder(item.parentId, unique)
        }

        if (settings.keepTimestamps) {
            val restored = MetaRestore.applyTimestamp(resolver, target, item.lastModified)
            if (!restored && !timestampFailureLogged) {
                timestampFailureLogged = true
                JobBus.appendLog(
                    "HINWEIS: Aenderungszeit liess sich nicht zuruecksetzen - " +
                        "Android erlaubt das nur mit \"Zugriff auf alle Dateien\""
                )
            }
        }
        rescanLegacy(target)

        beforeBytes += item.sizeBytes
        afterBytes += after
        JobBus.appendLog(
            "${if (replaceInPlace) "ERSETZT" else "NEU"} ${item.displayName} · " +
                "${formatSize(item.sizeBytes)} → ${formatSize(after)} " +
                "(${percent(item.sizeBytes, after)}) · $details"
        )
    }

    private fun writeDocument(target: Uri, temp: File) {
        // "wt" kuerzt die Datei und ist der Modus, den der
        // ExternalStorageProvider erwartet. Manche Provider kennen nur
        // "w" - dann ist das der Rueckfall.
        val stream = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(target, "w") }.getOrNull()
            ?: throw IOException("Datei nicht beschreibbar")
        stream.use { out ->
            temp.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        }
    }

    private fun createSibling(parentId: String, mime: String, name: String): Uri? = runCatching {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        DocumentsContract.createDocument(resolver, parent, mime, name)
    }.getOrNull()

    private fun existsInFolder(parentId: String, name: String): Boolean =
        (folderNames[parentId] ?: loadNames(parentId)).contains(name)

    private fun rememberInFolder(parentId: String, name: String) {
        folderNames.getOrPut(parentId) { loadNames(parentId).toMutableSet() }.add(name)
    }

    private fun loadNames(parentId: String): MutableSet<String> {
        val names = HashSet<String>()
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names.add(it) }
                }
            }
        }
        folderNames[parentId] = names
        return names
    }

    /** Vor Android 10 muss die Galerie den neuen Stand erst erfahren. */
    private fun rescanLegacy(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) return
        runCatching {
            val documentId = DocumentsContract.getDocumentId(uri)
            if (documentId.startsWith("primary:")) {
                val relative = documentId.substringAfter(':')
                val path = "${Environment.getExternalStorageDirectory().absolutePath}/$relative"
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
        }
    }

    // ------------------------------------------------------------------
    // Fortschritt
    // ------------------------------------------------------------------

    private var lastPublish = 0L

    private fun publish(index: Int, name: String, fileProgress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_INTERVAL_MS && fileProgress < 1f) return
        lastPublish = now
        val overall = if (items.isEmpty()) 0f else (index + fileProgress) / items.size
        JobBus.publish(
            JobBus.state.copy(
                running = true,
                total = items.size,
                done = index,
                failed = failed,
                skipped = skipped,
                currentName = name,
                overall = overall.coerceIn(0f, 1f),
                beforeBytes = beforeBytes,
                afterBytes = afterBytes
            )
        )
    }

    private fun percent(before: Long, after: Long): String {
        if (before <= 0) return "-"
        val saved = ((before - after) * 100 / before)
        return "-$saved%"
    }

    companion object {
        private const val COPY_BUFFER = 64 * 1024
        private const val PUBLISH_INTERVAL_MS = 250L

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            // Deutsche Schreibweise mit Komma, unabhaengig von der
            // Geraeteeinstellung im Rest der App.
            val locale = java.util.Locale.GERMANY
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(locale, "%.0f kB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(locale, "%.1f MB", mb)
            return String.format(locale, "%.2f GB", mb / 1024.0)
        }
    }
}
