package com.steimerbyte.bulkcompressor.job

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.steimerbyte.bulkcompressor.MainActivity
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.scan.FolderPath
import com.steimerbyte.bulkcompressor.scan.SafScanner
import com.steimerbyte.bulkcompressor.stats.RunHistory
import com.steimerbyte.bulkcompressor.stats.runRecord
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vordergrunddienst: die Kompression laeuft weiter, wenn die App im
 * Hintergrund ist oder der Bildschirm ausgeht. Das ist bei mehreren
 * hundert Dateien keine Bequemlichkeit, sondern Voraussetzung - ein
 * normaler Thread wuerde mitten im Lauf eingefroren.
 */
class CompressionService : Service(), JobBus.Listener {

    private val cancelled = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdate = 0L
    private var notificationUpdates = 0

    /** Zuletzt gelesener Geraetespeicher; wird nicht bei jedem Prozent neu erfragt. */
    private var deviceFreeBytes = -1L
    private var deviceRamAt = 0L
    private var runStartedAt = 0L
    private var cancelRequestedAt = 0L

    /**
     * Ein Start, der waehrend des Abschlusses des vorigen Laufs eintrifft.
     * Frueher wurde er stillschweigend verworfen - die Oberflaeche blieb
     * dann mit einem nicht bedienbaren Startknopf zurueck, ohne Hinweis.
     */
    @Volatile
    private var pendingStart: Intent? = null
    private var wakeLockRenewals = 0

    /** Alles, was auf dem Haupt-Thread laufen muss: Meldungen, Wachhund, Start. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val renewLock = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            if (lock.isHeld) {
                mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
                return
            }
            val renewed = runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            wakeLockRenewals++
            if (renewed.isSuccess && lock.isHeld) {
                Log.d(
                    "Service",
                    "Wachhund erneuert (${wakeLockRenewals}. Mal) · Laufzeit " +
                        "${System.currentTimeMillis() - runStartedAt} ms · neues Limit" +
                        " ${WAKE_LOCK_TIMEOUT_MS / 60_000} min"
                )
                mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
            } else {
                Log.e(
                    "Service",
                    "Wachhund liess sich nicht erneuern - der Lauf kann bei Bildschirmsperre einfrieren",
                    renewed.exceptionOrNull()
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("Service", "onCreate")
        createChannel()
        JobBus.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tree = intent.getStringExtra(EXTRA_TREE)
                val docIds = intent.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
                if (tree == null || docIds.isEmpty()) {
                    Log.w(
                        "Service",
                        "Start ohne Ordner oder Dateien verworfen (tree=$tree, Dateien=${docIds.size})"
                    )
                    // Ein verworfener Auftrag muss sichtbar sein: ohne diese
                    // Meldung bleibt die Oberflaeche im Zustand "laeuft" und
                    // der Startknopf ist tot, ohne dass jemand erfaehrt warum.
                    JobBus.appendLog("Auftrag verworfen: kein Ordner oder keine Dateien ausgewaehlt")
                    JobBus.publish(JobBus.State(finished = true))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (worker?.isAlive == true) {
                    pendingStart = intent
                    Log.i(
                        "Service",
                        "Start vorgemerkt: voriger Lauf schliesst noch ab (Worker ${worker?.id} laeuft)"
                    )
                    JobBus.appendLog("Neuer Auftrag vorgemerkt - startet nach dem Abschluss des vorigen Laufs")
                    return START_REDELIVER_INTENT
                }
                Log.i("Service", "Start: ${docIds.size} Dateien · $tree")
                if (!startForegroundNow(docIds.size)) {
                    Log.e("Service", "Vordergrunddienst nicht moeglich - Auftrag wird nicht gestartet")
                    JobBus.appendLog("Nicht gestartet: Vordergrunddienst war nicht moeglich")
                    JobBus.publish(JobBus.State(finished = true))
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginRun(Uri.parse(tree), docIds)
                // Ein vom System beendeter Prozess bekommt den Auftrag
                // erneut zugestellt; das Journal nimmt die erledigten
                // Dateien heraus.
                return START_REDELIVER_INTENT
            }
            ACTION_CANCEL -> {
                pendingStart = null
                cancelRequestedAt = System.currentTimeMillis()
                val runningSince = runStartedAt
                Log.i(
                    "Service",
                    "Abbruch angefordert um $cancelRequestedAt" +
                        (if (runningSince > 0) " · Auftrag lief da ${cancelRequestedAt - runningSince} ms"
                        else " · kein Auftrag gestartet") +
                        " · Worker ${if (worker?.isAlive == true) "laeuft" else "laeuft nicht"}"
                )
                cancelled.set(true)
                if (worker?.isAlive == true) {
                    notify("Wird abgebrochen ...", null, 0, false)
                } else {
                    // Kein Lauf, kein Abbruch: den Dienst nicht laufen lassen.
                    Log.d("Service", "Abbruch ohne laufenden Auftrag - Dienst wird beendet")
                    stopSelf()
                }
            }
            else -> Log.w("Service", "Unbekannter Auftrag: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /**
     * Meldet den angenommenen Auftrag sofort und startet den Worker.
     *
     * Die Meldung muss vor dem Scan stehen: der Worker liest den Ordner neu
     * ein, und bis dahin sieht die Oberflaeche sonst gar nichts - der
     * Startknopf bliebe auf "Start" und ein zweiter Griff wuerde doppelt
     * starten.
     */
    private fun beginRun(treeUri: Uri, docIds: List<String>) {
        JobBus.publish(
            JobBus.State(running = true, total = docIds.size, currentName = "Vorbereitung ...")
        )
        launchWorker(treeUri, docIds)
    }

