package com.steimerbyte.bulkcompressor.job

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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.steimerbyte.bulkcompressor.MainActivity
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.scan.SafScanner
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
                if (worker?.isAlive == true) {
                    Log.d("Service", "Start ignoriert: es laeuft bereits ein Auftrag")
                    return START_NOT_STICKY
                }
                val tree = intent.getStringExtra(EXTRA_TREE)
                val docIds = intent.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
                if (tree == null || docIds.isEmpty()) {
                    Log.w(
                        "Service",
                        "Start ohne Ordner oder Dateien verworfen (tree=$tree, Dateien=${docIds.size})"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i("Service", "Start: ${docIds.size} Dateien · $tree")
                startForegroundNow()
                launchWorker(Uri.parse(tree), docIds)
            }
            ACTION_CANCEL -> {
                Log.i("Service", "Abbruch angefordert")
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

    private fun launchWorker(treeUri: Uri, docIds: List<String>) {
        cancelled.set(false)
        acquireWakeLock()
        val thread = Thread({
            try {
                // Der Ordner wird erneut gelesen: damit stimmen Groesse,
                // Zeitstempel und Ordnerzuordnung auch dann, wenn
                // zwischendurch etwas geaendert wurde.
                val scan = SafScanner(contentResolver).scan(treeUri)
                val wanted = docIds.toHashSet()
                val items = scan.items.filter { it.docId in wanted }
                Log.d("Service", "Scan: ${scan.items.size} Dateien in ${scan.folderCount} Ordnern")
                if (items.size != docIds.size) {
                    Log.w(
                        "Service",
                        "Nur ${items.size} von ${docIds.size} gewaehlten Dateien wiedergefunden"
                    )
                }
                val settings = CompressSettings.load(
                    getSharedPreferences(CompressSettings.PREFS, MODE_PRIVATE)
                )
                CompressionJob(applicationContext, treeUri, items, settings, cancelled).run()
            } catch (t: Throwable) {
                JobBus.appendLog("ABBRUCH: ${t.message ?: t.javaClass.simpleName}")
                Log.e("Service", "Lauf abgebrochen", t)
                JobBus.publish(
                    JobBus.state.copy(running = false, finished = true, overall = 0f)
                )
            } finally {
                releaseWakeLock()
                finishForeground()
            }
        }, "bulk-compress")
        worker = thread
        thread.start()
    }

    // ------------------------------------------------------------------
    // Benachrichtigung
    // ------------------------------------------------------------------

    override fun onState(state: JobBus.State) {
        if (state.finished || !state.running) return
        val now = System.currentTimeMillis()
        val progressChanged = (state.overall * 100).toInt()
        if (now - lastNotificationUpdate < 400 && progressChanged % 5 != 0) return
        lastNotificationUpdate = now
        notify(
            text = state.currentName.ifBlank { "Vorbereitung ..." },
            subText = "${state.done} / ${state.total}",
            percent = (state.overall * 100).toInt(),
            showProgress = true
        )
    }

    @Suppress("DEPRECATION")
    private fun startForegroundNow() {
        // Ohne POST_NOTIFICATIONS (ab Android 13) laeuft der Dienst
        // trotzdem; dann ist eben nur keine Benachrichtigung sichtbar.
        val notification = buildNotification("Startet ...", null, 0, true)
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

    private fun finishForeground() {
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
        stopSelf()
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
            Log.w("Service", "Wachhund konnte nicht erworben werden", t)
            throw t
        }
        if (lock.isHeld) {
            Log.d("Service", "Wachhund erworben (${WAKE_LOCK_TIMEOUT_MS / 60_000} min)")
        } else {
            Log.w("Service", "Wachhund nach acquire nicht gehalten - Lauf kann einfrieren")
        }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock == null) return
        if (!lock.isHeld) {
            Log.w("Service", "Wachhund war nicht mehr gehalten - Zeitlimit abgelaufen?")
            return
        }
        runCatching { lock.release() }
            .onFailure { Log.w("Service", "Wachhund liess sich nicht freigeben", it) }
    }

    override fun onDestroy() {
        Log.i("Service", "onDestroy")
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
        private const val NOTIFICATION_ID = 4711

        /** Zeitlimit des Wachhunds: danach gibt Android die Sperre selbst frei. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context, treeUri: Uri, docIds: List<String>) {
            val intent = Intent(context, CompressionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TREE, treeUri.toString())
                .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(docIds))
            // ContextCompat faellt unter Android 8 auf startService zurueck -
            // startForegroundService gibt es erst ab API 26.
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }
    }
}
