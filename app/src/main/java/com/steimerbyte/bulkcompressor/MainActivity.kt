package com.steimerbyte.bulkcompressor

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import com.steimerbyte.bulkcompressor.scan.SafScanner
import com.steimerbyte.bulkcompressor.ui.ChipRow
import com.steimerbyte.bulkcompressor.ui.MediaAdapter
import com.steimerbyte.bulkcompressor.ui.SegmentBar
import com.steimerbyte.bulkcompressor.ui.SoundKit
import com.steimerbyte.bulkcompressor.ui.TerminalPanel

class MainActivity : AppCompatActivity(), JobBus.Listener {

    private lateinit var prefs: SharedPreferences
    private var settings = CompressSettings()
    private var treeUri: Uri? = null
    private var scanResult: ScanResult? = null
    private val selectedIds = LinkedHashSet<String>()

    private lateinit var adapter: MediaAdapter
    private lateinit var textFolder: TextView
    private lateinit var textSummary: TextView
    private lateinit var listMedia: RecyclerView
    private lateinit var textEstimate: TextView
    private lateinit var textHint: TextView
    private lateinit var markTimestamps: TextView
    private lateinit var markExif: TextView
    private lateinit var markSound: TextView
    private lateinit var textAccessHint: TextView
    private lateinit var buttonAccess: Button
    private lateinit var groupProgress: LinearLayout
    private lateinit var segmentBar: SegmentBar
    private lateinit var textPercent: TextView
    private lateinit var textCurrent: TextView
    private lateinit var textTotals: TextView
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
    private lateinit var buttonCancel: Button
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