    private fun launchWorker(treeUri: Uri, docIds: List<String>) {
        cancelled.set(false)
        runStartedAt = System.currentTimeMillis()
        cancelRequestedAt = 0L
        notificationUpdates = 0
        acquireWakeLock()
        val thread = Thread({
            val threadName = Thread.currentThread().name
            val threadId = Thread.currentThread().id
            val workingStart = System.currentTimeMillis()
            logRuntime("Worker-Start")
            Log.i(
                "Service",
                "Worker: Thread $threadName (id $threadId) · Prioritaet ${Thread.currentThread().priority}" +
                    " · Start um $workingStart · ${docIds.size} Dateien · $treeUri"
            )
            try {
                // Der Ordner wird erneut gelesen: damit stimmen Groesse,
                // Zeitstempel und Ordnerzuordnung auch dann, wenn
                // zwischendurch etwas geaendert wurde.
                val scanStartedAt = System.currentTimeMillis()
                val scan = SafScanner(contentResolver).scan(treeUri)
                val wanted = docIds.toHashSet()
                val items = scan.items.filter { it.docId in wanted }
                Log.i(
                    "Service",
                    "Scan des Auftrags: ${scan.items.size} Dateien in ${scan.folderCount} Ordnern" +
                        " in ${System.currentTimeMillis() - scanStartedAt} ms" +
                        " · ${items.size} davon ausgewaehlt" +
                        if (scan.truncated) " · Grenze erreicht" else ""
                )
                if (items.size != docIds.size) {
                    Log.w(
                        "Service",
                        "Nur ${items.size} von ${docIds.size} gewaehlten Dateien wiedergefunden"
                    )
                }
                val settings = CompressSettings.load(
                    getSharedPreferences(CompressSettings.PREFS, MODE_PRIVATE)
                )
                Log.i(
                    "Service",
                    "Einstellungen geladen: ${settings.videoMode.name} ${settings.bitratePercent}%" +
                        " · kurze Kante ${settings.videoShortEdge}px · Codec ${settings.codec.name}" +
                        " · Bilder Q${settings.imageQuality} · ${settings.imageFormat.name}" +
                        " · Ausgabe ${settings.outputMode.name}" +
                        " · Zeitstempel ${if (settings.keepTimestamps) "behalten" else "verwerfen"}" +
                        " · EXIF ${if (settings.keepExif) "behalten" else "verwerfen"}"
                )
                CompressionJob(applicationContext, treeUri, items, settings, cancelled).run()
            } catch (t: Throwable) {
                JobBus.appendLog("ABBRUCH: ${t.message ?: t.javaClass.simpleName}")
                Log.e("Service", "Lauf abgebrochen nach ${System.currentTimeMillis() - workingStart} ms", t)
                // Vor der Abschlussmeldung notieren: die Oberflaeche liest
                // die Historie in genau dem Moment, in dem sie "fertig"
                // sieht. recordRun ist idempotent, der finally-Zweig
                // schreibt denselben Lauf kein zweites Mal.
                recordRun(treeUri)
                JobBus.publish(
                    JobBus.state.copy(running = false, finished = true, overall = 0f)
                )
            } finally {
                // Bewusst vor finishForeground: das veroeffentlicht den
                // Endzustand, und die Oberflaeche liest die Historie genau
                // dann nach. Hier und nicht in der Activity - der Lauf
                // endet auch dann richtig, wenn keine Oberflaeche existiert.
                recordRun(treeUri)
                logRunEnd(
                    threadName,
                    workingStart,
                    if (cancelled.get()) {
                        "Abbruch, Wachhund freigegeben, Dienst beendet sich selbst (stopSelf)"
                    } else {
                        "Lauf abgeschlossen, Wachhund freigegeben, Dienst beendet sich selbst (stopSelf)"
                    }
                )
                releaseWakeLock()
                val next = pendingStart
                pendingStart = null
                if (next == null) {
                    finishForeground()
                } else {
                    // Abschlussmeldung ja, Dienstende nein: der vorgemerkte
                    // Auftrag laeuft direkt weiter.
                    finishForeground(stopService = false)
                    val tree = next.getStringExtra(EXTRA_TREE)
                    val docIds = next.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
                    Log.i(
                        "Service",
                        "Vorgemerkter Auftrag wird angeschlossen: ${docIds.size} Dateien" +
                            " \u00b7 $tree"
                    )
                    if (tree == null || docIds.isEmpty()) {
                        finishForeground()
                    } else {
                        mainHandler.post {
                            beginRun(Uri.parse(tree), docIds)
                        }
                    }
                }
            }
        }, "bulk-compress")
        worker = thread
        thread.start()
        Log.i(
            "Service",
            "Worker-Thread gestartet: ${thread.name} (id ${thread.id}) · angefordert um $runStartedAt"
        )
    }

