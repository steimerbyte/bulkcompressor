package com.steimerbyte.bulkcompressor

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.steimerbyte.bulkcompressor.job.CompressionJob
import com.steimerbyte.bulkcompressor.job.CompressionService
import com.steimerbyte.bulkcompressor.job.JobBus
import com.steimerbyte.bulkcompressor.log.DownloadsLog
import com.steimerbyte.bulkcompressor.log.Log
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
import com.steimerbyte.bulkcompressor.stats.History
import com.steimerbyte.bulkcompressor.stats.HistoryReader
import com.steimerbyte.bulkcompressor.stats.StatsActivity
import com.steimerbyte.bulkcompressor.scan.SafScanner
import com.steimerbyte.bulkcompressor.ui.ChipRow
import com.steimerbyte.bulkcompressor.ui.MediaAdapter
import com.steimerbyte.bulkcompressor.ui.SegmentBar
import com.steimerbyte.bulkcompressor.ui.SoundKit
import com.steimerbyte.bulkcompressor.ui.StatsBoard
import com.steimerbyte.bulkcompressor.ui.StatsFormat
import com.steimerbyte.bulkcompressor.ui.ScanBeacon
import com.steimerbyte.bulkcompressor.ui.TerminalPanel

/**
 * Die beiden Regler, deren Zug getrennt vom Abschluss behandelt wird.
 */
internal enum class Slider { BITRATE, QUALITY }

class MainActivity : AppCompatActivity(), JobBus.Listener {

    private lateinit var prefs: SharedPreferences
    private var settings = CompressSettings()
    private var treeUri: Uri? = null
    private var scanResult: ScanResult? = null
    private val selectedIds = LinkedHashSet<String>()

    /**
     * Laufende Nummer des Ordner-Scans. Ein neuer Start entwertet den
     * vorigen: dessen Ergebnis darf die Anzeige nicht mehr ueberschreiben.
     */
    @Volatile
    private var scanGeneration = 0

    // Kennzahlen der Auswahl. Sie haengen an der Auswahl, nicht an den
    // Reglern, und werden deshalb nur bei Auswahlwechsel neu gerechnet.
    private var selectionVideos = 0
    private var selectionImages = 0
    private var selectionVideoBytes = 0L
    private var selectionImageBytes = 0L

    private lateinit var stats: StatsBoard

    /**
     * Die Laufhistorie wird abseits des Haupt-Threads gelesen und hier
     * gezeichnet; die Trennung steckt in [HistoryReader].
     */
    private val historyReaderDelegate = lazy { HistoryReader(applicationContext) }
    private val historyReader by historyReaderDelegate
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            stats.refresh()
            statsHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private lateinit var adapter: MediaAdapter
    private lateinit var textFolder: TextView
    private lateinit var textSub: TextView
    private lateinit var textSummary: TextView
    private lateinit var textSelection: TextView
    private lateinit var rowSelection: View
    private lateinit var rowSelectionActions: View
    private lateinit var dividerSelection: View
    private lateinit var listMedia: RecyclerView
    private lateinit var textEstimate: TextView
    private lateinit var textHint: TextView
    private lateinit var markTimestamps: TextView
    private lateinit var markExif: TextView
    private lateinit var markSound: TextView
    private lateinit var markRetag: TextView
    private lateinit var textAccessHint: TextView
    private lateinit var buttonAccess: Button
    private lateinit var buttonRescan: Button
    /** true, solange ein Ordner gelesen wird. */
    private var scanning = false
    private lateinit var scanBeacon: ScanBeacon
    private lateinit var groupProgress: LinearLayout
    private lateinit var segmentBar: SegmentBar
    private lateinit var textPercent: TextView
    private lateinit var textCurrent: TextView
    private lateinit var textTotals: TextView
    private lateinit var textTotalSaved: TextView
    private lateinit var buttonStats: android.widget.ImageButton
    private lateinit var textActiveConfig: TextView
    private lateinit var statusVideo: TextView
    private lateinit var statusImages: TextView
    private lateinit var statusBitrate: TextView
    private lateinit var statusVideoResolution: TextView
    private lateinit var groupVideo: View
    private lateinit var groupImages: View
    private lateinit var groupBitrate: View
    private lateinit var terminal: TerminalPanel
    private val logListener: (LogLine) -> Unit = { line ->
        if (!isFinishing && this::terminal.isInitialized) terminal.append(line)
    }
    private lateinit var buttonStart: Button

    /** Der Knopf ist Start und Stopp in einem; das hier ist die Anzeige dafuer. */
    private var cancelRequested = false

    /**
     * Ein Auftrag ist abgegeben, aber der Dienst hat ihn noch nicht
     * angenommen. Solange gilt der Knopf als beschaeftigt - und der
     * Startwaechter raeumt die Markierung auf, falls nie eine Meldung kommt.
     */
    private var startPending = false
    private var startAttempts = 0
    private lateinit var textBitrateValue: TextView
    private lateinit var textQualityValue: TextView
    private lateinit var seekBitrate: SeekBar
    private lateinit var seekQuality: SeekBar

    private lateinit var modeChips: ChipRow
    private lateinit var resolutionChips: ChipRow
    private lateinit var codecChips: ChipRow
    private lateinit var imageResolutionChips: ChipRow
    private lateinit var imageFormatChips: ChipRow
    private lateinit var outputChips: ChipRow