    private var pendingStart = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingStart) {
                pendingStart = false
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

        adapter = MediaAdapter { item -> toggleSelection(item) }
        listMedia.layoutManager = LinearLayoutManager(this)
        listMedia.adapter = adapter

        findViewById<Button>(R.id.button_pick).setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            pickFolder()
        }
        buttonStart.setOnClickListener {
            SoundKit.play(SoundKit.Click.THUNK)
            onStartClicked()
        }
        buttonAccess.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            requestAllFilesAccess()
        }
        buttonCancel.setOnClickListener {
            SoundKit.play(SoundKit.Click.TICK)
            CompressionService.cancel(this)
        }

        applySettingsToViews()
        requestLegacyStoragePermission()
        restoreFolder()
    }

    override fun onStart() {
        super.onStart()
        JobBus.addListener(this)
        Log.addListener(logListener)
        if (this::terminal.isInitialized) terminal.setLines(Log.snapshot(TERMINAL_LINES))
    }

    override fun onStop() {
        JobBus.removeListener(this)
        Log.removeListener(logListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Nach dem Rueckweg aus den Einstellungen kann die Berechtigung
        // jetzt vorliegen - die Zeile verschwindet dann sofort.
        if (::buttonAccess.isInitialized) updateAccessRow()
        // Die Systemeinstellung fuer Beruehrungsklaenge kann sich
        // waehrend der Laufzeit geaendert haben.
        SoundKit.refreshSystemSetting(this)
    }

    // ------------------------------------------------------------------
    // Aufbau
    // ------------------------------------------------------------------

    private fun bindViews() {
        textFolder = findViewById(R.id.text_folder)
        textSummary = findViewById(R.id.text_summary)
        listMedia = findViewById(R.id.list_media)
        textEstimate = findViewById(R.id.text_estimate)
        textHint = findViewById(R.id.text_hint)
        markTimestamps = findViewById(R.id.mark_timestamps)
        markExif = findViewById(R.id.mark_exif)
        markSound = findViewById(R.id.mark_sound)
        textAccessHint = findViewById(R.id.text_access_hint)
        buttonAccess = findViewById(R.id.button_access)
        groupProgress = findViewById(R.id.group_progress)
        segmentBar = findViewById(R.id.segment_bar)
        textPercent = findViewById(R.id.text_percent)
        textCurrent = findViewById(R.id.text_current)
        textTotals = findViewById(R.id.text_totals)
        textActiveConfig = findViewById(R.id.text_active_config)
        statusVideo = findViewById(R.id.status_video)
        statusImages = findViewById(R.id.status_images)
        statusBitrate = findViewById(R.id.status_bitrate)
        statusVideoResolution = findViewById(R.id.status_video_resolution)
        groupVideo = findViewById(R.id.group_video)
        groupImages = findViewById(R.id.group_images)
        groupBitrate = findViewById(R.id.group_bitrate)
        buttonStart = findViewById(R.id.button_start)
        buttonCancel = findViewById(R.id.button_cancel)
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
        seekBitrate.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            settings = settings.copy(bitratePercent = 5 + value)
            afterSettingsChanged()
        })

        seekQuality.max = 55
        seekQuality.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            settings = settings.copy(imageQuality = 40 + value)
            afterSettingsChanged()
        })
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
    }

    private fun renderValues() {
        textBitrateValue.text = "${settings.bitratePercent} %"
        textQualityValue.text = settings.imageQuality.toString()
        renderToggle(markTimestamps, settings.keepTimestamps)
        renderToggle(markExif, settings.keepExif)
        renderToggle(markSound, settings.clickSounds)
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
        val chosen = scanResult?.items?.filter { selectedIds.contains(it.docId) }.orEmpty()
        val hasVideos = chosen.any { it.kind == MediaKind.VIDEO }
        val hasImages = chosen.any { it.kind == MediaKind.IMAGE }
        val selectsNothing = scanResult == null

        groupVideo.alpha = if (hasVideos || selectsNothing) 1f else INACTIVE_ALPHA
        groupImages.alpha = if (hasImages || selectsNothing) 1f else INACTIVE_ALPHA
        setSectionStatus(statusVideo, hasVideos, selectsNothing)
        setSectionStatus(statusImages, hasImages, selectsNothing)

        textActiveConfig.text = configSummary(hasVideos, hasImages, selectsNothing)
    }

    private fun setStatus(view: TextView, active: Boolean) {
        view.setText(if (active) R.string.status_active else R.string.status_inactive)
        view.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF6B6B6B.toInt())
    }

    private fun setSectionStatus(view: TextView, hasItems: Boolean, unknown: Boolean) {
        when {
            unknown -> {
                view.setText(R.string.status_active)
                view.setTextColor(0xFF6B6B6B.toInt())
            }
            hasItems -> {
                view.setText(R.string.status_active)
                view.setTextColor(0xFFFFFFFF.toInt())
            }
            else -> {
                view.setText(R.string.status_no_selection)
                view.setTextColor(0xFF6B6B6B.toInt())
            }
        }
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
            append(
                getString(
                    R.string.config_output,
                    output,
                    getString(if (settings.keepTimestamps) R.string.value_on else R.string.value_off),
                    getString(if (settings.keepExif) R.string.value_on else R.string.value_off)
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
        startScan(uri)
    }

    private fun startScan(uri: Uri) {
        textFolder.text = displayPath(uri)
        textSummary.visibility = View.VISIBLE
        textSummary.text = getString(R.string.scan_scanning)
        Thread({
            val result = runCatching {
                SafScanner(contentResolver).scan(uri) { progress ->
                    runOnUiThread {
                        if (!isFinishing) {
                            textSummary.text = "${progress.folders} Ordner · ${progress.files} Dateien"
                        }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { applyScan(it) }
                    .onFailure {
                        textSummary.text = it.message ?: getString(R.string.error_permission)
                    }
            }
        }, "ordner-scan").start()
    }

    private fun applyScan(result: ScanResult) {
        scanResult = result
        selectedIds.clear()
        result.items.forEach { selectedIds.add(it.docId) }

        if (result.items.isEmpty()) {
            textSummary.text = getString(R.string.scan_empty)
            listMedia.visibility = View.GONE
        } else {
            textSummary.text = getString(
                R.string.scan_summary,
                result.videos.size,
                result.images.size,
                CompressionJob.formatSize(result.totalBytes)
            ) + if (result.truncated) getString(R.string.scan_truncated) else ""
            listMedia.visibility = View.VISIBLE
        }
        adapter.submit(result.items, selectedIds)
        renderActiveState()
        updateEstimate()
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
        renderActiveState()
        updateEstimate()
    }

    private fun displayPath(uri: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "")
        val volumeName = if (volume == "primary") "Intern" else volume
        if (path.isBlank()) volumeName else "$volumeName / ${path.replace("/", " / ")}"
    }.getOrDefault(uri.toString())

    // ------------------------------------------------------------------
    // Schätzung / Zusammenfassung
    // ------------------------------------------------------------------

    private fun updateEstimate() {
        val result = scanResult
        if (result == null) {
            textEstimate.setText(R.string.estimate_unknown)
            return
        }
        val chosen = result.items.filter { selectedIds.contains(it.docId) }
        if (chosen.isEmpty()) {
            textEstimate.text = "-"
            return
        }
        val videos = chosen.filter { it.kind == MediaKind.VIDEO }
        val images = chosen.filter { it.kind == MediaKind.IMAGE }
        textEstimate.text = buildString {
            append("${videos.size} Videos · ")
            append(CompressionJob.formatSize(videos.sumOf { it.sizeBytes }))
            append("   ")
            append("${images.size} Bilder · ")
            append(CompressionJob.formatSize(images.sumOf { it.sizeBytes }))
            append("\n")
            append(videoSummary())
            append(" · Bilder ")
            append(if (settings.imageShortEdge > 0) "${settings.imageShortEdge}p" else "unveraendert")
            append(" Q${settings.imageQuality}")
        }
    }

    private fun videoSummary(): String = when (settings.videoMode) {
        VideoMode.BITRATE -> "Bitrate ${settings.bitratePercent} %"
        VideoMode.SCALE -> if (settings.videoShortEdge > 0) {
            "Aufloesung max. ${settings.videoShortEdge}p"
        } else {
            "Aufloesung unveraendert"
        }
        VideoMode.BOTH -> "${settings.bitratePercent} % · max. ${settings.videoShortEdge}p"
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

    private fun onStartClicked() {
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
            pendingStart = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchJob()
    }

    private fun launchJob() {
        val uri = treeUri ?: return
        JobBus.reset()
        groupProgress.visibility = View.VISIBLE
        buttonStart.isEnabled = false
        CompressionService.start(this, uri, selectedIds.toList())
    }

    override fun onState(state: JobBus.State) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
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

            buttonStart.isEnabled = !state.running
            buttonCancel.visibility = if (state.running) View.VISIBLE else View.GONE
            if (state.finished) buttonStart.setText(R.string.button_start)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private class SimpleSeekListener(private val onChange: (Int) -> Unit) :
        SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            // Jeder Schritt klickt wie in Minecraft; SoundKit bremst
            // schnell aufeinanderfolgende Wiederholungen von selbst.
            SoundKit.play(SoundKit.Click.TICK)
            onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            // Der letzte Schritt kann vom Bremsen verschluckt worden sein.
            SoundKit.play(SoundKit.Click.TICK)
        }
    }

    companion object {
        private const val TAG = "UI"
        private const val KEY_TREE = "tree_uri"
        private const val TERMINAL_LINES = 400

        /** Wie stark ein nicht wirksamer Abschnitt zurueckgenommen wird. */
        private const val INACTIVE_ALPHA = 0.35f
        private val RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720, 480)
        private val IMAGE_RESOLUTION_VALUES = intArrayOf(0, 2160, 1440, 1080, 720)
    }
}