    /** Speicher und Ausgabeort - einmal je Lauf, nicht je Datei. */
    private fun logRuntime(stage: String) {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val totalMb = runtime.totalMemory() / 1024 / 1024
        val freeMb = runtime.freeMemory() / 1024 / 1024
        val usedMb = totalMb - freeMb
        Log.i(
            "Service",
            "$stage · Speicher: max $maxMb MB, belegt $usedMb MB von $totalMb MB, frei $freeMb MB"
        )
        runCatching {
            val cacheDir = cacheDir
            val usableMb = cacheDir.usableSpace / 1024 / 1024
            val totalSpaceMb = cacheDir.totalSpace / 1024 / 1024
            Log.i(
                "Service",
                "$stage · Ausgabeort/Arbeitsordner ${cacheDir.absolutePath}" +
                    " · $usableMb MB frei von $totalSpaceMb MB" +
                    " · Temp-Datei je Datei wird hier angelegt"
            )
        }.onFailure {
            Log.w("Service", "$stage · freier Speicher des Arbeitsordners nicht ermittelbar", it)
        }
    }

    /** Abschluss des Workers: Dauer, Abbruchgrund und warum der Dienst endet. */
    private fun logRunEnd(threadName: String, workingStart: Long, reason: String) {
        val now = System.currentTimeMillis()
        val cancelledNow = cancelled.get()
        val state = JobBus.state
        val abortDetail = if (cancelRequestedAt > 0) {
            "Abbruch angefordert vor ${now - cancelRequestedAt} ms"
        } else {
            "kein Abbruch angefordert"
        }
        Log.i(
            "Service",
            "Worker endet: Thread $threadName · Laufzeit ${now - workingStart} ms" +
                " · Abbruch ${if (cancelledNow) "ja" else "nein"} · $abortDetail" +
                " · ${state.done}/${state.total} Dateien" +
                " · ${state.skipped} uebersprungen · ${state.failed} fehlgeschlagen" +
                " · ${CompressionJob.formatSize(state.beforeBytes)} -> " +
                "${CompressionJob.formatSize(state.afterBytes)}" +
                " · ${notificationUpdates} Benachrichtigungs-Updates" +
                " · Ende: $reason"
        )
    }