    private var awaitingNotificationPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (awaitingNotificationPermission) {
                awaitingNotificationPermission = false
                if (granted) {
                    launchJob()
                } else {
                    // Auch ohne Benachrichtigung komprimieren - der Dienst
                    // laeuft trotzdem, nur unsichtbar.
                    launchJob()
                }
            }
        }

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
            treeUri = uri
            startScan(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(CompressSettings.PREFS, MODE_PRIVATE)
        settings = CompressSettings.load(prefs)

        // Vor dem Aufbau: die Klickdateien sollen beim ersten Tippen
        // schon dekodiert sein, sonst bleibt der erste Klick stumm.
        SoundKit.init(this, settings.clickSounds)

        bindViews()
        setupChips()
        setupSliders()
        setupToggles()

        stats = StatsBoard(this, findViewById(R.id.stats_board))

        adapter = MediaAdapter { item -> toggleSelection(item) }
        listMedia.layoutManager = LinearLayoutManager(this)
        listMedia.adapter = adapter

        findViewById<Button>(R.id.button_pick).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            pickFolder()
        }
        buttonRescan.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            // Derselbe Weg wie nach der Ordnerauswahl: der Freigabe-Token
            // liegt schon vor, es geht nur um eine neue Liste.
            val uri = treeUri
            if (uri == null) {
                Log.i(TAG, "Neu scannen ohne Ordner - Auswahl wird geoeffnet")
                pickFolder()
            } else {
                startScan(uri)
            }
        }
        buttonStats.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            runCatching { startActivity(Intent(this, StatsActivity::class.java)) }
                .onFailure { Log.w(TAG, "Statistikseite nicht zu oeffnen", it) }
        }

        buttonStart.setOnClickListener {
            SoundKit.play(SoundKit.Click.THUNK)
            onStartButtonClicked()
        }
        findViewById<View>(R.id.button_select_all).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            selectAll()
        }
        findViewById<View>(R.id.button_select_none).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            selectNone()
        }
        buttonAccess.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            requestAllFilesAccess()
        }
        applySettingsToViews()
        // Grundzustand: ohne Ordner ist der zweite Knopf gesperrt. Ohne
        // diesen Aufruf steht er beim Kaltstart in voller Helligkeit da,
        // obwohl er nichts tun kann.
        updateRescanButton()
        requestLegacyStoragePermission()
        restoreFolder()
        resumeInterruptedRun()
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

    override fun onStart() {
        super.onStart()
        JobBus.addListener(this)
        renderStartButton()
        refreshHistory()
        Log.addListener(logListener)
        if (this::terminal.isInitialized) terminal.setLines(Log.snapshot(TERMINAL_LINES))
    }

    override fun onStop() {
        statsHandler.removeCallbacks(statsTick)
        // Der Auftrag laeuft im Dienst weiter, der Ton aber meldet einen
        // Zustand dieser Oberflaeche - im Hintergrund waere er ein Summen
        // aus der Hosentasche.
        SoundKit.stopJobHum()
        JobBus.removeListener(this)
        Log.removeListener(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        // Der Historien-Leser haengt an dieser Instanz; ohne ihn zu
        // beenden bliebe nach dem Schliessen ein Thread mit einer
        // toten Activity in der Warteschlange.
        if (historyReaderDelegate.isInitialized()) historyReader.shutdown()
        // Ein laufender Scan kann diese Activity ueberleben (er laeuft in
        // einem eigenen Thread) - sein Ton darf das nicht.
        SoundKit.stopScanHum()
        SoundKit.stopJobHum()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Die Tafel lebt nur im Vordergrund: im Hintergrund waere ein
        // Sekundentakt nur Stromverbrauch ohne Betrachter.
        statsHandler.removeCallbacks(statsTick)
        statsHandler.post(statsTick)
        // Nach dem Rueckweg aus den Einstellungen kann die Berechtigung
        // jetzt vorliegen - die Zeile verschwindet dann sofort.
        if (::buttonAccess.isInitialized) updateAccessRow()
        // Die Systemeinstellung fuer Beruehrungsklaenge kann sich
        // waehrend der Laufzeit geaendert haben.
        SoundKit.refresh(this)
        // Waehrend die Oberflaeche weg war, konnte ein Auftrag laufen (oder
        // enden) - der Zustand wird beim Zurueckkommen neu gelesen, statt
        // sich auf die verpassten Meldungen zu verlassen.
        if (JobBus.state.running) SoundKit.startJobHum() else SoundKit.stopJobHum()
    }

    // ------------------------------------------------------------------
    // Aufbau
    // ------------------------------------------------------------------

    private fun bindViews() {
        textFolder = findViewById(R.id.text_folder)
        buttonRescan = findViewById(R.id.button_rescan)
        scanBeacon = findViewById(R.id.scan_beacon)
        textSub = findViewById(R.id.text_sub)
        textSummary = findViewById(R.id.text_summary)
        textSelection = findViewById(R.id.text_selection)
        rowSelection = findViewById(R.id.row_selection)
        rowSelectionActions = findViewById(R.id.row_selection_actions)
        dividerSelection = findViewById(R.id.divider_selection)
        listMedia = findViewById(R.id.list_media)
        textEstimate = findViewById(R.id.text_estimate)
        textHint = findViewById(R.id.text_hint)
        markTimestamps = findViewById(R.id.mark_timestamps)
        markExif = findViewById(R.id.mark_exif)
        markSound = findViewById(R.id.mark_sound)
        markRetag = findViewById(R.id.mark_retag)
        textAccessHint = findViewById(R.id.text_access_hint)
        buttonAccess = findViewById(R.id.button_access)
        groupProgress = findViewById(R.id.group_progress)
        segmentBar = findViewById(R.id.segment_bar)
        textPercent = findViewById(R.id.text_percent)
        textCurrent = findViewById(R.id.text_current)
        textTotals = findViewById(R.id.text_totals)
        textTotalSaved = findViewById(R.id.text_total_saved)
        buttonStats = findViewById(R.id.button_stats)
        textActiveConfig = findViewById(R.id.text_active_config)
        statusVideo = findViewById(R.id.status_video)
        statusImages = findViewById(R.id.status_images)
        statusBitrate = findViewById(R.id.status_bitrate)
        statusVideoResolution = findViewById(R.id.status_video_resolution)
        groupVideo = findViewById(R.id.group_video)
        groupImages = findViewById(R.id.group_images)
        groupBitrate = findViewById(R.id.group_bitrate)
        buttonStart = findViewById(R.id.button_start)
        textBitrateValue = findViewById(R.id.text_bitrate_value)
        textQualityValue = findViewById(R.id.text_quality_value)
        seekBitrate = findViewById(R.id.seek_bitrate)
        seekQuality = findViewById(R.id.seek_quality)

        terminal = TerminalPanel(
            activity = this,
            root = findViewById(R.id.terminal_panel),
            onCopy = { Log.d(TAG, "Log in die Zwischenablage kopiert") },
            onShare = { shareLog() },
            onClear = { Log.d(TAG, "Terminalansicht geleert, Logdatei bleibt") }
        )
        terminal.setLines(Log.snapshot(TERMINAL_LINES))
    }

    /** Teilen ohne FileProvider: der Logtext geht als Text raus, damit
     *  jede Ziel-App ihn direkt anzeigen kann. */
    private fun shareLog() {
        val text = Log.exportText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Bulk Compressor Log")
            putExtra(Intent.EXTRA_TEXT, text.take(400_000))
        }
        runCatching { startActivity(Intent.createChooser(intent, "Log teilen")) }
            .onFailure {
                Log.w(TAG, "Teilen fehlgeschlagen", it)
                toast("Kein Ziel fuer den Log gefunden")
            }
    }

    private fun setupChips() {
        modeChips = ChipRow(
            findViewById(R.id.chip_row_mode),
            listOf(
                getString(R.string.mode_bitrate),
                getString(R.string.mode_scale),
                getString(R.string.mode_both)
            )
        ) { index ->
            settings = settings.copy(videoMode = VideoMode.entries[index])
            afterSettingsChanged()
        }

        resolutionChips = ChipRow(
            findViewById(R.id.chip_row_resolution),
            listOf(
                getString(R.string.res_original),
                getString(R.string.res_4k),
                getString(R.string.res_1440),
                getString(R.string.res_1080),
                getString(R.string.res_720),
                getString(R.string.res_480)
            )
        ) { index ->
            settings = settings.copy(videoShortEdge = RESOLUTION_VALUES[index])
            afterSettingsChanged()
        }

        codecChips = ChipRow(
            findViewById(R.id.chip_row_codec),
            listOf(
                getString(R.string.codec_auto),
                getString(R.string.codec_avc),
                getString(R.string.codec_hevc)
            )
        ) { index ->
            settings = settings.copy(codec = VideoCodec.entries[index])
            afterSettingsChanged()
        }

        imageResolutionChips = ChipRow(
            findViewById(R.id.chip_row_image_resolution),
            listOf(
                getString(R.string.res_original),
                getString(R.string.res_4k),
                getString(R.string.res_1440),
                getString(R.string.res_1080),
                getString(R.string.res_720)
            )
        ) { index ->
            settings = settings.copy(imageShortEdge = IMAGE_RESOLUTION_VALUES[index])
            afterSettingsChanged()
        }

        imageFormatChips = ChipRow(
            findViewById(R.id.chip_row_image_format),
            listOf(
                getString(R.string.format_keep),
                getString(R.string.format_jpeg),
                getString(R.string.format_webp)
            )
        ) { index ->
            settings = settings.copy(imageFormat = ImageFormat.entries[index])
            afterSettingsChanged()
        }

        outputChips = ChipRow(
            findViewById(R.id.chip_row_output),
            listOf(
                getString(R.string.output_overwrite),
                getString(R.string.output_keep)
            )
        ) { index ->
            settings = settings.copy(
                outputMode = if (index == 0) OutputMode.OVERWRITE else OutputMode.KEEP_ORIGINAL
            )
            afterSettingsChanged()
        }
    }

    private fun setupSliders() {
        seekBitrate.max = 95
        seekBitrate.setOnSeekBarChangeListener(
            SimpleSeekListener(
                onTick = { sliderTick(Slider.BITRATE, it) },
                onCommit = { sliderCommit(Slider.BITRATE, it) }
            )
        )

        seekQuality.max = 55
        seekQuality.setOnSeekBarChangeListener(
            SimpleSeekListener(
                onTick = { sliderTick(Slider.QUALITY, it) },
                onCommit = { sliderCommit(Slider.QUALITY, it) }
            )
        )
    }

    /**
     * Der Finger am Regler. Hier aendert sich genau eine Zahl - also wird
     * auch nur diese geschrieben.
     *
     * Vorher lief jeder Zwischenschritt durch `afterSettingsChanged()`:
     * SharedPreferences neu geschrieben, Protokollzeile (und damit ein
     * Repaint des Terminal-Panels), Konfigurationszeile neu gebaut und
     * zweimal ueber alle gescannten Dateien gefiltert. Bei einem
     * Kamerafolger mit tausenden Bildern war das je Reglerbewegung ein
     * voller Durchlauf ueber die ganze Liste - und weil der SeekBar bei
     * jeder Fingerbewegung meldet, mehrmals pro Frame.
     */
    internal fun sliderTick(slider: Slider, raw: Int) {
        settings = applySlider(settings, slider, raw)
        renderSliderLabels()
    }

    /** Ende des Zugs: jetzt ist der Wert eine Entscheidung, nicht ein Zwischenschritt. */
    internal fun sliderCommit(slider: Slider, raw: Int) {
        settings = applySlider(settings, slider, raw)
        afterSettingsChanged()
    }

    private fun applySlider(
        current: CompressSettings,
        slider: Slider,
        raw: Int
    ): CompressSettings = when (slider) {
        Slider.BITRATE -> current.copy(bitratePercent = 5 + raw)
        Slider.QUALITY -> current.copy(imageQuality = 40 + raw)
    }

    private fun renderSliderLabels() {
        textBitrateValue.text = "${settings.bitratePercent} %"
        textQualityValue.text = settings.imageQuality.toString()
    }

    private fun setupToggles() {
        findViewById<View>(R.id.row_timestamps).setOnClickListener {
            SoundKit.play(SoundKit.Click.TOGGLE)
            settings = settings.copy(keepTimestamps = !settings.keepTimestamps)
            afterSettingsChanged()
        }
        findViewById<View>(R.id.row_exif).setOnClickListener {
            SoundKit.play(SoundKit.Click.TOGGLE)
            settings = settings.copy(keepExif = !settings.keepExif)
            afterSettingsChanged()
        }
        findViewById<View>(R.id.row_retag).setOnClickListener {
            SoundKit.play(SoundKit.Click.TOGGLE)
            settings = settings.copy(ignoreCompressionTag = !settings.ignoreCompressionTag)
            afterSettingsChanged()
        }
        findViewById<View>(R.id.row_sound).setOnClickListener {
            settings = settings.copy(clickSounds = !settings.clickSounds)
            // Erst umschalten, dann klicken - beim Abschalten hoert man
            // den Klick, mit dem man ihn abschaltet. Beim Einschalten
            // kommt er, weil der Ton schon wieder frei ist.
            SoundKit.setEnabled(settings.clickSounds)
            SoundKit.play(SoundKit.Click.TOGGLE)
            afterSettingsChanged()
        }
    }

    private fun applySettingsToViews() {
        modeChips.selectedIndex = settings.videoMode.ordinal
        resolutionChips.selectedIndex = RESOLUTION_VALUES.indexOf(settings.videoShortEdge).coerceAtLeast(0)
        codecChips.selectedIndex = settings.codec.ordinal
        imageResolutionChips.selectedIndex =
            IMAGE_RESOLUTION_VALUES.indexOf(settings.imageShortEdge).coerceAtLeast(0)
        imageFormatChips.selectedIndex = settings.imageFormat.ordinal
        outputChips.selectedIndex = if (settings.outputMode == OutputMode.OVERWRITE) 0 else 1
        seekBitrate.progress = (settings.bitratePercent - 5).coerceIn(0, 95)
        seekQuality.progress = (settings.imageQuality - 40).coerceIn(0, 55)
        renderValues()
    }

    private fun afterSettingsChanged() {
        settings.save(prefs)
        renderValues()
        Log.d(TAG, "Einstellung geaendert: ${describeSettings()}")
    }

    /** Die wirksame Konfiguration als eine Zeile - dieselbe Fassung wie in der Oberflaeche. */
    private fun describeSettings(): String = buildString {
        append("Videos ").append(settings.videoMode).append(" ").append(settings.bitratePercent).append(" %")
        if (settings.videoShortEdge > 0) append(" ").append(settings.videoShortEdge).append("p")
        append(" ").append(settings.codec)
        append(" · Bilder Q").append(settings.imageQuality).append(' ')
        append(if (settings.imageShortEdge > 0) "${settings.imageShortEdge}px" else "original")
        append(' ').append(settings.imageFormat)
        append(" · ").append(settings.outputMode)
        append(" · Zeitstempel ").append(if (settings.keepTimestamps) "behalten" else "neu")
        append(" · EXIF/GPS ").append(if (settings.keepExif) "behalten" else "verwerfen")
        append(" · Klick ").append(if (settings.clickSounds) "an" else "aus")
        append(" · Marker ").append(if (settings.ignoreCompressionTag) "ignorieren" else "beachten")
    }

    private fun renderValues() {
        renderSliderLabels()
        renderToggle(markTimestamps, settings.keepTimestamps)
        renderToggle(markExif, settings.keepExif)
        renderToggle(markSound, settings.clickSounds)
        // Der Schalter heisst "erneut bearbeiten" - eingeschaltet heisst
        // also: Marker ignorieren.
        renderToggle(markRetag, settings.ignoreCompressionTag)
        textHint.setText(
            if (settings.outputMode == OutputMode.OVERWRITE) R.string.output_hint_overwrite
            else R.string.output_hint_keep
        )
        textHint.setTextColor(
            if (settings.outputMode == OutputMode.OVERWRITE) 0xFF9A9A9A.toInt() else 0xFF6B6B6B.toInt()
        )
        updateAccessRow()
        renderActiveState()
        updateEstimate()
    }

    /**
     * Beantwortet die Frage "welche Aktion ist gerade aktiv?" an drei
     * Stellen: Abschnittsstatus rechts neben der Ueberschrift, das
     * Zuruecknehmen der nicht wirksamen Bedienelemente und die
     * Zusammenfassung ueber dem Startknopf.
     */
    private fun renderActiveState() {
        val bitrateActive = settings.videoMode != VideoMode.SCALE
        val resolutionActive = settings.videoMode != VideoMode.BITRATE

        // Bitrate wirkt nur ausserhalb des reinen Aufloesungsmodus.
        groupBitrate.alpha = if (bitrateActive) 1f else INACTIVE_ALPHA
        seekBitrate.isEnabled = bitrateActive
        setStatus(statusBitrate, bitrateActive)

        // Aufloesung wirkt nur ausserhalb des reinen Bitratenmodus.
        // Bewusst nur die Chips zuruecknehmen (nicht zusaetzlich den
        // ganzen Block), sonst wird die Zeile doppelt gedimmt und ist
        // kaum noch lesbar.
        resolutionChips.setRowInactive(!resolutionActive)
        setStatus(statusVideoResolution, resolutionActive)

        // Ganze Abschnitte: ohne Dateien der Art gibt es nichts zu tun.
        val hasVideos = selectionVideos > 0
        val hasImages = selectionImages > 0
        val selectsNothing = scanResult == null

        groupVideo.alpha = if (hasVideos || selectsNothing) 1f else INACTIVE_ALPHA
        groupImages.alpha = if (hasImages || selectsNothing) 1f else INACTIVE_ALPHA
        setSectionStatus(statusVideo, hasVideos, selectsNothing)
        setSectionStatus(statusImages, hasImages, selectsNothing)

        textActiveConfig.text = configSummary(hasVideos, hasImages, selectsNothing)
    }

    /**
     * Statuslabels sind Ausnahmen, keine Dekoration: ein Abschnitt, der
     * wirkt, braucht kein "AKTIV" - das ist der Normalfall. Angezeigt wird
     * nur, was den Normalfall verlaesst. Vorher standen bis zu fuenf
     * "● AKTIV" gleichzeitig auf dem Bildschirm und verdraengten genau das
     * eine Wort, auf das es ankommt.
     */
    private fun setStatus(view: TextView, active: Boolean) {
        if (active) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.setText(R.string.status_inactive)
        view.setTextColor(0xFF6B6B6B.toInt())
    }

    private fun setSectionStatus(view: TextView, hasItems: Boolean, unknown: Boolean) {
        if (hasItems || unknown) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.setText(R.string.status_no_selection)
        view.setTextColor(0xFF6B6B6B.toInt())
    }

    private fun configSummary(hasVideos: Boolean, hasImages: Boolean, unknown: Boolean): String {
        val codec = getString(
            when (settings.codec) {
                VideoCodec.AUTO -> R.string.codec_auto
                VideoCodec.AVC -> R.string.codec_avc
                VideoCodec.HEVC -> R.string.codec_hevc
            }
        )
        val edge = if (settings.videoShortEdge > 0) "${settings.videoShortEdge}p" else getString(R.string.value_unchanged)
        val videoLine = when {
            !unknown && !hasVideos -> getString(R.string.config_video_off)
            settings.videoMode == VideoMode.BITRATE ->
                getString(R.string.config_video_bitrate, settings.bitratePercent, codec)
            settings.videoMode == VideoMode.SCALE ->
                getString(R.string.config_video_scale, edge, codec)
            else ->
                getString(R.string.config_video_both, settings.bitratePercent, edge, codec)
        }
        val imageEdge = if (settings.imageShortEdge > 0) "${settings.imageShortEdge}p" else getString(R.string.value_unchanged)
        val imageFormat = getString(
            when (settings.imageFormat) {
                ImageFormat.KEEP -> R.string.format_keep
                ImageFormat.JPEG -> R.string.format_jpeg
                ImageFormat.WEBP -> R.string.format_webp
            }
        )
        val imageLine = if (!unknown && !hasImages) {
            getString(R.string.config_images_off)
        } else {
            getString(R.string.config_images, imageEdge, settings.imageQuality, imageFormat)
        }
        val output = getString(
            if (settings.outputMode == OutputMode.OVERWRITE) R.string.output_overwrite
            else R.string.output_keep
        )
        return buildString {
            appendLine(videoLine)
            appendLine(imageLine)
            appendLine(getString(R.string.config_output, output))
            append(
                getString(
                    R.string.config_options,
                    getString(if (settings.keepTimestamps) R.string.value_on else R.string.value_off),
                    getString(if (settings.keepExif) R.string.value_on else R.string.value_off),
                    // "AN" heisst: der Marker wird beachtet, bereits
                    // komprimierte Dateien werden uebersprungen. Der
                    // Schalter darueber ist die Umkehrung davon, deshalb
                    // steht hier nicht derselbe Wortlaut.
                    getString(if (settings.ignoreCompressionTag) R.string.value_off else R.string.value_on)
                )
            )
        }
    }

    private fun updateAccessRow() {
        val needed = Build.VERSION.SDK_INT >= 30 &&
            settings.keepTimestamps &&
            !Environment.isExternalStorageManager()
        val visibility = if (needed) View.VISIBLE else View.GONE
        textAccessHint.visibility = visibility
        buttonAccess.visibility = visibility
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

    private fun renderToggle(mark: TextView, enabled: Boolean) {
        mark.text = if (enabled) "■" else "□"
        mark.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF6B6B6B.toInt())
    }

    // ------------------------------------------------------------------
    // Ordner
    // ------------------------------------------------------------------

    private fun pickFolder() {
        runCatching { folderPicker.launch(null) }
            .onFailure { toast(getString(R.string.error_no_folder)) }
    }

    private fun restoreFolder() {
        val stored = prefs.getString(KEY_TREE, null) ?: return
        val uri = runCatching { Uri.parse(stored) }.getOrNull() ?: return
        val permitted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!permitted) {
            textFolder.text = getString(R.string.error_permission)
            return
        }
        treeUri = uri
        updateRescanButton()
        textSub.visibility = View.GONE
        startScan(uri)
    }

    private fun startScan(uri: Uri) {
        scanBeacon.start()
        // Der Ton haengt am Vorgang, nicht am Knopf: auch der Start aus
        // dem wiederhergestellten Ordner klingt so - der Scan beginnt ja
        // wirklich. Abgeschaltet wird er ueber denselben Schalter wie
        // die Klicks (SoundKit).
        // Der Dauerton laeuft ab jetzt und endet mit dem Lesevorgang; er
        // ist das Signal, dass etwas laeuft, auch ohne Blick aufs Display.
        SoundKit.startScanHum()
        SoundKit.play(SoundKit.Click.SCAN_START)
        scanning = true
        updateRescanButton()
        textFolder.text = FolderPath.display(uri)
        textSummary.visibility = View.VISIBLE
        textSummary.text = getString(R.string.scan_scanning)
        // Jeder Start entwertet den vorigen Lauf. Ohne diese Nummer
        // ueberschreibt ein langer DCIM-Scan das Ergebnis des
        // Unterordners, den der Benutzer inzwischen gewaehlt hat - die
        // Liste zeigt dann Dateien aus dem falschen Baum.
        val generation = ++scanGeneration
        Thread({
            val result = runCatching {
                SafScanner(contentResolver).scan(
                    uri,
                    // "Marker beachten" ist die Vorgabe: was schon einen
                    // Marker traegt, kommt gar nicht erst auf die Liste.
                    respectTag = !settings.ignoreCompressionTag,
                    cancelled = { generation != scanGeneration }
                ) { progress ->
                    runOnUiThread {
                        if (!isFinishing && generation == scanGeneration) {
                            textSummary.text = "${progress.folders} Ordner · ${progress.files} Dateien"
                        }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || generation != scanGeneration) return@runOnUiThread
                result.onSuccess { applyScan(it) }
                    .onFailure {
                        Log.w(TAG, "Ordner-Scan fehlgeschlagen: $uri", it)
                        scanBeacon.stop()
                        SoundKit.stopScanHum()
                        scanning = false
                        updateRescanButton()
                        clearScan()
                        textSummary.text = getString(R.string.scan_failed)
                    }
            }
        }, "ordner-scan").start()
    }

    /**
     * Der zweite Knopf ist nur sinnvoll, solange ein Ordner freigegeben ist
     * und gerade kein Scan laeuft. Ohne Ordner oeffnet ihn der andere
     * Knopf; waehrend eines Laufs wuerde ein zweiter Start den ersten
     * abbrechen, was wie ein Fehler aussieht.
     *
     * Die Abschwaechung gehoert dazu: der Knopf hat eine feste
     * Textfarbe, ein gesperrter Knopf saehe sonst genauso aus wie ein
     * benutzbarer.
     */
    private fun updateRescanButton() {
        val enabled = treeUri != null && !scanning
        buttonRescan.isEnabled = enabled
        buttonRescan.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** Leerer Zustand nach einem fehlgeschlagenen Scan - keine Reste in der Liste. */
    private fun clearScan() {
        scanResult = null
        selectedIds.clear()
        adapter.submit(emptyList(), selectedIds)
        listMedia.visibility = View.GONE
        afterSelectionChanged()
    }

    private fun applyScan(result: ScanResult) {
        scanBeacon.stop()
        // Erst das Summen beenden, dann den Abschluss-Schlag: der Ton
        // markiert das Ende, er soll nicht ueber dem Dauerton liegen.
        SoundKit.stopScanHum()
        SoundKit.play(SoundKit.Click.SCAN_DONE)
        scanning = false
        updateRescanButton()
        scanResult = result
        selectedIds.clear()
        result.items.forEach { selectedIds.add(it.docId) }

        // Die Zeile haengt nicht mehr daran, dass startScan() sie vorher
        // sichtbar gemacht hat: eine wiederhergestellte Auswahl nach einem
        // Prozessende kommt ohne startScan hier an.
        textSummary.visibility = View.VISIBLE
        if (result.items.isEmpty()) {
            textSummary.text = getString(R.string.scan_empty)
            listMedia.visibility = View.GONE
        } else {
            val truncatedNote = if (result.truncated) getString(R.string.scan_truncated) else ""
            val partialNote = if (result.unreadableFolders > 0) {
                getString(R.string.scan_unreadable, result.unreadableFolders)
            } else {
                ""
            }
            val taggedNote = if (result.skippedTagged > 0) {
                getString(R.string.scan_tagged, result.skippedTagged)
            } else {
                ""
            }
            textSummary.text = getString(
                R.string.scan_summary,
                result.videoCount,
                result.imageCount,
                CompressionJob.formatSize(result.totalBytes)
            ) + truncatedNote + partialNote + taggedNote
            listMedia.visibility = View.VISIBLE
        }
        adapter.submit(result.items, selectedIds)
        afterSelectionChanged()
    }

    private fun toggleSelection(item: MediaItem) {
        val nowSelected = if (selectedIds.contains(item.docId)) {
            selectedIds.remove(item.docId)
            false
        } else {
            selectedIds.add(item.docId)
            true
        }
        adapter.setSelected(item.docId, nowSelected)
        afterSelectionChanged()
    }

    /**
     * Alles auswaehlen. Bei einem Ordner mit hunderten Dateien ist der
     * Unterschied zwischen "alle" und "keine" der einzige Auswahlschritt,
     * den man in einem Tipp erledigen will.
     */
    private fun selectAll() {
        val items = scanResult?.items ?: return
        items.forEach { selectedIds.add(it.docId) }
        adapter.submit(items, selectedIds)
        afterSelectionChanged()
    }

    private fun selectNone() {
        selectedIds.clear()
        adapter.submit(scanResult?.items.orEmpty(), selectedIds)
        afterSelectionChanged()
    }

    private fun afterSelectionChanged() {
        recomputeSelection()
        renderSelection()
        renderActiveState()
        renderStartButton()
        updateEstimate()
    }

    /**
     * Was in der Auswahl steckt - einmal je Auswahlwechsel.
     *
     * Diese Zahlen haengen an der Auswahl, nicht an den Reglern. Sie hier
     * zu rechnen ist der Grund, warum das Ziehen eines Sliders bei einem
     * Ordner mit tausenden Dateien nicht mehr die ganze Liste durchlaeuft.
     */
    private fun recomputeSelection() {
        var videos = 0
        var images = 0
        var videoBytes = 0L
        var imageBytes = 0L
        for (item in scanResult?.items.orEmpty()) {
            if (!selectedIds.contains(item.docId)) continue
            if (item.kind == MediaKind.VIDEO) {
                videos++
                videoBytes += item.sizeBytes
            } else {
                images++
                imageBytes += item.sizeBytes
            }
        }
        selectionVideos = videos
        selectionImages = images
        selectionVideoBytes = videoBytes
        selectionImageBytes = imageBytes
    }

    /**
     * Zeigt die Zahl der ausgewaehlten Dateien und blendet die Griffe ein.
     * Ohne Ordner gibt es nichts zu zaehlen - dann bleiben beide weg.
     */
    private fun renderSelection() {
        val items = scanResult?.items.orEmpty()
        val visible = items.isNotEmpty()
        val how = if (visible) View.VISIBLE else View.GONE
        dividerSelection.visibility = how
        rowSelection.visibility = how
        rowSelectionActions.visibility = how
        if (visible) {
            textSelection.text = getString(R.string.selection_count, selectedIds.size, items.size)
        }
        // Die Einleitung erklaert die App; bei geladenem Ordner ist die
        // Flaeche besser in der Dateiliste aufgehoben.
        textSub.visibility = if (treeUri == null) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Schätzung / Zusammenfassung
    // ------------------------------------------------------------------

    /**
     * Nur die Mengen: was ausgewaehlt ist und wie gross es ist. Wie es
     * danach aussieht, steht darunter im Konfigurationsblock - vorher
     * nannte diese Zeile Aufloesung und Qualitaet ein zweites Mal.
     */
    private fun updateEstimate() {
        if (scanResult == null || selectedIds.isEmpty()) {
            textEstimate.visibility = View.GONE
            return
        }
        textEstimate.visibility = View.VISIBLE
        textEstimate.text = buildString {
            append("$selectionVideos Videos · ")
            append(CompressionJob.formatSize(selectionVideoBytes))
            append("    ")
            append("$selectionImages Bilder · ")
            append(CompressionJob.formatSize(selectionImageBytes))
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

    private fun onStartButtonClicked() {
        if (JobBus.state.running) {
            cancelRequested = true
            renderStartButton()
            Log.i(TAG, "Abbruch ueber den Startknopf angefordert")
            CompressionService.cancel(this)
            return
        }
        val uri = treeUri
        if (uri == null) {
            toast(getString(R.string.error_no_folder))
            return
        }
        if (selectedIds.isEmpty()) {
            toast(getString(R.string.error_no_selection))
            return
        }
        settings.save(prefs)
        Log.i(
            TAG,
            "Start angefordert: ${selectedIds.size} Dateien · Modus ${settings.videoMode} · " +
                "${settings.bitratePercent} % · ${settings.videoShortEdge}p · Codec ${settings.codec} · " +
                "Bilder Q${settings.imageQuality} ${settings.imageShortEdge}p ${settings.imageFormat} · " +
                "Ausgabe ${settings.outputMode}"
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            awaitingNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchJob()
    }

    private fun launchJob() {
        val uri = treeUri ?: return
        groupProgress.visibility = View.VISIBLE
        startAttempts = 0
        startPending = true
        JobBus.reset()
        renderStartButton()
        CompressionService.start(this, uri, selectedIds.toList())
        // Der Dienst meldet sich sofort mit "laeuft". Bleibt das aus, hat er
        // den Auftrag verworfen - dann wird einmal nachgestartet und danach
        // aufgegeben, statt einen toten Knopf zu hinterlassen.
        statsHandler.removeCallbacks(startCheck)
        statsHandler.postDelayed(startCheck, START_CHECK_MS)
    }

    /**
     * Waechter fuer den Start: der Dienst kann einen Auftrag ablehnen, ohne
     * dass die Oberflaeche davon erfaehrt. Statt eines stillen Nichtstuns
     * gibt es einen zweiten Versuch und dann eine sichtbare Meldung.
     */
    private val startCheck = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val state = JobBus.state
            if (state.running || state.finished || state.cancelled) return
            val uri = treeUri
            val ids = selectedIds.toList()
            if (uri == null || ids.isEmpty()) return
            if (startAttempts >= MAX_START_ATTEMPTS) {
                Log.e(
                    TAG,
                    "Auftrag nach $MAX_START_ATTEMPTS Versuchen nicht angenommen - aufgegeben"
                )
                JobBus.appendLog("Auftrag kam nicht an: $MAX_START_ATTEMPTS Versuche ohne Wirkung")
                startPending = false
                renderStartButton()
                toast(getString(R.string.error_start_refused))
                return
            }
            startAttempts++
            Log.w(
                TAG,
                "Auftrag nach ${START_CHECK_MS} ms nicht angenommen - Versuch $startAttempts" +
                    " (${ids.size} Dateien)"
            )
            CompressionService.start(this@MainActivity, uri, ids)
            statsHandler.postDelayed(this, START_CHECK_MS)
        }
    }

    /**
     * Ein Knopf, zwei Bedeutungen. Der angezeigte Zustand kommt aus
     * [JobBus], nicht aus einem frueheren Klick: sonst bleibt der Knopf nach
     * einem im Hintergrund beendeten Lauf gesperrt, weil dessen Endmeldung
     * niemand gesehen hat.
     */
    private fun renderStartButton() {
        val state = JobBus.state
        when {
            state.running && cancelRequested -> {
                buttonStart.setText(R.string.button_cancelling)
                buttonStart.isEnabled = true
                buttonStart.setTextColor(0xFFFF4D4D.toInt())
                buttonStart.setBackgroundResource(R.drawable.button_background_outline)
            }
            state.running -> {
                startPending = false
                buttonStart.setText(R.string.button_stop)
                buttonStart.isEnabled = true
                buttonStart.setTextColor(0xFFFF4D4D.toInt())
                buttonStart.setBackgroundResource(R.drawable.button_background_outline)
            }
            startPending -> {
                buttonStart.setText(R.string.button_starting)
                buttonStart.isEnabled = false
                buttonStart.setTextColor(0xFF6B6B6B.toInt())
                buttonStart.setBackgroundResource(R.drawable.button_background_disabled)
            }
            else -> {
                cancelRequested = false
                val ready = treeUri != null && selectedIds.isNotEmpty()
                buttonStart.setText(if (ready) R.string.button_start else R.string.button_no_selection)
                buttonStart.isEnabled = ready
                if (ready) {
                    buttonStart.setTextColor(0xFF000000.toInt())
                    buttonStart.setBackgroundResource(R.drawable.button_background)
                } else {
                    // Weiss gefuellt und nicht bedienbar sieht aus wie der
                    // wichtigste Knopf der App, der nicht reagiert.
                    buttonStart.setTextColor(0xFF6B6B6B.toInt())
                    buttonStart.setBackgroundResource(R.drawable.button_background_disabled)
                }
            }
        }
    }

    override fun onState(state: JobBus.State) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (state.running || state.finished || state.cancelled) startPending = false
            // Der Auftragston haengt am Zustand, nicht am Knopf: er laeuft,
            // solange dieser Lauf laeuft, und endet mit ihm. Ein Zustand
            // kommt nur im Vordergrund an (der Listener haengt an onStop).
            if (state.running) SoundKit.startJobHum() else SoundKit.stopJobHum()
            renderStartButton()
            if (!state.running && !state.finished && !state.cancelled) return@runOnUiThread

            groupProgress.visibility = View.VISIBLE
            textPercent.text = "${(state.overall * 100).toInt()} %"
            segmentBar.progress = state.overall
            textCurrent.text = state.currentName.ifBlank { getString(R.string.progress_preparing) }
            textTotals.text = buildString {
                append("${state.done} / ${state.total}")
                if (state.beforeBytes > 0) {
                    append(" · ")
                    append(CompressionJob.formatSize(state.beforeBytes))
                    append(" → ")
                    append(CompressionJob.formatSize(state.afterBytes))
                    append(" (−")
                    append(state.savedPercent)
                    append(" %)")
                }
            }

            // Der Dienst schreibt den Lauf beim Abschluss in die
            // Historie; hier wird sie nur noch nachgelesen. Das ist der
            // einzige Weg, der auch stimmt, wenn diese Oberflaeche beim
            // Abschluss gar nicht existierte.
            if (state.finished && !state.running) refreshHistory()
        }
    }

    /**
     * Liest die Laufhistorie im Hintergrund und stellt Tafel und
     * Summenzeile darauf ein. Die Datei ist klein, aber sie ist eine
     * Datei - auf dem Hauptthread hat sie beim Zustandswechsel nichts
     * zu suchen.
     */
    private fun refreshHistory() {
        historyReader.read { history ->
            if (isFinishing || isDestroyed) return@read
            stats.renderSaved(history.runs.lastOrNull())
            renderTotalSaved(history)
        }
    }

    /**
     * Nur fuer Tests: wartet auf das Ende des Lesers. Die Activity liest im
     * Hintergrund, ein Test kann deshalb nicht einfach nachsehen.
     */
    internal fun awaitHistoryForTest(timeoutMs: Long = 2000) {
        historyReader.awaitForTest(timeoutMs)
    }

    /** Summe ueber alle Laeufe - erst ab dem ersten fertigen Lauf. */
    private fun renderTotalSaved(history: History) {
        val totals = history.totals
        if (totals.runs <= 0) {
            textTotalSaved.visibility = View.GONE
            return
        }
        textTotalSaved.visibility = View.VISIBLE
        textTotalSaved.text = getString(
            R.string.total_saved,
            StatsFormat.formatFreed(totals.savedBytes),
            totals.runs
        )
    }

    /** Nur fuer Tests: Ordner und Scanergebnis ohne echten ContentProvider. */
    internal fun applyScanForTest(tree: Uri, result: ScanResult) {
        treeUri = tree
        textFolder.text = FolderPath.display(tree)
        applyScan(result)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Zwei Wege statt einem: [onTick] folgt dem Finger, [onCommit] schliesst
     * den Zug ab. Alles, was speichert, protokolliert oder die abgeleitete
     * Anzeige neu baut, gehoert an [onCommit].
     */
    internal class SimpleSeekListener(
        private val onTick: (Int) -> Unit,
        private val onCommit: (Int) -> Unit
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            // Jeder Schritt klickt wie in Minecraft; SoundKit bremst
            // schnell aufeinanderfolgende Wiederholungen von selbst.
            SoundKit.play(SoundKit.Click.TICK)
            onTick(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            val progress = seekBar?.progress ?: return
            // Der letzte Schritt kann vom Bremsen verschluckt worden sein.
            SoundKit.play(SoundKit.Click.TICK)
            onCommit(progress)
        }
    }

    companion object {
        private const val TAG = "UI"
        private const val KEY_TREE = "tree_uri"
        private const val TERMINAL_LINES = 400

        /** Takt der Speichertafel - ein Blick pro Sekunde reicht. */
        private const val STATS_INTERVAL_MS = 1000L

        /** Deckkraft eines gesperrten Knopfes mit fester Textfarbe. */
        private const val DISABLED_ALPHA = 0.35f

        /** Nach dieser Zeit muss der Dienst den Auftrag angenommen haben. */
        private const val START_CHECK_MS = 4000L

        /** Ein Wiederholungsversuch, dann eine Meldung statt Stille. */
        private const val MAX_START_ATTEMPTS = 1

        /** Wie stark ein nicht wirksamer Abschnitt zurueckgenommen wird. */
        private const val INACTIVE_ALPHA = 0.35f
        private val RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720, 480)
        private val IMAGE_RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720)
    }
}
