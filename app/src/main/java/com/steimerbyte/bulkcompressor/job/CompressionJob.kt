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
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.OutputMode
import com.steimerbyte.bulkcompressor.model.VideoMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
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
    private var processedBytes = 0L
    private var done = 0
    private var failed = 0
    private var skipped = 0
    private val folderNames = HashMap<String, MutableSet<String>>()
    private var timestampFailureLogged = false
    private var runStartedAt = 0L
    private var fileStartedAt = 0L

    fun run() {
        runStartedAt = System.currentTimeMillis()
        val videoCount = items.count { it.kind == MediaKind.VIDEO }
        val totalBytes = items.sumOf { it.sizeBytes }
        Log.i(
            "Job",
            "Start: ${items.size} Dateien ($videoCount Videos, ${items.size - videoCount} Bilder," +
                " ${formatSize(totalBytes)}) · Ziel: ${describeSettings()}"
        )
        Log.i("Job", "Arbeitsverzeichnis: ${context.cacheDir.absolutePath} · Temp-Dateien je Datei neu")
        JobBus.publish(
            JobBus.State(
                running = true,
                total = items.size,
                log = listOf("Start: ${items.size} Dateien", "Ziel: ${describeSettings()}")
            )
        )

        for ((index, item) in items.withIndex()) {
            if (cancelled.get()) {
                Log.i("Job", "Abbruch bemerkt bei Datei ${index + 1}/${items.size} (${item.displayName})")
                break
            }
            Log.i(
                "Job",
                "Datei ${index + 1}/${items.size}: ${item.displayName} · ${kindLabel(item)}" +
                    " · ${formatSize(item.sizeBytes)} (${item.sizeBytes} B) · ${item.extension.ifBlank { "ohne Endung" }}" +
                    " · ${item.mime.ifBlank { "ohne MIME" }} · Aktion: ${describeAction(item)}"
            )
            publish(index, item.displayName, 0f)
            try {
                process(item, index)
            } catch (e: JobCancelledException) {
                Log.i("Job", "abgebrochen vor ${item.displayName}")
                break
            } catch (t: Throwable) {
                failed++
                logLine("FEHLER ${item.displayName}: ${t.message ?: t.javaClass.simpleName}")
                Log.e("Job", "fehlgeschlagen: ${item.displayName} nach ${elapsedFile()} ms", t)
            }
            done = index + 1
            processedBytes += item.sizeBytes
            if (done % CHECKPOINT_EVERY == 0 && done < items.size) checkpoint("Zwischenstand")
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
        logLine(
            if (cancelledNow) "Abgebrochen nach $done von ${items.size} Dateien"
            else "Fertig: $done bearbeitet, $skipped uebersprungen, $failed fehlgeschlagen"
        )
        checkpoint("Endstand")
        Log.i(
            "Job",
            "Summen: ${formatSize(beforeBytes)} -> ${formatSize(afterBytes)}" +
                " (${percent(beforeBytes, afterBytes)})" +
                " · $done bearbeitet · $skipped uebersprungen · $failed fehlgeschlagen" +
                if (cancelledNow) " · abgebrochen" else ""
        )
        Log.i(
            "Job",
            "Lauf beendet: ${elapsedRun()} ms gesamt · ${done} von ${items.size} Dateien" +
                " · ${formatSize(processedBytes)} Quellmaterial gelesen" +
                " · Durchschnitt ${throughput()} MB/s" +
                " · ${formatSize(savedTotal())} (${percent(beforeBytes, afterBytes)}) gespart" +
                if (cancelledNow) " · vorzeitig abgebrochen" else " · vollstaendig durchgelaufen"
        )
    }

    /**
     * Verdichtungszeile: eine Zeile pro zehn Dateien statt einer pro Datei,
     * damit die Logdatei auch bei tausenden Bildern lesbar bleibt.
     */
    private fun checkpoint(label: String) {
        val elapsed = elapsedRun()
        val processed = if (done <= 0) 1 else done
        val remaining = (items.size - done).coerceAtLeast(0)
        val perFile = elapsed.toDouble() / processed
        val etaMs = (perFile * remaining).toLong()
        Log.i(
            "Job",
            "$label: $done/${items.size} verarbeitet · ${successCount()} Erfolge" +
                " · $skipped uebersprungen · $failed Fehler" +
                " · ${formatSize(savedTotal())} gespart" +
                " · $elapsed ms Laufzeit (${String.format(Locale.US, "%.0f", perFile)} ms/Datei)" +
                " · ${throughput()} MB/s" +
                " · Restzeit ca. $etaMs ms fuer $remaining Dateien"
        )
    }

    private fun successCount(): Int = (done - skipped - failed).coerceAtLeast(0)

    private fun savedTotal(): Long = (beforeBytes - afterBytes).coerceAtLeast(0)

    private fun throughput(): String {
        val elapsed = elapsedRun()
        if (elapsed <= 0 || processedBytes <= 0) return "0,0"
        val mbPerSecond = processedBytes / 1024.0 / 1024.0 / (elapsed / 1000.0)
        return String.format(Locale.US, "%.2f", mbPerSecond).replace('.', ',')
    }

    private fun elapsedRun(): Long {
        if (runStartedAt <= 0) return 0L
        return System.currentTimeMillis() - runStartedAt
    }

    private fun elapsedFile(): Long {
        if (fileStartedAt <= 0) return 0L
        return System.currentTimeMillis() - fileStartedAt
    }

    private fun kindLabel(item: MediaItem): String =
        if (item.kind == MediaKind.VIDEO) "Video" else "Bild"

    /** Was mit dieser Datei passieren soll - vor dem Arbeiten protokolliert. */
    private fun describeAction(item: MediaItem): String {
        if (item.sizeBytes <= 0) return "Groesse 0 B - Verarbeitung wird fehlschlagen"
        val target = if (settings.outputMode == OutputMode.OVERWRITE) "Original ersetzen" else "neue Datei daneben"
        return when (item.kind) {
            MediaKind.VIDEO -> {
                val video = when (settings.videoMode) {
                    VideoMode.BITRATE -> "Video ${settings.bitratePercent}% Bitrate"
                    VideoMode.SCALE -> "Video kurze Kante ${settings.videoShortEdge}px"
                    VideoMode.BOTH -> "Video ${settings.bitratePercent}% + ${settings.videoShortEdge}px"
                }
                "$video · ${settings.codec.name} · $target"
            }
            MediaKind.IMAGE ->
                "Bild Q${settings.imageQuality}" +
                    (if (settings.imageShortEdge > 0) " · ${settings.imageShortEdge}px" else " · Originalgroesse") +
                    " · ${settings.imageFormat.name} · $target"
        }
    }

    /** Haengt eine Zeile an die Oberflaeche an und spiegelt sie ins Log. */
    private fun logLine(line: String) {
        JobBus.appendLog(line)
        Log.i("Job", line)
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
        fileStartedAt = System.currentTimeMillis()
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
        Log.i(
            "Job",
            "${item.displayName} · Video · ${formatSize(item.sizeBytes)} ein" +
                " · Quelle ${info.width}x${info.height} ${info.bitrate / 1000} kbit/s" +
                " ${info.frameRate} fps ${info.durationMs} ms, Rotation ${info.rotation} Grad," +
                " ${info.videoMime.ifBlank { "ohne MIME" }}, Ton ${info.audioMime ?: "keiner"}" +
                " · Plan ${plan.width}x${plan.height} ${plan.bitrate / 1000} kbit/s ${plan.frameRate} fps" +
                " " + if (plan.codecMime == PresetEngine.MIME_HEVC) "H.265" else "H.264"
        )
        if (plan.isNoOp) {
            skipped++
            logLine("SKIP ${item.displayName}: kein Unterschied zu den Einstellungen")
            Log.i(
                "Job",
                "uebersprungen: ${item.displayName} · Grund: keine Einsparung erwartet (Plan entspricht dem Original)" +
                    " (${plan.width}x${plan.height} ${plan.bitrate / 1000} kbit/s)" +
                    " · Zielgroesse waere ${formatSize(item.sizeBytes)}"
            )
            return
        }
        Log.i(
            "Job",
            "${item.displayName} · Transcode startet · Rohschaetzung ${estimateBytes(plan.bitrate, info.durationMs)}" +
                " gegen ${formatSize(item.sizeBytes)} Original"
        )

        val actual = transcoder.transcode(
            uri = item.uri,
            target = temp,
            plan = plan,
            durationMs = info.durationMs,
            orientationHint = info.rotation,
            onProgress = { publish(index, item.displayName, it) },
            isCancelled = { cancelled.get() }
        )
        Log.i(
            "Job",
            "${item.displayName} · Transcode fertig nach ${elapsedFile()} ms" +
                " · ${actual.width}x${actual.height} · ${actual.bitrate / 1000} kbit/s" +
                " · ${formatSize(temp.length())} in der Temp-Datei"
        )

        val requested = "${actual.width}x${actual.height} · ${actual.bitrate / 1000} kbit/s"
        val details = buildString {
            append(requested)
            if (actual.width != plan.width || actual.height != plan.height) {
                append(" (Encoder-Limit)")
            }
            append(" · ${if (actual.codecMime == PresetEngine.MIME_HEVC) "H.265" else "H.264"}")
            if (info.durationMs > 0) append(" · ${info.durationMs} ms")
        }

        val extension = if (Naming.isMp4Compatible(item.extension)) item.extension else "mp4"
        deliver(item, temp, extension, "video/mp4", inPlaceSafe = Naming.isMp4Compatible(item.extension), details)
    }

    /** Grobe Vorabschaetzung: Zielbitrate mal Laufzeit, ohne Tonanteil. */
    private fun estimateBytes(bitrate: Int, durationMs: Long): String {
        if (bitrate <= 0 || durationMs <= 0) return "unbekannt"
        val bytes = bitrate.toLong() * durationMs / 8 / 1000
        return formatSize(bytes)
    }

    // ------------------------------------------------------------------
    // Bilder
    // ------------------------------------------------------------------

    private fun processImage(item: MediaItem, temp: File, index: Int) {
        publish(index, item.displayName, 0.3f)
        val shortEdge = if (settings.imageShortEdge <= 0) "unveraendert" else "${settings.imageShortEdge}px"
        Log.i(
            "Job",
            "${item.displayName} · Bild · ${formatSize(item.sizeBytes)} ein · Plan:" +
                " Q${settings.imageQuality} · $shortEdge · ${settings.imageFormat.name}" +
                " · Quelle ${item.extension.ifBlank { "ohne Endung" }} ${item.mime.ifBlank { "ohne MIME" }}" +
                " · EXIF ${if (settings.keepExif) "behalten" else "verwerfen"}"
        )
        val result = images.compress(item.uri, item.extension, temp, settings)
        publish(index, item.displayName, 0.8f)

        // EXIF auf die Zieldatei, bevor sie an ihren Platz wandert.
        val exifOk = MetaRestore.copyExif(resolver, item.uri, temp, settings.keepExif)
        val exifText = when {
            !settings.keepExif -> "bewusst verworfen"
            exifOk -> "uebernommen"
            else -> "angefordert, aber ohne Wirkung"
        }
        val formatText = if (item.extension == result.format.extension) {
            "identisch (${item.extension})"
        } else {
            "wechselt ${item.extension.ifBlank { "ohne Endung" }} -> ${result.format.extension}," +
                " kein In-Place moeglich"
        }
        Log.i(
            "Job",
            "${item.displayName} · Bild fertig nach ${elapsedFile()} ms" +
                " · ${result.width}x${result.height} · ${result.format.kind.uppercase()}" +
                " · ${formatSize(temp.length())} in der Temp-Datei" +
                " · Format $formatText · EXIF $exifText"
        )
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
            logLine("FEHLER ${item.displayName}: leere Ausgabedatei")
            Log.e(
                "Job",
                "fehlgeschlagen: ${item.displayName} · leere Ausgabedatei (0 B)" +
                    " · Quelle ${formatSize(item.sizeBytes)} · nach ${elapsedFile()} ms" +
                    " · Ziel-MIME $mime · Endung $extension"
            )
            return
        }
        if (after >= item.sizeBytes) {
            skipped++
            logLine(
                "SKIP ${item.displayName}: Ergebnis waere ${formatSize(after)} statt ${formatSize(item.sizeBytes)}"
            )
            Log.i(
                "Job",
                "uebersprungen: ${item.displayName} · Grund: keine Einsparung erwartet" +
                    " (Ergebnis ${formatSize(after)} >= Quelle ${formatSize(item.sizeBytes)})" +
                    " · nach ${elapsedFile()} ms · $details"
            )
            return
        }

        val replaceInPlace = settings.outputMode == OutputMode.OVERWRITE && inPlaceSafe
        val target: Uri?
        if (replaceInPlace) {
            target = item.uri
            Log.i(
                "Job",
                "${item.displayName} · schreibe in das Original (In-Place moeglich:" +
                    " Endung ${item.extension} passt zu $mime)"
            )
            writeDocument(target, temp)
        } else {
            val newName = Naming.compressedName(item.displayName, extension.takeIf { it != item.extension })
            val unique = Naming.uniqueName(newName) { candidate -> existsInFolder(item.parentId, candidate) }
            target = createSibling(item.parentId, mime, unique)
            if (target == null) {
                failed++
                logLine("FEHLER ${item.displayName}: Zieldatei liess sich nicht anlegen")
                Log.e(
                    "Job",
                    "fehlgeschlagen: ${item.displayName} · Zieldatei nicht anlegbar" +
                        " · gewuenschter Name $unique · MIME $mime · Ordner ${item.parentId}" +
                        " · In-Place waere ${if (inPlaceSafe) "moeglich" else "nicht moeglich"}" +
                        " · Ausgabemodus ${settings.outputMode.name}"
                )
                return
            }
            Log.i(
                "Job",
                "${item.displayName} · neue Datei angelegt als $unique" +
                    " (Endung ${if (unique.substringAfterLast('.', "") == item.extension) "unveraendert" else "geaendert"}" +
                    " ${item.extension} -> $extension)"
            )
            writeDocument(target, temp)
            rememberInFolder(item.parentId, unique)
        }

        if (settings.keepTimestamps) {
            val restored = MetaRestore.applyTimestamp(resolver, target, item.lastModified)
            if (restored) {
                Log.i("Job", "${item.displayName} · Aenderungszeit auf ${item.lastModified} zurueckgesetzt")
            }
            if (!restored && !timestampFailureLogged) {
                timestampFailureLogged = true
                logLine(
                    "HINWEIS: Aenderungszeit liess sich nicht zuruecksetzen - " +
                        "Android erlaubt das nur mit \"Zugriff auf alle Dateien\""
                )
                Log.w(
                    "Job",
                    "Hinweis: Aenderungszeit nicht zurueckgesetzt (${item.displayName})"
                )
            }
        }
        rescanLegacy(target)

        beforeBytes += item.sizeBytes
        afterBytes += after
        val savedPercent = percent(item.sizeBytes, after)
        val savedBytes = item.sizeBytes - after
        val savedShare = if (item.sizeBytes > 0) (savedBytes * 100 / item.sizeBytes) else 0
        logLine(
            "${if (replaceInPlace) "ERSETZT" else "NEU"} ${item.displayName} · " +
                "${formatSize(item.sizeBytes)} -> ${formatSize(after)} " +
                "($savedPercent) · $details"
        )
        Log.i(
            "Job",
            "fertig: ${item.displayName} · ${if (replaceInPlace) "ersetzt" else "neu angelegt"}" +
                " · ${formatSize(item.sizeBytes)} vorher, ${formatSize(after)} nachher" +
                " (${item.sizeBytes} B -> $after B)" +
                " · Ersparnis ${formatSize(savedBytes)} · $savedShare% kleiner · ${elapsedFile()} ms" +
                " · $details"
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

        /** Nach so vielen Dateien eine Verdichtungszeile - nicht eine pro Datei. */
        private const val CHECKPOINT_EVERY = 10

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