    // ------------------------------------------------------------------
    // Benachrichtigung
    // ------------------------------------------------------------------

    /**
     * Fortschritt kommt vom Worker-Thread, die Benachrichtigung gehoert auf
     * den Haupt-Thread. Ohne diese Umschaltung laufen zwei Staende
     * gleichzeitig: der Worker meldet hier, waehrend der Haupt-Thread ueber
     * `beginRun`/`exitWithError` dieselben Felder (Kadenz, Geraetespeicher,
     * Zaehler) anfasst. Die Umschaltung macht den Pfad einfach: alle
     * Meldungen werden der Reihe nach auf dem Haupt-Thread abgearbeitet.
     */
    override fun onState(state: JobBus.State) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            renderState(state)
        } else {
            mainHandler.post { renderState(state) }
        }
    }

    private fun renderState(state: JobBus.State) {
        if (state.finished || !state.running) return
        val now = System.currentTimeMillis()
        val progressChanged = (state.overall * 100).toInt()
        if (now - lastNotificationUpdate < 400 && progressChanged % 5 != 0) return
        lastNotificationUpdate = now
        notificationUpdates++
        if (notificationUpdates == 1) {
            Log.i(
                "Service",
                "Benachrichtigung: Kadenz fruehestens alle 400 ms oder je 5% Fortschritt" +
                    " · erstes Update bei ${progressChanged}% (${state.done}/${state.total} Dateien)" +
                    " · Text \"${state.currentName.ifBlank { "Vorbereitung ..." }}\""
            )
        }
        val runtime = Runtime.getRuntime()
        refreshDeviceRam(now)
        notify(
            // Die Speicherzeile steht im immer sichtbaren Text, der Dateiname
            // darunter im Kopfbereich - "wie knapp wird es" will man sehen,
            // ohne die App zu oeffnen.
            text = NotificationText.progressLine(
                done = state.done,
                total = state.total,
                heapUsed = runtime.totalMemory() - runtime.freeMemory(),
                heapMax = runtime.maxMemory(),
                deviceFree = deviceFreeBytes,
                level = MemoryGuard.level()
            ),
            subText = state.currentName.ifBlank { "Vorbereitung ..." },
            percent = (state.overall * 100).toInt(),
            showProgress = true
        )
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNow(total: Int): Boolean {
        // Ohne POST_NOTIFICATIONS (ab Android 13) laeuft der Dienst
        // trotzdem; dann ist eben nur keine Benachrichtigung sichtbar.
        val startLine = NotificationText.progressLine(
            done = 0,
            total = total,
            heapUsed = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() },
            heapMax = Runtime.getRuntime().maxMemory(),
            deviceFree = deviceFreeBytes,
            level = MemoryGuard.level()
        )
        val notification = buildNotification(startLine, "Startet ...", 0, true)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (started.isFailure) {
            // Ab Android 12 kann das System den Start verweigern, wenn die
            // App aus dem Hintergrund heraus startet. Ohne diesen Schritt
            // wuerde die Ausnahme aus onStartCommand fliegen und den
            // Prozess mitnehmen; stattdessen wird der Auftrag gemeldet
            // und bleibt unerledigt.
            exitWithError(started.exceptionOrNull())
            return false
        }
        Log.i(
            "Service",
            "Vordergrunddienst aktiv: Kennung $NOTIFICATION_ID · Typ " +
                if (Build.VERSION.SDK_INT >= 29) "DATA_SYNC" else "ohne Typ (API < 29)"
        )
        return true
    }

    /** Meldet einen Auftrag, der nicht starten konnte - sichtbar, nicht nur im Log. */
    private fun exitWithError(error: Throwable?) {
        val text = "Start nicht moeglich: ${error?.javaClass?.simpleName ?: "unbekannt"}"
        Log.e("Service", "$text · ${error?.message}", error)
        JobBus.appendLog("FEHLER: $text")
        JobBus.publish(JobBus.state.copy(running = false, finished = true))
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, null, 0, false))
        }
    }

    /**
     * Haelt den abgeschlossenen Lauf in der Statistik fest.
     *
     * Ein Lauf ohne eine einzige bearbeitete Datei wird nicht notiert -
     * er hat nichts zu berichten. Ein abgebrochener Lauf zaehlt mit dem,
     * was er bis dahin geschafft hat: die Bytes sind wirklich frei
     * geworden.
     */
    private fun recordRun(tree: Uri) {
        runCatching {
            val state = JobBus.state
            val entry = runRecord(
                id = runStartedAt,
                folder = FolderPath.display(tree),
                total = state.total,
                done = state.done,
                skipped = state.skipped,
                failed = state.failed,
                savedBytes = state.savedBytes
            ) ?: return
            RunHistory.of(applicationContext).record(entry)
        }.onFailure { Log.w("Service", "Lauf nicht in die Statistik geschrieben", it) }
    }

    private fun finishForeground(stopService: Boolean = true) {
        JobBus.publish(JobBus.state.copy(running = false))
        val state = JobBus.state
        val summary = buildString {
            if (state.cancelled) append("Abgebrochen") else append("Fertig")
            append(": ${state.done} Dateien")
            if (state.beforeBytes > 0) {
                append(" · ${CompressionJob.formatSize(state.savedBytes)} gespart")
            }
        }
        val notification = buildNotification(summary, null, 0, false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        Log.i(
            "Service",
            "Vordergrunddienst beendet: Abschlussbenachrichtigung \"$summary\"" +
                " · $notificationUpdates Fortschritts-Updates insgesamt" +
                " · Dienst stoppt sich selbst (stopSelf)"
        )
        stopSelf()
    }

    /**
     * Der Geraetespeicher kommt vom Systemdienst und wird deshalb nicht bei
     * jedem Prozent neu erfragt - fuer die Anzeige reicht ein Takt von zwei
     * Sekunden, der eigene Heap wird ohnehin je Meldung gelesen.
     */
    private fun refreshDeviceRam(now: Long) {
        if (deviceFreeBytes >= 0 && now - deviceRamAt < DEVICE_RAM_REFRESH_MS) return
        deviceRamAt = now
        runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            deviceFreeBytes = info.availMem
        }
    }

    private fun notify(text: String, subText: String?, percent: Int, showProgress: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, subText, percent, showProgress))
    }

    private fun buildNotification(
        text: String,
        subText: String?,
        percent: Int,
        showProgress: Boolean
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bulk Compressor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(showProgress)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (subText != null) builder.setSubText(subText)
        if (showProgress) {
            // Aufgeklappt steht beides untereinander: im zugeklappten
            // Zustand zeigen manche Hersteller nur den Fliesstext, und
            // gerade dort soll die Speicherzeile nicht wegfallen.
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (subText.isNullOrBlank()) text else "$text\n$subText"
                )
            )
            builder.setProgress(100, percent.coerceIn(0, 100), false)
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    getString(R.string.action_cancel),
                    cancelIntent
                ).build()
            )
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        // Ein fehlender Kanal ist ein echter Fehler und wird protokolliert,
        // aber nicht verschluckt: der Aufruf soll sich verhalten wie bisher.
        try {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) {
                Log.d("Service", "Notification-Kanal vorhanden")
                return
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
            Log.d("Service", "Notification-Kanal angelegt")
        } catch (t: Throwable) {
            Log.w("Service", "Notification-Kanal nicht anlegbar", t)
            throw t
        }
    }

    // ------------------------------------------------------------------
    // Lebenszyklus
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bulkcompressor:job")
        lock.setReferenceCounted(false)
        try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            // Ein fehlender Wachhund darf den Lauf nicht kosten: ohne ihn
            // arbeitet der Dienst weiter, wird bei Bildschirmsperre aber
            // moeglicherweise gedrosselt. Frueher flog die Ausnahme aus
            // onStartCommand heraus und nahm den Prozess mit.
            wakeLock = null
            Log.e("Service", "Wachhund nicht verfuegbar - Lauf ohne Zeitgeber", t)
            return
        }
        if (lock.isHeld) {
            Log.i(
                "Service",
                "Wachhund erworben: PARTIAL_WAKE_LOCK · Kennung bulkcompressor:job" +
                    " · Zeitlimit ${WAKE_LOCK_TIMEOUT_MS / 60_000} min" +
                    " (${WAKE_LOCK_TIMEOUT_MS} ms) · gehalten ja · Referenzzaehlung aus"
            )
        } else {
            Log.w("Service", "Wachhund nach acquire nicht gehalten - Lauf kann einfrieren")
        }
        wakeLock = lock
        mainHandler.removeCallbacks(renewLock)
        mainHandler.postDelayed(renewLock, WAKE_LOCK_RENEW_MS)
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(renewLock)
        val lock = wakeLock
        wakeLock = null
        if (lock == null) return
        if (!lock.isHeld) {
            Log.w("Service", "Wachhund war nicht mehr gehalten - Zeitlimit abgelaufen?")
            return
        }
        runCatching { lock.release() }
            .onSuccess {
                Log.i(
                    "Service",
                    "Wachhund freigegeben · Kennung bulkcompressor:job · gehalten nein" +
                        (if (wakeLockRenewals > 0) " · $wakeLockRenewals Erneuerungen" else "")
                )
            }
            .onFailure { Log.w("Service", "Wachhund liess sich nicht freigeben", it) }
    }

    override fun onDestroy() {
        Log.i(
            "Service",
            "onDestroy · Abbruch ${if (cancelled.get()) "gesetzt" else "nicht gesetzt"}" +
                (if (runStartedAt > 0) " · Dienst lief ${System.currentTimeMillis() - runStartedAt} ms"
                else " · keine Laufzeit erfasst") +
                " · Worker ${if (worker?.isAlive == true) "noch aktiv" else "beendet"}"
        )
        cancelled.set(true)
        JobBus.removeListener(this)
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.steimerbyte.bulkcompressor.action.START"
        const val ACTION_CANCEL = "com.steimerbyte.bulkcompressor.action.CANCEL"
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOC_IDS = "doc_ids"
        private const val CHANNEL_ID = "compression"

        /** Der Geraetespeicher wird hoechstens alle zwei Sekunden erfragt. */
        private const val DEVICE_RAM_REFRESH_MS = 2_000L
        private const val NOTIFICATION_ID = 4711

        /** Zeitlimit des Wachhunds: danach gibt Android die Sperre selbst frei. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /**
         * Erneuerungsabstand. Deutlich unter dem Zeitlimit, damit ein
         * einzelner verpasster Durchlauf die Sperre nicht ablaufen laesst.
         */
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 1000L

        fun start(context: Context, treeUri: Uri, docIds: List<String>) {
            val intent = Intent(context, CompressionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TREE, treeUri.toString())
                .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(docIds))
            // ContextCompat faellt unter Android 8 auf startService zurueck -
            // startForegroundService gibt es erst ab API 26.
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Setzt einen vom System beendeten Lauf fort. Aufgerufen beim
         * Start der Oberflaeche: ohne diesen Aufruf bliebe von einem
         * 600-Dateien-Lauf nach einem Prozessabbruch nur die Erinnerung
         * des Nutzers uebrig.
         */
        fun resumeIfInterrupted(context: Context): RunJournal.Snapshot? {
            if (JobBus.state.running) return null
            val snapshot = RunJournal.load(context) ?: return null
            val reason = when {
                snapshot.finished -> "war abgeschlossen"
                snapshot.remaining.isEmpty() -> "hatte keine offenen Dateien"
                snapshot.ageMs() > RunJournal.MAX_RESUME_AGE_MS -> "ist zu alt"
                else -> null
            }
            if (reason != null) {
                runCatching { RunJournal.fileFor(context).delete() }
                Log.i("Service", "Journal $reason - nichts fortzusetzen")
                return null
            }
            Log.i("Service", "Unterbrochener Lauf gefunden: ${snapshot.describe()} · wird fortgesetzt")
            JobBus.appendLog("WIEDERAUFNAHME: ${snapshot.describe()}")
            start(context, Uri.parse(snapshot.treeUri), snapshot.remaining)
            return snapshot
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }
    }
}
