package com.steimerbyte.bulkcompressor.job

import android.content.ContentResolver
import android.content.Context
import android.media.MediaCodec
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.BuildConfig
import com.steimerbyte.bulkcompressor.engine.ImageCompressor
import com.steimerbyte.bulkcompressor.engine.JobCancelledException
import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import com.steimerbyte.bulkcompressor.engine.MetaRestore
import com.steimerbyte.bulkcompressor.engine.Naming
import com.steimerbyte.bulkcompressor.engine.PresetEngine
import com.steimerbyte.bulkcompressor.engine.VideoPlan
import com.steimerbyte.bulkcompressor.engine.VideoTranscoder
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.TagFile
import com.steimerbyte.bulkcompressor.engine.tag.TagStore
import com.steimerbyte.bulkcompressor.engine.verify.Verifier
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
    /** Bereits erledigte Dateien aus einem fortgesetzten Lauf. */
    private var resumeBase = 0
    private var failed = 0
    private var skipped = 0
    private val folderNames = HashMap<String, MutableSet<String>>()
    private var timestampFailureLogged = false
    private var runStartedAt = 0L
    private var fileStartedAt = 0L

    /** Lauf-Journal: haelt fest, welche Datei schon erledigt ist. */
    private var journal: RunJournal? = null

    /** Ersetzen mit Rueckfallebene; erst bei Bedarf angelegt. */
    private val safeReplace: SafeReplace by lazy { SafeReplace(context.cacheDir) }

    /** Wahr, wenn der Lauf wegen vollem Speicher angehalten wurde. */
    private var storageStopped = false

    /** Begruendung des letzten Fehlschlags fuer das Journal. */
    private var failureReason: String = ""

    /** Marker der aktuellen Quelldatei, `null` wenn sie unmarkiert ist. */
    private var sourceTag: CompressionTag? = null

    /** Marker-Leser fuer Dokumente; erst bei Bedarf angelegt. */
    private val tagStore: TagStore by lazy { TagStore(context.contentResolver) }

    fun run() {
        runStartedAt = System.currentTimeMillis()

        // Ein unterbrochener Lauf wird fortgesetzt, statt von vorn zu beginnen.
        val snapshot = openJournal()
        // Fehlgeschlagene Dateien gelten bewusst NICHT als erledigt: genau
        // sie sind der Grund, warum der Nutzer den Lauf fortsetzt. Vorher
        // fielen sie stillschweigend heraus, und der Restlauf meldete
        // "Fertig" ueber einer Luecke.
        val alreadyDone = snapshot?.completed
            ?.filterValues { it != RunJournal.Result.FAIL }
            ?.keys ?: emptySet()
        resumeBase = alreadyDone.size
        if (snapshot != null) {
            // Kennzahlen der Vorsitzung uebernehmen, statt bei Null zu
            // beginnen - sonst zeigte der Fortsetzungslauf nur den Rest.
            skipped = snapshot.completed.count { it.value == RunJournal.Result.SKIP }
            beforeBytes = snapshot.beforeBytes
            afterBytes = snapshot.afterBytes
        }
        // Schluessel ist die SAF-Dokument-ID: danach sucht der Dienst die
        // Dateien beim Fortsetzen wieder, nicht nach der URI.
        val work = items.filterNot { it.docId in alreadyDone }
        val totalBytes = work.sumOf { it.sizeBytes }
        val videoCount = work.count { it.kind == MediaKind.VIDEO }
        Log.i(
            "Job",
            "Start: ${work.size} Dateien ($videoCount Videos, ${work.size - videoCount} Bilder," +
                " ${formatSize(totalBytes)})" +
                (if (resumeBase > 0) " · ${resumeBase} bereits erledigt" else "") +
                " · Ziel: ${describeSettings()}"
        )
        Log.i("Job", "Arbeitsverzeichnis: ${context.cacheDir.absolutePath} · Temp-Dateien je Datei neu")
        Log.i("Ram", "Speicher zu Beginn: ${MemoryGuard.snapshot()}")
        JobBus.publish(
            JobBus.State(
                running = true,
                total = work.size,
                log = buildList {
                    add("Start: ${work.size} Dateien")
                    add("Ziel: ${describeSettings()}")
                    if (snapshot != null) add("Wiederaufnahme: ${snapshot.describe()}")
                }
            )
        )
        if (snapshot != null) {
            logLine(
                "WIEDERAUFNAHME: ${snapshot.doneCount} von ${snapshot.total} Dateien waren schon erledigt," +
                    " ${work.size} werden jetzt bearbeitet"
            )
        }

        if (work.isEmpty()) {
            Log.i("Job", "Nichts zu tun: alle ${items.size} Dateien waren bereits erledigt")
            journal?.finish("fertig")
            journal = null
            JobBus.publish(
                JobBus.state.copy(
                    running = false,
                    finished = true,
                    total = 0,
                    done = 0,
                    overall = 1f,
                    currentName = ""
                )
            )
            return
        }

        for ((index, item) in work.withIndex()) {
            if (cancelled.get()) {
                Log.i("Job", "Abbruch bemerkt bei Datei ${index + 1}/${work.size} (${item.displayName})")
                break
            }
            if (!ensureStorage(item, index, work.size)) {
                storageStopped = true
                break
            }
            // Marker unmittelbar vor der Datei lesen, nicht aus dem Scan
            // uebernehmen: zwischen Ordnerauswahl und Start kann ein
            // anderer Lauf dieselbe Datei erwischt haben.
            if (!settings.ignoreCompressionTag) {
                val existing = tagStore.read(item.uri)
                if (existing != null) {
                    skipped++
                    logLine(
                        "SKIP ${item.displayName}: bereits komprimiert (${existing.describe()})"
                    )
                    Log.i(
                        "Job",
                        "uebersprungen: ${item.displayName} · Grund: Marker vorhanden" +
                            " (${existing.describe()}) · Einstellung: Marker beachten" +
                            " · ${formatSize(item.sizeBytes)}"
                    )
                    journal?.mark(item.docId, RunJournal.Result.SKIP, item.sizeBytes, 0, "bereits komprimiert")
                    done = resumeBase + index + 1
                    // Uebersprungene Dateien zaehlen fuer den Balken mit,
                    // vorher blieb er stehen und sah nach Haenger aus.
                    publish(index + 1, item.displayName, 0f)
                    continue
                }
            }
            // Vor jeder Datei: bei Speicherdruck warten, statt den Prozess
            // deswegen zu verlieren.
            MemoryGuard.pauseBefore { cancelled.get() }

            Log.i(
                "Job",
                "Datei ${index + 1}/${work.size}: ${item.displayName} · ${kindLabel(item)}" +
                    " · ${formatSize(item.sizeBytes)} (${item.sizeBytes} B) · ${item.extension.ifBlank { "ohne Endung" }}" +
                    " · ${item.mime.ifBlank { "ohne MIME" }} · Aktion: ${describeAction(item)}"
            )
            publish(index, item.displayName, 0f)
            val failedBefore = failed
            val skippedBefore = skipped
            val afterBefore = afterBytes
            failureReason = ""
            var cancelledHere = false
            try {
                process(item, index)
            } catch (e: JobCancelledException) {
                cancelledHere = true
                Log.i("Job", "abgebrochen vor ${item.displayName}")
            } catch (o: OutOfMemoryError) {
                failed++
                failureReason = "Speicher erschoepft"
                logLine("FEHLER ${item.displayName}: Arbeitsspeicher erschoepft")
                Log.e("Job", "fehlgeschlagen: ${item.displayName} · Speicher erschoepft · ${MemoryGuard.snapshot()}")
            } catch (t: Throwable) {
                failed++
                failureReason = t.message ?: t.javaClass.simpleName
                logLine("FEHLER ${item.displayName}: ${failureReason}")
                Log.e("Job", "fehlgeschlagen: ${item.displayName} nach ${elapsedFile()} ms", t)
            }
            if (cancelledHere) break

            // Journal: eine Zeile je Datei, sofort auf der Platte.
            val result = when {
                failed > failedBefore -> RunJournal.Result.FAIL
                skipped > skippedBefore -> RunJournal.Result.SKIP
                else -> RunJournal.Result.OK
            }
            val savedAfter = if (result == RunJournal.Result.OK) afterBytes - afterBefore else 0L
            journal?.mark(
                item.docId,
                result,
                beforeBytes = item.sizeBytes,
                afterBytes = savedAfter,
                reason = failureReason.take(80)
            )

            done = resumeBase + index + 1
            processedBytes += item.sizeBytes
            if (done % CHECKPOINT_EVERY == 0 && done < work.size) checkpoint("Zwischenstand")
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
        if (storageStopped) {
            logLine(
                "ANGEHALTEN: Im Cache ist zu wenig Platz fuer die naechste Datei." +
                    " Die offene Liste bleibt gespeichert und wird beim naechsten Start fortgesetzt."
            )
        } else {
            logLine(
                if (cancelledNow) "Abgebrochen nach $done von ${work.size} Dateien"
                else "Fertig: $done bearbeitet, $skipped uebersprungen, $failed fehlgeschlagen"
            )
        }
        checkpoint("Endstand")
        if (MemoryGuard.pauseCount() > 0) {
            Log.w(
                "Ram",
                "Speicherdruck im Lauf: ${MemoryGuard.pauseCount()} Drosselungen," +
                    " ${MemoryGuard.waitedMs()} ms gewartet · ${MemoryGuard.snapshot()}"
            )
        }

        // Journal aufraeumen: abgeschlossen oder bewusst abgebrochen heisst,
        // es gibt nichts fortzusetzen. Bei Speichermangel bleibt es stehen -
        // dann nimmt der naechste Start die Dateiliste wieder auf.
        if (!storageStopped) {
            journal?.finish(if (cancelledNow) "abgebrochen" else "fertig")
            journal = null
        } else {
            journal?.close()
            journal = null
        }
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
     * Oeffnet das Journal und entscheidet, ob der Lauf fortgesetzt wird.
     *
     * Fortgesetzt wird nur, wenn eine frueher angefangene Liste zum
     * selben Ordner gehoert, nicht abgeschlossen wurde und nicht aelter
     * als einen Tag ist. Alles andere wird verworfen - eine Woche alte
     * Liste gegen ein Geraet, dessen Dateien sich seither geaendert
     * haben, waere schlimmer als ein neuer Anfang.
     */
    private fun openJournal(): RunJournal.Snapshot? {
        val opened = RunJournal.open(context)
        journal = opened
        val existing = RunJournal.load(context)
        val reason = when {
            existing == null -> "keins vorhanden"
            existing.finished -> "war abgeschlossen"
            existing.treeUri != treeUri.toString() -> "gehoert zu einem anderen Ordner"
            existing.ageMs() > RunJournal.MAX_RESUME_AGE_MS ->
                "zu alt (letzte Aenderung vor ${existing.ageMs() / 1000 / 60} min)"
            else -> null
        }
        if (reason != null) {
            if (existing != null) Log.i("Job", "Journal verworfen: $reason")
            opened.start(treeUri.toString(), items.map { it.docId })
            if (!opened.isOpen) {
                Log.w("Job", "Journal liess sich nicht anlegen - ein Abbruch kann nicht fortgesetzt werden")
                journal = null
            }
            return null
        }
        if (!opened.resume()) {
            Log.w("Job", "Journal liess sich nicht oeffnen - der Lauf beginnt von vorn")
            return null
        }
        Log.i(
            "Job",
            "Journal gefunden: ${existing?.describe()} · angelegt ${existing?.startedAt}," +
                " zuletzt ${existing?.updatedAt} · ${existing?.remaining?.size} Dateien offen"
        )
        return existing
    }

    /**
     * Prueft vor jeder Datei den Platz im Cache. Ohne diese Pruefung
     * scheitert die Datei mitten im Schreiben, und bei In-Place-Ersetzen
     * waere das ein Risiko fuer das Original: die Rueckfallebene braucht
     * Platz, sonst gibt es keine.
     */
    private fun ensureStorage(item: MediaItem, index: Int, total: Int): Boolean {
        val free = context.cacheDir.usableSpace
        val needed = item.sizeBytes + STORAGE_RESERVE
        if (free >= needed) {
            if (free < needed * 2) {
                Log.w(
                    "Job",
                    "Platz wird knapp: ${formatSize(free)} frei, ${formatSize(needed)} fuer die naechste Datei nötig"
                )
            }
            return true
        }
        Log.e(
            "Job",
            "angehalten bei Datei ${index + 1}/$total (${item.displayName})" +
                " · Cache voll: ${formatSize(free)} frei, ${formatSize(needed)} gebraucht" +
                " (Datei ${formatSize(item.sizeBytes)} + Reserve ${formatSize(STORAGE_RESERVE)})"
        )
        return false
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
        val marker = if (settings.ignoreCompressionTag) "Marker ignorieren" else "Marker beachten"
        return "$video, Bilder Q${settings.imageQuality}, $output, $marker"
    }

    private fun process(item: MediaItem, index: Int) {
        fileStartedAt = System.currentTimeMillis()
        // Der Marker wird hier gehalten, damit [deliver] den Lauf-Zaehler
        // fortschreiben kann, statt bei 1 zu beginnen.
        sourceTag = tagStore.read(item.uri)
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

        // Drosselklappe fuer den Transcoder: bei Speicherdruck wartet die
        // Bildschleife kurz, statt dass Android den Prozess beendet.
        val throttle: () -> Unit = {
            if (MemoryGuard.level() != MemoryGuard.Level.OK) {
                MemoryGuard.pauseBefore { cancelled.get() }
            }
        }
        fun runTranscode(planToUse: VideoPlan) = transcoder.transcode(
            uri = item.uri,
            target = temp,
            plan = planToUse,
            durationMs = info.durationMs,
            orientationHint = info.rotation,
            onProgress = { publish(index, item.displayName, it) },
            isCancelled = { cancelled.get() },
            throttle = throttle
        )

        val actual = try {
            runTranscode(plan)
        } catch (t: Throwable) {
            // Ein Codec, der einen Plan nicht mag, ist ein Geraetedetail:
            // derselbe Film mit H.264 geht fast immer. Einmal versuchen,
            // nicht in einer Schleife - zwei Laeufe ueber dieselbe Datei
            // kosten doppelt so viel Zeit wie einer.
            val retry = t is MediaCodec.CodecException &&
                plan.codecMime != PresetEngine.MIME_AVC &&
                !cancelled.get()
            if (!retry) throw t
            Log.w(
                "Job",
                "${item.displayName} · Encoder meldet ${t.javaClass.simpleName}: ${t.message}" +
                    " · zweiter Versuch mit H.264 statt H.265"
            )
            logLine("HINWEIS ${item.displayName}: H.265 scheiterte am Encoder, neuer Versuch mit H.264")
            temp.delete()
            runTranscode(plan.copy(codecMime = PresetEngine.MIME_AVC))
        }
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

    /**
     * Prueft eine abgelegte Datei: Container, Groesse, Marker und bei
     * Videos die Spur. Die Spur-Pruefung geht ueber den echten Extraktor -
     * ein Container, den er nicht oeffnen kann, ist kaputt, egal was der
     * Muxer beim Schreiben gemeldet hat.
     */
    private fun verifyAtPlace(uri: Uri, mime: String, expectedBytes: Long) = Verifier.verifyDocument(
        resolver = resolver,
        uri = uri,
        expectMime = mime,
        expectedBytes = expectedBytes,
        expectTag = true,
        probeTracks = { Verifier.trackProblem(resolver, it) }
    )

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

        // Marker schreiben, bevor die Datei an ihren Platz geht: danach ist
        // die Datei in der Galerie und darf nicht mehr halbfertig sein.
        val tag = CompressionTag(
            runs = (sourceTag?.runs ?: 0) + 1,
            // Bei einem zweiten Lauf ist die "Quelle" das Original von
            // damals - sonst wuerde die Historie bei jedem Lauf auf die
            // zuletzt gelesene Groesse zusammenschrumpfen.
            sourceBytes = sourceTag?.sourceBytes?.takeIf { it > 0 } ?: item.sizeBytes,
            resultBytes = after,
            atMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME
        )
        val tagProblem = TagFile.write(temp, tag)
        if (tagProblem != null) {
            failed++
            logLine("FEHLER ${item.displayName}: Marker nicht schreibbar ($tagProblem) - Datei unangetastet")
            Log.e(
                "Job",
                "fehlgeschlagen: ${item.displayName} · Marker nicht schreibbar: $tagProblem" +
                    " · Quelle unveraendert · ${formatSize(after)} Ergebnis verworfen"
            )
            return
        }
        val report = Verifier.verifyFile(temp, mime, expectTag = true)
        if (!report.passed) {
            failed++
            logLine("FEHLER ${item.displayName}: Pruefung der Ausgabe (${report.reason()}) - Datei unangetastet")
            Log.e(
                "Job",
                "fehlgeschlagen: ${item.displayName} · Pruefung der Ausgabe: ${report.reason()}" +
                    " · Quelle unveraendert · Ergebnis ${formatSize(after)} verworfen"
            )
            return
        }
        Log.i(
            "Job",
            "${item.displayName} · Ausgabe geprueft: ${report.summary()} · Marker ${tag.describe()}"
        )

        val replaceInPlace = settings.outputMode == OutputMode.OVERWRITE && inPlaceSafe
        val target: Uri?
        if (replaceInPlace) {
            target = item.uri
            Log.i(
                "Job",
                "${item.displayName} · schreibe in das Original (In-Place moeglich:" +
                    " Endung ${item.extension} passt zu $mime) mit Rueckfallebene"
            )
            when (val outcome = safeReplace.replace(
                resolver = resolver,
                target = target,
                payload = temp,
                sourceSize = item.sizeBytes,
                expectedSize = after,
                label = item.displayName,
                verifyTarget = { verifyAtPlace(item.uri, mime, after).reason() }
            )) {
                is SafeReplace.Outcome.Written ->
                    Log.i("Job", "${item.displayName} · Inhalt am Platz geprueft ($mime, ${formatSize(after)})")
                is SafeReplace.Outcome.Restored -> {
                    failed++
                    logLine(
                        "FEHLER ${item.displayName}: Schreiben abgebrochen (${outcome.reason}) - " +
                            "Original wiederhergestellt, es ist unveraendert"
                    )
                    return
                }
                is SafeReplace.Outcome.Refused -> {
                    failed++
                    logLine(
                        "FEHLER ${item.displayName}: nicht ersetzt - ${outcome.reason}."
                    )
                    Log.w("Job", "${item.displayName} · nicht ersetzt · ${outcome.reason}")
                    return
                }
                is SafeReplace.Outcome.Damaged -> {
                    failed++
                    logLine(
                        "FEHLER ${item.displayName}: Schreiben abgebrochen (${outcome.reason}) - " +
                            "Original ist beschaedigt, bitte aus einer Sicherung ersetzen"
                    )
                    Log.e(
                        "Job",
                        "DATENVERLUST-RISIKO ${item.displayName} · ${outcome.reason}" +
                            " · keine oder fehlgeschlagene Rueckfallebene" +
                            " · Quelle ${formatSize(item.sizeBytes)} · Zielgroesse ${formatSize(after)}"
                    )
                    return
                }
            }
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
            // Bei einer neuen Datei ist eine Beanstandung kein Datenverlust,
            // aber eine kaputte Datei in der Galerie. Sie wird entfernt,
            // statt sie als Erfolg zu zaehlen.
            val written = verifyAtPlace(target, mime, after)
            Log.i("Job", "${item.displayName} · neue Datei geprueft: ${written.summary()}")
            if (!written.passed) {
                val removed = runCatching { DocumentsContract.deleteDocument(resolver, target) }
                    .getOrDefault(false)
                failed++
                logLine(
                    "FEHLER ${item.displayName}: geschriebene Datei ist nicht in Ordnung" +
                        " (${written.reason()})" +
                        if (removed) " - sie wurde wieder entfernt" else " - bitte von Hand loeschen"
                )
                Log.e(
                    "Job",
                    "fehlgeschlagen: ${item.displayName} · Pruefung am Ziel: ${written.reason()}" +
                        " · entfernt=$removed · Original unveraendert"
                )
                return
            }
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
        // Bezug ist die gesamte Auswahl, nicht die Restliste: bei einem
        // fortgesetzten Lauf sprang der Balken sonst auf 0 zurueck und
        // zaehlte nur die noch offenen Dateien.
        val total = items.size
        val doneBefore = resumeBase + index
        val overall = if (total <= 0) 0f else (doneBefore + fileProgress) / total
        JobBus.publish(
            JobBus.state.copy(
                running = true,
                total = total,
                done = doneBefore,
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

        /**
         * Platz, der im Cache neben der Datei selbst frei bleiben muss:
         * Platz fuer die Rueckfallebene beim In-Place-Ersetzen und fuer
         * die Ausgabedatei. 128 MB ist die Untergrenze; grosse Videos
         * brauchen mehr, dafuer sorgt die Groesse der Datei selbst.
         */
        private const val STORAGE_RESERVE = 128L * 1024 * 1024

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
