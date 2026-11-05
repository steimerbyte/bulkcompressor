package com.steimerbyte.bulkcompressor

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import com.steimerbyte.bulkcompressor.job.CompressionJob
import com.steimerbyte.bulkcompressor.job.CompressionService
import com.steimerbyte.bulkcompressor.job.JobBus
import com.steimerbyte.bulkcompressor.log.DownloadsLog
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.log.LogLevel
import com.steimerbyte.bulkcompressor.log.LogLine
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.ImageFormat
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.OutputMode
import com.steimerbyte.bulkcompressor.model.ScanResult
import com.steimerbyte.bulkcompressor.model.VideoCodec
import com.steimerbyte.bulkcompressor.model.VideoMode
import com.steimerbyte.bulkcompressor.scan.FolderPath
import com.steimerbyte.bulkcompressor.scan.SafScanner
import com.steimerbyte.bulkcompressor.stats.History
import com.steimerbyte.bulkcompressor.stats.HistoryReader
import com.steimerbyte.bulkcompressor.stats.StatsActivity
import com.steimerbyte.bulkcompressor.ui.Accent
import com.steimerbyte.bulkcompressor.ui.AboutPanel
import com.steimerbyte.bulkcompressor.ui.AppTheme
import com.steimerbyte.bulkcompressor.ui.Bar
import com.steimerbyte.bulkcompressor.ui.Body
import com.steimerbyte.bulkcompressor.ui.Chip
import com.steimerbyte.bulkcompressor.ui.Dim
import com.steimerbyte.bulkcompressor.ui.Good
import com.steimerbyte.bulkcompressor.ui.HomeState
import com.steimerbyte.bulkcompressor.ui.Hint
import com.steimerbyte.bulkcompressor.ui.Line
import com.steimerbyte.bulkcompressor.ui.LivePanel
import com.steimerbyte.bulkcompressor.ui.MediaFilter
import com.steimerbyte.bulkcompressor.ui.Micro
import com.steimerbyte.bulkcompressor.ui.MiniButton
import com.steimerbyte.bulkcompressor.ui.Mono
import com.steimerbyte.bulkcompressor.ui.Osw
import com.steimerbyte.bulkcompressor.ui.OutlineBox
import com.steimerbyte.bulkcompressor.ui.Panel
import com.steimerbyte.bulkcompressor.ui.Pill
import com.steimerbyte.bulkcompressor.ui.SearchField
import com.steimerbyte.bulkcompressor.ui.SoundKit
import com.steimerbyte.bulkcompressor.ui.SummaryPanel
import com.steimerbyte.bulkcompressor.ui.Toggle
import com.steimerbyte.bulkcompressor.ui.Warn
import com.steimerbyte.bulkcompressor.ui.WideButton
import com.steimerbyte.bulkcompressor.ui.runStatusLabel
import com.steimerbyte.bulkcompressor.ui.scanMarkerNote
import com.steimerbyte.bulkcompressor.ui.scanNotes
import com.steimerbyte.bulkcompressor.ui.startControlLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ein Bildschirm, ein Zustand: Ordner waehlen, Dateien auswaehlen, Lauf
 * starten. Die Darstellung kommt aus dem NothingOS-Layoutsystem
 * (ui/Nothing.kt), der Zustand aus [HomeState], die Arbeit aus dem
 * Vordergrunddienst.
 */
class MainActivity : ComponentActivity(), JobBus.Listener {

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val historyReaderDelegate = lazy { HistoryReader(applicationContext) }
    private val historyReader by historyReaderDelegate

    /** Der Bildschirmzustand; die Composition legt ihn an und haengt ihn hier ein. */
    private var homeState: HomeState? = null

    /** Laufender Auftrag, wie ihn der Dienst meldet. */
    internal var job by mutableStateOf(JobBus.state)
    internal var logLines by mutableStateOf<List<LogLine>>(emptyList())
    internal var history by mutableStateOf(History())
    internal var cancelling by mutableStateOf(false)
    internal var startPending by mutableStateOf(false)
    internal var aboutOpen by mutableStateOf(false)
    internal var logOpen by mutableStateOf(false)
    internal var resultOpen by mutableStateOf(false)
    internal var externalAccess by mutableStateOf(false)

    internal var cpuHist by mutableStateOf<List<Float>>(emptyList())
    internal var ramHist by mutableStateOf<List<Float>>(emptyList())
    internal var cpuValue by mutableStateOf("")
    internal var ramValue by mutableStateOf("")

    private var startAttempts = 0
    private var wasRunning = false

    /**
     * Laufende Nummer des Ordner-Scans. Ein neuer Start entwertet den
     * vorigen: dessen Ergebnis darf die Anzeige nicht mehr ueberschreiben.
     */
    @Volatile
    private var scanGeneration = 0

    private val logListener: (LogLine) -> Unit = { line ->
        main.post { logLines = (logLines + line).takeLast(TERMINAL_LINES) }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchJob() }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(
                TAG,
                if (granted) "Schreibrecht erteilt - Logdatei liegt in ${DownloadsLog.folderPath()}"
                else "Ohne Schreibrecht bleibt die Logdatei im app-privaten Speicher"
            )
            if (granted) Log.retryDownloadLog(this)
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            SoundKit.play(SoundKit.Click.TICK)
            home().folder = uri
        }

    // ------------------------------------------------------------------
    // Lebenszyklus
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(CompressSettings.PREFS, MODE_PRIVATE)
        SoundKit.init(this, CompressSettings.load(prefs).clickSounds)
        setContent { AppTheme { Screen() } }
        requestLegacyStoragePermission()
        restoreFolder()
        resumeInterruptedRun()
    }

    override fun onStart() {
        super.onStart()
        JobBus.addListener(this)
        Log.addListener(logListener)
        logLines = Log.snapshot(TERMINAL_LINES)
        refreshHistory()
    }

    override fun onStop() {
        // Der Auftrag laeuft im Dienst weiter, der Ton aber meldet einen
        // Zustand dieser Oberflaeche - im Hintergrund waere er ein Summen
        // aus der Hosentasche.
        SoundKit.stopJobHum()
        JobBus.removeListener(this)
        Log.removeListener(logListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Nach dem Rueckweg aus den Einstellungen kann das Recht jetzt
        // vorliegen - die Zeile verschwindet dann sofort.
        externalAccess = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        // Die Systemeinstellung fuer Beruehrungsklaenge kann sich
        // waehrend der Laufzeit geaendert haben.
        SoundKit.refresh(this)
        if (JobBus.state.running) SoundKit.startJobHum() else SoundKit.stopJobHum()
    }

    override fun onDestroy() {
        if (historyReaderDelegate.isInitialized()) historyReader.shutdown()
        SoundKit.stopScanHum()
        SoundKit.stopJobHum()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Zustand
    // ------------------------------------------------------------------

    /** Der Zustand des Bildschirms; vor der ersten Composition angelegt. */
    internal fun home(): HomeState =
        homeState ?: HomeState(CompressSettings.load(prefs), ::persistSettings)
            .also { homeState = it }

    private fun persistSettings(settings: CompressSettings) {
        settings.save(prefs)
        Log.d(TAG, "Einstellung geaendert: ${describeSettings(settings)}")
        SoundKit.setEnabled(settings.clickSounds)
    }

    /**
     * Ein vom System beendeter Lauf wird beim Oeffnen der App
     * fortgesetzt - sonst waere ein Stundenlauf nach einem Prozessabbruch
     * stillschweigend verloren.
     */
    private fun resumeInterruptedRun() {
        val snapshot = CompressionService.resumeIfInterrupted(this) ?: return
        toast("Lauf wird fortgesetzt: ${snapshot.doneCount} von ${snapshot.total} erledigt")
        Log.i(TAG, "Wiederaufnahme gestartet: ${snapshot.describe()}")
    }

    /** Freigabe-Token aus den Preferences: derselbe Ordner, ohne Systemdialog. */
    private fun restoreFolder() {
        val stored = prefs.getString(KEY_TREE, null) ?: return
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return
        val permitted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!permitted) {
            home().scanError = "Zugriff auf den Ordner ist abgelaufen. Bitte neu waehlen."
            return
        }
        home().folder = uri
    }

    // ------------------------------------------------------------------
    // Ordner lesen
    // ------------------------------------------------------------------

    /**
     * Liest den Baum. Laeuft als Coroutine der Composition: ein neuer
     * Ordner oder "Neu scannen" bricht den vorigen ab, und die laufende
     * Nummer haelt Spaetzuender aus der Anzeige.
     */
    private suspend fun scanFolder(state: HomeState) {
        val uri = state.folder ?: return
        // Der Test setzt Scanergebnisse ohne ContentProvider; ohne diese
        // Sperre wuerde der Effekt sie sofort wieder ueberschreiben.
        if (state.scanningDisabled) return
        val respectTag = !state.settings.ignoreCompressionTag
        state.scanning = true
        state.scanLive = ""
        state.scanError = ""
        state.scanFolders = 0
        state.scanFiles = 0
        val generation = ++scanGeneration
        SoundKit.startScanHum()
        SoundKit.play(SoundKit.Click.SCAN_START)
        Log.i(TAG, "Ordner-Scan startet: $uri")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                SafScanner(contentResolver).scan(
                    uri,
                    // "Marker beachten" ist die Vorgabe: was schon einen
                    // Marker traegt, kommt gar nicht erst auf die Liste.
                    respectTag = respectTag,
                    cancelled = { generation != scanGeneration }
                ) { progress ->
                    main.post {
                        // Ein abgeloester oder beendeter Scan darf die
                        // Anzeige nicht mehr anfassen: seine letzte Meldung
                        // liegt sonst nach dem Ergebnis im Zustand.
                        if (generation == scanGeneration && state.scanning) {
                            state.scanFolders = progress.folders
                            state.scanFiles = progress.files
                            state.scanLive = progress.live
                        }
                    }
                }
            }
        }
        if (generation != scanGeneration) return
        // Erst das Summen beenden, dann den Abschluss-Schlag: der Ton
        // markiert das Ende, er soll nicht ueber dem Dauerton liegen.
        SoundKit.stopScanHum()
        result.onSuccess { scan ->
            SoundKit.play(SoundKit.Click.SCAN_DONE)
            state.settleScan(scan)
            Log.i(
                TAG,
                "Scan fertig: ${scan.items.size} Dateien in ${scan.folderCount} Ordnern" +
                    " · ${scan.skippedTagged} bereits komprimiert"
            )
        }.onFailure {
            Log.w(TAG, "Ordner-Scan fehlgeschlagen: $uri", it)
            state.failScan(it.message ?: it.toString())
        }
    }

    // ------------------------------------------------------------------
    // Lauf
    // ------------------------------------------------------------------

    /**
     * Android 7-9 haben kein MediaStore.Downloads: ohne
     * WRITE_EXTERNAL_STORAGE liegt die Logdatei nur im app-privaten
     * Speicher. Ab Android 10 ist der Aufruf wirkungslos.
     */
    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= 29) return
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        Log.i(TAG, "Frage Schreibrecht fuer den Download-Ordner an (Android ${Build.VERSION.RELEASE})")
        legacyStoragePermission.launch(permission)
    }

    /** Der eine Knopf: Start, Stopp, Abbruch. */
    internal fun onStartControl() {
        SoundKit.play(SoundKit.Click.THUNK)
        if (job.running) {
            cancelling = true
            Log.i(TAG, "Abbruch ueber den Startknopf angefordert")
            CompressionService.cancel(this)
            return
        }
        val state = home()
        val uri = state.folder
        if (uri == null) {
            toast("Kein Ordner gewaehlt")
            return
        }
        if (state.selected.isEmpty()) {
            toast("Keine Datei ausgewaehlt")
            return
        }
        state.settings.save(prefs)
        Log.i(
            TAG,
            "Start angefordert: ${state.selected.size} Dateien · ${describeSettings(state.settings)}"
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchJob()
    }

    private fun launchJob() {
        val state = home()
        val uri = state.folder ?: return
        cancelling = false
        resultOpen = false
        startAttempts = 0
        startPending = true
        JobBus.reset()
        CompressionService.start(this, uri, state.selected.toList())
        // Der Dienst meldet sich sofort mit "laeuft". Bleibt das aus, hat er
        // den Auftrag verworfen - dann wird einmal nachgestartet und danach
        // aufgegeben, statt einen toten Knopf zu hinterlassen.
        main.removeCallbacks(startCheck)
        main.postDelayed(startCheck, START_CHECK_MS)
    }

    private val startCheck = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val state = JobBus.state
            if (state.running || state.finished || state.cancelled) return
            val uri = home().folder
            val ids = home().selected.toList()
            if (uri == null || ids.isEmpty()) return
            if (startAttempts >= MAX_START_ATTEMPTS) {
                Log.e(TAG, "Auftrag nach $MAX_START_ATTEMPTS Versuchen nicht angenommen - aufgegeben")
                JobBus.appendLog("Auftrag kam nicht an: $MAX_START_ATTEMPTS Versuche ohne Wirkung")
                startPending = false
                toast("Auftrag kam nicht an")
                return
            }
            startAttempts++
            Log.w(TAG, "Auftrag nach ${START_CHECK_MS} ms nicht angenommen - Versuch $startAttempts")
            CompressionService.start(this@MainActivity, uri, ids)
            main.postDelayed(this, START_CHECK_MS)
        }
    }

    override fun onState(state: JobBus.State) {
        val apply = {
            job = state
            if (state.running) {
                cancelling = false
                startPending = false
                SoundKit.startJobHum()
            } else {
                SoundKit.stopJobHum()
                if (state.finished || state.cancelled) {
                    cancelling = false
                    startPending = false
                }
            }
            // Eine fertige Datei bekommt ihre Nachher-Groesse in der Liste.
            if (state.lastDocId.isNotEmpty()) {
                home().afterBytes[state.lastDocId] = state.lastAfterBytes
            }
            if (wasRunning && !state.running) {
                refreshHistory()
                resultOpen = state.done > 0
            }
            wasRunning = state.running
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else main.post(apply)
    }

    /** Liest die Laufhistorie im Hintergrund; gezeichnet wird auf dem Haupt-Thread. */
    private fun refreshHistory() {
        historyReader.read { loaded ->
            if (isFinishing || isDestroyed) return@read
            history = loaded
        }
    }

    /**
     * Nur fuer Tests: wartet auf das Ende des Lesers. Die Activity liest im
     * Hintergrund, ein Test kann deshalb nicht einfach nachsehen.
     */
    internal fun awaitHistoryForTest(timeoutMs: Long = 2000) {
        historyReader.awaitForTest(timeoutMs)
    }

    /**
     * Nur fuer Tests: derselbe Scan wie im LaunchedEffect, ohne auf einen
     * Frame der Composition zu warten.
     */
    internal fun startScanForTest() {
        lifecycleScope.launch { scanFolder(home()) }
    }

    /** Nur fuer Tests: Ordner und Scanergebnis ohne echten ContentProvider. */
    internal fun applyScanForTest(tree: Uri, result: ScanResult) {
        val state = home()
        state.scanningDisabled = true
        state.folder = tree
        state.settleScan(result)
    }

    /** Nur fuer Tests: die Beschriftung, die der Startknopf traegt. */
    internal fun startLabel(): String = startControlLabel(
        running = job.running,
        pending = startPending,
        cancelling = cancelling,
        ready = home().folder != null && home().selected.isNotEmpty()
    )

    internal fun startEnabled(): Boolean = job.running || startLabel() == "Start"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun describeSettings(settings: CompressSettings): String = buildString {
        append("Videos ${settings.videoMode} ${settings.bitratePercent} %")
        if (settings.videoShortEdge > 0) append(" ${settings.videoShortEdge}p")
        append(" ${settings.codec}")
        append(" · Bilder Q${settings.imageQuality} ")
        append(if (settings.imageShortEdge > 0) "${settings.imageShortEdge}px" else "original")
        append(' ').append(settings.imageFormat)
        append(" · ").append(settings.outputMode)
        append(" · Zeitstempel ").append(if (settings.keepTimestamps) "behalten" else "neu")
        append(" · EXIF/GPS ").append(if (settings.keepExif) "behalten" else "verwerfen")
        append(" · Klick ").append(if (settings.clickSounds) "an" else "aus")
        append(" · Marker ").append(if (settings.ignoreCompressionTag) "ignorieren" else "beachten")
    }

    // ------------------------------------------------------------------
    // CPU und RAM
    // ------------------------------------------------------------------

    /**
     * CPU-Zeit des eigenen Prozesses aus `/proc/self/stat` (utime+stime).
     *
     * `/proc/stat` ist fuer Apps auf Android 17 nicht lesbar (Permission
     * denied), der Systemtakt also nicht messbar. Der eigene Prozess ist
     * hier ohnehin der richtige Wert: die Kompression laeuft in ihm.
     */
    private fun readProcessTicks(): Long? {
        val text = runCatching { File("/proc/self/stat").readText() }.getOrNull() ?: return null
        // Der Kommandoname steht in Klammern und darf Leerzeichen enthalten;
        // ab dem letzten ") " sind die Felder stabil (Feld 3 beginnt dort).
        val fields = text.substringAfterLast(") ").trim().split(" ")
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    private fun readMem(): Pair<Long, Long>? {
        val text = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return null
        val total = Regex("(?m)^MemTotal:\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val avail = Regex("(?m)^MemAvailable:\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (total == null || avail == null || total <= 0) return null
        return total to avail
    }

    /**
     * Ein Wert alle 700 ms, 48 Werte: die Pflicht-Histogramme aus dem
     * Layoutsystem. Der Takt laeuft nur, solange der Bildschirm sichtbar
     * ist - im Hintergrund waere er Stromverbrauch ohne Betrachter.
     */
    private suspend fun sampleLoop() = withContext(Dispatchers.IO) {
        var previous = runCatching { readProcessTicks() }.getOrNull()
        var previousAt = SystemClock.elapsedRealtime()
        while (isActive) {
            delay(SAMPLE_INTERVAL_MS)
            val ticks = runCatching { readProcessTicks() }.getOrNull()
            val mem = runCatching { readMem() }.getOrNull()
            val now = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main.immediate) {
                val before = previous
                if (before != null && ticks != null) {
                    // 100 Ticks je Sekunde (USER_HZ), ein Kern = 100 %.
                    val seconds = (now - previousAt) / 1000.0
                    if (seconds > 0) {
                        val load = ((ticks - before) / (seconds * 100.0)).toFloat()
                            .coerceIn(0f, 1f)
                        cpuHist = (cpuHist + load).takeLast(HISTORY_SLOTS)
                        cpuValue = "${(load * 100).roundToInt()} %"
                    }
                }
                if (mem != null) {
                    val totalGiB = mem.first / 1048576.0
                    val usedGiB = (mem.first - mem.second) / 1048576.0
                    ramHist = (ramHist + (usedGiB / totalGiB).toFloat()).takeLast(HISTORY_SLOTS)
                    ramValue = String.format(Locale.GERMANY, "%.1f/%.1f", usedGiB, totalGiB)
                }
            }
            if (ticks != null) {
                previous = ticks
                previousAt = now
            }
        }
    }

    // ------------------------------------------------------------------
    // Oberflaeche
    // ------------------------------------------------------------------

    @Composable
    private fun Screen() {
        val state = rememberSaveable(saver = HomeState.saver(CompressSettings.load(prefs), ::persistSettings)) {
            home()
        }
        // Die Activity braucht denselben Zustand fuer Ordnerwahl und Start.
        SideEffect { homeState = state }

        // Ordner lesen: neuer Ordner, neuer Auftrag an die Coroutine.
        LaunchedEffect(state.folder, state.scanToken) { scanFolder(state) }

        // Takt der Histogramme, nur im Vordergrund.
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { sampleLoop() }
        }

        // Laufende Dauer: der Zustand allein meldet nur bei Dateiwechsel.
        var tick by remember { mutableStateOf(0L) }
        LaunchedEffect(job.running) {
            while (job.running) {
                tick = System.currentTimeMillis()
                delay(1000)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = "NOTHING OS · BILD-KOMPRESSION",
                title = "Bulk Compressor",
                action = { MiniButton("LOG") { logOpen = true } },
                onKickerClick = { aboutOpen = true }
            )
            Spacer(Modifier.height(12.dp))

            val rows = state.rows()
            val scan = state.scan
            // Querformat hat Hoehe fuer eine Zeile Panels, nicht fuer zwei
            // untereinander: dort stehen sie nebeneinander.
            val landscape = LocalConfiguration.current.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            val summary = listOf(
                "DATEIEN" to if (job.running || job.total > 0) {
                    "${job.done} / ${job.total}"
                } else {
                    "${state.selected.size} / ${scan?.items?.size ?: 0}"
                },
                "EINGESPART" to CompressionJob.formatSize(job.savedBytes),
                "DAUER" to formatDuration(
                    if (job.running) {
                        job.startedAt.takeIf { it > 0 }?.let { (tick - it).coerceAtLeast(0) } ?: 0
                    } else {
                        job.elapsedMs
                    }
                )
            )
            LazyColumn(Modifier.weight(1f)) {
                item {
                    if (landscape) {
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                LivePanel(
                                    cpuHist, ramHist, cpuValue, ramValue,
                                    cpuLabel = "CPU · PROZESS"
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) { SummaryPanel(summary) }
                        }
                    } else {
                        LivePanel(
                            cpuHist, ramHist, cpuValue, ramValue,
                            // Ohne Shizuku ist /proc/stat fuer Apps gesperrt.
                            cpuLabel = "CPU · PROZESS"
                        )
                        Spacer(Modifier.height(12.dp))
                        SummaryPanel(summary)
                    }
                    Spacer(Modifier.height(8.dp))
                    StatusLines(state)
                    Spacer(Modifier.height(10.dp))
                    FolderRow(state)
                    Spacer(Modifier.height(4.dp))
                    SearchField(state.query, { state.query = it }, rows.size)
                    Spacer(Modifier.height(2.dp))
                    FilterRow(state, rows.size)
                    Spacer(Modifier.height(4.dp))
                    PresetBlock(state)
                    Spacer(Modifier.height(8.dp))
                    if (rows.isNotEmpty()) SelectionRow(state, scan)
                }
                items(rows) { item -> MediaRow(state, item) }
                if (rows.isEmpty()) {
                    item { EmptyState(state, scan) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            ActionBar(state)
        }

        if (aboutOpen) AboutDialog { aboutOpen = false }
        if (logOpen) LogDialog { logOpen = false }
        if (resultOpen) ResultDialog { resultOpen = false }
    }

    // ---- Bausteine des Bildschirms ----

    @Composable
    private fun StatusLines(state: HomeState) {
        val scan = state.scan
        when {
            state.scanError.isNotBlank() -> {
                Text(
                    state.scanError, color = Warn, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            state.scanning -> {
                Micro(
                    if (state.scanFolders == 0) "LÄDT ORDNER …"
                    else "LÄDT · ${state.scanFolders} ORDNER · ${state.scanFiles} DATEIEN",
                    Warn
                )
                if (state.scanLive.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Mono(state.scanLive)
                }
            }
            scan != null -> {
                Micro(
                    "${scan.videoCount} VIDEOS · ${scan.imageCount} BILDER · " +
                        CompressionJob.formatSize(scan.totalBytes)
                )
                if (scan.markersUnreadable > 0) {
                    Spacer(Modifier.height(2.dp))
                    Micro(scanMarkerNote(scan), Warn)
                }
                val notes = scanNotes(scan)
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Micro(notes, Warn)
                }
            }
            state.folder != null -> Micro("ORDNER GEWÄHLT · NICHT GELESEN")
            else -> Body(
                "Wähle einen Ordner. Android fragt einmal nach dem Zugriff, danach " +
                    "liest die App ihn ohne Dialog neu."
            )
        }
        // Ohne "Zugriff auf alle Dateien" laesst sich der Zeitstempel nicht
        // zuruecksetzen. Die Zeile steht nur, wenn es etwas zu holen gibt.
        if (Build.VERSION.SDK_INT >= 30 && state.settings.keepTimestamps && !externalAccess) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Micro("ZEITSTEMPEL OHNE RECHT · NEU DATIERT", Warn)
                Spacer(Modifier.width(10.dp))
                MiniButton("RECHT GEBEN") { requestAllFilesAccess() }
            }
        }
    }

    @Composable
    private fun FolderRow(state: HomeState) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniButton("ORDNER WÄHLEN", Modifier.weight(1f)) {
                SoundKit.play(SoundKit.Click.TICK)
                runCatching { folderPicker.launch(null) }
                    .onFailure { toast("Keine Ordnerauswahl gefunden") }
            }
            MiniButton(
                "NEU SCANNEN",
                Modifier.weight(1f),
                enabled = state.folder != null && !state.scanning
            ) {
                SoundKit.play(SoundKit.Click.TICK)
                if (state.folder == null) {
                    runCatching { folderPicker.launch(null) }
                } else {
                    state.scanToken++
                }
            }
            MiniButton("STATISTIK", Modifier.weight(1f)) {
                SoundKit.play(SoundKit.Click.TICK)
                runCatching { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
                    .onFailure { Log.w(TAG, "Statistikseite nicht zu oeffnen", it) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Mono(
            state.folder?.let { FolderPath.display(it) } ?: "KEIN ORDNER",
            if (state.folder == null) Dim else Color.White.copy(alpha = 0.8f)
        )
        if (history.totals.runs > 0) {
            Mono(
                "BISHER ${history.totals.runs} LÄUFE · " +
                    CompressionJob.formatSize(history.totals.savedBytes) + " GESPART"
            )
        }
    }

    @Composable
    private fun FilterRow(state: HomeState, hits: Int) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MediaFilter.entries.forEach { filter ->
                Pill(filter.label, state.filter == filter) {
                    SoundKit.play(SoundKit.Click.TICK)
                    state.filter = filter
                }
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (state.scan != null) Micro("$hits VON ${state.scan?.items?.size ?: 0} DATEIEN")
        }
    }

    @Composable
    private fun SelectionRow(state: HomeState, scan: ScanResult?) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Micro("AUSWAHL ${state.selected.size} / ${scan?.items?.size ?: 0}")
            Spacer(Modifier.weight(1f))
            MiniButton("ALLE") {
                SoundKit.play(SoundKit.Click.TICK)
                state.selectAll()
            }
            Spacer(Modifier.width(6.dp))
            MiniButton("KEINE", enabled = state.selected.isNotEmpty()) {
                SoundKit.play(SoundKit.Click.TICK)
                state.selectNone()
            }
        }
    }

    /**
     * Preset: eine Zeile, die sagt, was gilt, und auf Tippen die
     * Bedienelemente. Der Bildschirm ist voll genug von der Liste; die
     * Regler sind keine Aussage, sondern eine Entscheidung.
     */
    @Composable
    private fun PresetBlock(state: HomeState) {
        val s = state.settings
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable { state.presetOpen = !state.presetOpen },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Micro("PRESET")
            Spacer(Modifier.width(10.dp))
            Text(
                presetSummary(s), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Chip(if (state.presetOpen) "ZU" else "OFFEN", if (state.presetOpen) Dim else Accent)
        }
        if (!state.presetOpen) return
        OutlineBox {
            Micro("VIDEO · MODUS")
            Spacer(Modifier.height(4.dp))
            PillRow(
                listOf("BITRATE", "SKALA", "BEIDES"),
                s.videoMode.ordinal
            ) { index ->
                state.updateSettings(s.copy(videoMode = VideoMode.entries[index]))
            }
            if (s.videoMode != VideoMode.SCALE) {
                Spacer(Modifier.height(2.dp))
                SliderRow(
                    "BITRATE ${s.bitratePercent} %",
                    (s.bitratePercent - 5).toFloat(),
                    0f..95f
                ) { raw ->
                    state.updateSettings(s.copy(bitratePercent = 5 + raw.toInt()))
                }
            }
            if (s.videoMode != VideoMode.BITRATE) {
                Spacer(Modifier.height(2.dp))
                PillRow(
                    listOf("ORIGINAL", "2160p", "1440p", "1080p", "720p", "480p"),
                    RESOLUTION_VALUES.indexOf(s.videoShortEdge).coerceAtLeast(0)
                ) { index ->
                    state.updateSettings(s.copy(videoShortEdge = RESOLUTION_VALUES[index]))
                }
            }
            Spacer(Modifier.height(2.dp))
            PillRow(listOf("CODEC AUTO", "H.264", "H.265"), s.codec.ordinal) { index ->
                state.updateSettings(s.copy(codec = VideoCodec.entries[index]))
            }
            Spacer(Modifier.height(12.dp))
            Micro("BILDER · QUALITÄT")
            SliderRow(
                "${s.imageQuality}",
                (s.imageQuality - 40).toFloat(),
                0f..55f
            ) { raw ->
                state.updateSettings(s.copy(imageQuality = 40 + raw.toInt()))
            }
            PillRow(
                listOf("ORIGINAL", "2160p", "1440p", "1080p", "720p"),
                IMAGE_RESOLUTION_VALUES.indexOf(s.imageShortEdge).coerceAtLeast(0)
            ) { index ->
                state.updateSettings(s.copy(imageShortEdge = IMAGE_RESOLUTION_VALUES[index]))
            }
            Spacer(Modifier.height(2.dp))
            PillRow(listOf("BEHALTEN", "JPEG", "WEBP"), s.imageFormat.ordinal) { index ->
                state.updateSettings(s.copy(imageFormat = ImageFormat.entries[index]))
            }
            Spacer(Modifier.height(12.dp))
            Micro("AUSGABE")
            Spacer(Modifier.height(4.dp))
            PillRow(
                listOf("ERSETZEN", "ORIGINAL BEHALTEN"),
                if (s.outputMode == OutputMode.OVERWRITE) 0 else 1
            ) { index ->
                state.updateSettings(
                    s.copy(
                        outputMode = if (index == 0) OutputMode.OVERWRITE
                        else OutputMode.KEEP_ORIGINAL
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Micro(
                if (s.outputMode == OutputMode.OVERWRITE) "DIE DATEI WIRD AN ORT UND STELLE ERSETZT"
                else "DAS ORIGINAL BLEIBT · DIE KLEINE DATEI KOMMT DANEBEN",
                if (s.outputMode == OutputMode.OVERWRITE) Dim else Good
            )
            Spacer(Modifier.height(10.dp))
            Micro("OPTIONEN")
            Toggle("ZEITSTEMPEL BEHALTEN", s.keepTimestamps) {
                SoundKit.play(SoundKit.Click.TOGGLE)
                state.updateSettings(s.copy(keepTimestamps = it))
            }
            Toggle("EXIF UND GPS BEHALTEN", s.keepExif) {
                SoundKit.play(SoundKit.Click.TOGGLE)
                state.updateSettings(s.copy(keepExif = it))
            }
            Toggle("MARKER IGNORIEREN", s.ignoreCompressionTag) {
                SoundKit.play(SoundKit.Click.TOGGLE)
                state.updateSettings(s.copy(ignoreCompressionTag = it))
            }
            Toggle("KLICKTÖNE", s.clickSounds) {
                state.updateSettings(s.copy(clickSounds = it))
                SoundKit.play(SoundKit.Click.TOGGLE)
            }
        }
        Spacer(Modifier.height(4.dp))
        Mono(
            "SPEICHER ${MemoryGuard.snapshot()}",
            if (MemoryGuard.level() == MemoryGuard.Level.OK) Dim else Warn
        )
    }

    @Composable
    private fun PillRow(labels: List<String>, selectedIndex: Int, onPick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f)) {
                    Pill(label, index == selectedIndex) {
                        SoundKit.play(SoundKit.Click.TICK)
                        onPick(index)
                    }
                }
            }
        }
    }

    @Composable
    private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        Column {
            Micro(label)
            Slider(
                value = value,
                onValueChange = { onChange(it) },
                valueRange = range,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Line
                ),
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
        }
    }

    @Composable
    private fun MediaRow(state: HomeState, item: MediaItem) {
        val selected = item.docId in state.selected
        val after = state.afterBytes[item.docId]
        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    SoundKit.play(SoundKit.Click.TICK)
                    state.toggle(item)
                }
                .padding(vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selected) "■" else "□",
                    color = if (selected) Color.White else Dim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    item.displayName, color = Color.White, fontSize = 12.sp,
                    letterSpacing = 0.3.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    CompressionJob.formatSize(item.sizeBytes), color = Dim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1
                )
                if (after != null) {
                    Text(
                        " → " + CompressionJob.formatSize(after), color = Good, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(24.dp))
                Row(Modifier.weight(1f)) {
                    Mono(
                        (if (item.kind == MediaKind.VIDEO) "VIDEO" else "BILD") +
                            if (item.lastModified > 0) {
                                " · " + DATE_FORMAT.format(Date(item.lastModified))
                            } else {
                                ""
                            }
                    )
                }
                if (after != null && item.sizeBytes > 0) {
                    val saved = ((item.sizeBytes - after) * 100 / item.sizeBytes).toInt()
                    Chip(if (saved > 0) "−$saved %" else "GLEICH", if (saved > 0) Good else Warn)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    @Composable
    private fun EmptyState(state: HomeState, scan: ScanResult?) {
        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
            Micro(
                when {
                    scan == null -> "KEINE DATEIEN"
                    state.query.isNotBlank() -> "KEIN TREFFER FÜR \"${state.query}\""
                    else -> "KEINE DATEI IN DIESEM FILTER"
                },
                if (state.query.isNotBlank()) Warn else Dim
            )
        }
    }

    /** Der untere Block: Fortschritt und der eine Knopf. */
    @Composable
    private fun ActionBar(state: HomeState) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
            if (job.running || job.total > 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Micro(
                        runStatusLabel(job.running, job.cancelled, job.done, job.total, job.failed),
                        if (job.running || job.cancelled || (job.done > 0 && job.failed == job.done)) {
                            Warn
                        } else {
                            Good
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    if (job.beforeBytes > 0) {
                        Micro(
                            CompressionJob.formatSize(job.beforeBytes) + " → " +
                                CompressionJob.formatSize(job.afterBytes) + " · −${job.savedPercent} %",
                            Good
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Bar(if (job.running) job.overall else if (job.total > 0) 1f else 0f)
                if (job.currentName.isNotBlank() && job.running) {
                    Spacer(Modifier.height(3.dp))
                    Mono(job.currentName)
                }
                Spacer(Modifier.height(6.dp))
            }
            WideButton(
                startControlLabel(
                    running = job.running,
                    pending = startPending,
                    cancelling = cancelling,
                    ready = state.folder != null && state.selected.isNotEmpty()
                ),
                enabled = job.running || (state.folder != null && state.selected.isNotEmpty()),
                onClick = { onStartControl() }
            )
            // Im Querformat ist jede Zeile Hoehe wert; der Satz ist dort
            // keine Information, sondern ein Absatz.
            if (!job.running && job.total == 0 &&
                LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
            ) {
                Hint("JEDE DATEI EINZELN · ABBRUCH JEDERZEIT · KEIN NETZ")
            }
        }
    }

    // ---- Overlays ----

    @Composable
    private fun AboutDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)),
            text = {
                AboutPanel(
                    name = "Bulk Compressor",
                    version = "${BuildConfig.VERSION_NAME} · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    purpose = "Komprimiert Videos und Bilder eines Ordners an Ort und Stelle.",
                    commands = listOf(
                        "SafScanner · DocumentsContract",
                        "MediaCodec + MediaMuxer",
                        "ExifInterface",
                        "cat /proc/self/stat · /proc/meminfo",
                        "am start-foreground-service"
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(
                        "SCHLIESSEN", color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            }
        )
    }

    @Composable
    private fun LogDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)),
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Micro("PROTOKOLL ${logLines.size} ZEILEN")
                    Spacer(Modifier.weight(1f))
                    MiniButton("KOPIEREN") { copyLog() }
                    MiniButton("TEILEN") { shareLog() }
                    MiniButton("LEEREN") {
                        // Nur die Ansicht: die Logdatei bleibt, sonst waere
                        // das Loeschen ein Datenverlust ohne Rueckfrage.
                        logLines = emptyList()
                        Log.d(TAG, "Terminalansicht geleert, Logdatei bleibt")
                    }
                }
            },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    items(logLines) { line ->
                        Text(
                            line.format(), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            color = when (line.level) {
                                LogLevel.E -> Accent
                                LogLevel.W -> Warn
                                else -> Dim
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(
                        "SCHLIESSEN", color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            }
        )
    }

    @Composable
    private fun ResultDialog(onClose: () -> Unit) {
        AlertDialog(
            onDismissRequest = onClose,
            containerColor = Panel,
            modifier = Modifier.border(1.dp, Line, RoundedCornerShape(2.dp)),
            title = {
                Micro(
                    when {
                        job.cancelled -> "ABGEBROCHEN"
                        job.done > 0 && job.failed == job.done -> "FEHLGESCHLAGEN"
                        else -> "ERGEBNIS"
                    },
                    if (job.cancelled || (job.done > 0 && job.failed == job.done)) Warn else Good
                )
            },
            text = {
                Column {
                    SummaryPanel(
                        listOf(
                            "DATEIEN" to "${job.done} / ${job.total}",
                            "EINGESPART" to CompressionJob.formatSize(job.savedBytes),
                            "DAUER" to formatDuration(job.elapsedMs),
                            "ÜBERSPRUNGEN" to job.skipped.toString()
                        )
                    )
                    if (job.failed > 0 || job.unreadable > 0) {
                        Spacer(Modifier.height(8.dp))
                        Micro(
                            "${job.failed} FEHLER · ${job.unreadable} MARKER UNLESBAR", Warn
                        )
                    }
                    if (job.beforeBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        Mono(
                            CompressionJob.formatSize(job.beforeBytes) + " → " +
                                CompressionJob.formatSize(job.afterBytes) +
                                " · −${job.savedPercent} %"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Hint("DETAILS IM PROTOKOLL", Dim) { logOpen = true }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(
                        "SCHLIESSEN", color = Dim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp
                    )
                }
            }
        )
    }

    /** In die Zwischenablage, damit die Zeilen ohne Kabel woanders landen. */
    private fun copyLog() {
        val text = Log.exportText()
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        runCatching {
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("Bulk Compressor Log", text.take(400_000))
            )
        }.onFailure { Log.w(TAG, "Kopieren fehlgeschlagen", it) }
        toast("Log kopiert")
    }

    /** Teilen ohne FileProvider: der Logtext geht als Text raus. */
    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bulk Compressor Log")
            putExtra(Intent.EXTRA_TEXT, Log.exportText().take(400_000))
        }
        runCatching { startActivity(Intent.createChooser(intent, "Log teilen")) }
            .onFailure {
                Log.w(TAG, "Teilen fehlgeschlagen", it)
                toast("Kein Ziel fuer den Log gefunden")
            }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null)
        )
        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        runCatching { startActivity(perApp) }
            .recoverCatching { startActivity(fallback) }
    }

    // ---- Formatierung ----

    private fun presetSummary(s: CompressSettings): String = buildString {
        append(
            when (s.videoMode) {
                VideoMode.BITRATE -> "VIDEO ${s.bitratePercent} %"
                VideoMode.SCALE -> "VIDEO ${edge(s.videoShortEdge)}"
                VideoMode.BOTH -> "VIDEO ${s.bitratePercent} % · ${edge(s.videoShortEdge)}"
            }
        )
        append(" · ").append(
            when (s.codec) {
                VideoCodec.AUTO -> "AUTO"
                VideoCodec.AVC -> "H.264"
                VideoCodec.HEVC -> "H.265"
            }
        )
        append(" · BILDER Q").append(s.imageQuality).append(' ').append(edge(s.imageShortEdge))
        append(' ').append(
            when (s.imageFormat) {
                ImageFormat.KEEP -> "BEHALTEN"
                ImageFormat.JPEG -> "JPEG"
                ImageFormat.WEBP -> "WEBP"
            }
        )
        append(" · ").append(if (s.outputMode == OutputMode.OVERWRITE) "ERSETZEN" else "DANEBEN")
    }

    private fun edge(px: Int): String = if (px > 0) "${px}p" else "ORIGINAL"

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0 s"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(Locale.GERMANY, "%d h %02d min", hours, minutes)
            minutes > 0 -> String.format(Locale.GERMANY, "%d min %02d s", minutes, seconds)
            else -> "$seconds s"
        }
    }

    companion object {
        private const val TAG = "UI"
        private const val KEY_TREE = "tree_uri"
        private const val TERMINAL_LINES = 400
        private const val START_CHECK_MS = 4000L
        private const val MAX_START_ATTEMPTS = 1
        private const val SAMPLE_INTERVAL_MS = 700L
        private const val HISTORY_SLOTS = 48

        private val RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720, 480)
        private val IMAGE_RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720)
        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY)
    }
}
