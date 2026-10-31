package com.steimerbyte.bulkcompressor.log

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Letzter Schreibvorgang vor dem Prozess-Tod.
 *
 * Der Standard-Handler wird ersetzt, nicht verdraengt: nach dem eigenen
 * Schreiben laeuft der vorherige Handler weiter, damit Android den Absturz
 * wie gewohnt meldet. Ein verschluckter Fehler waere hier fatal - die App
 * bliebe in einem kaputten Zustand stehen.
 *
 * Bewusst synchron und ohne Executor: beim unbehandelten Absturz wird der
 * Prozess sofort beendet, ein Daemon-Thread kaeme nie zum Schreiben.
 */
object CrashGuard {

    /** So viele Ringpuffer-Zeilen landen im Bericht. */
    private const val RING_LINES = 200

    @Volatile
    private var installed = false

    @Volatile
    private var logDir: File? = null

    private val guard = Any()

    fun install(context: Context) {
        if (installed) return
        runCatching {
            synchronized(guard) {
                if (!installed) installLocked(context)
            }
        }
    }

    /** Setzt den Handler; laeuft unter [guard]. */
    private fun installLocked(context: Context) {
        val app = context.applicationContext ?: context
        // Eigene Senke statt Log: der Log-Schreib-Thread kann beim Absturz
        // schon tot oder blockiert sein.
        logDir = File(app.filesDir, "logs")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeReport(app, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // Ohne Vorgaenger muss der Prozess selbst beendet werden,
                // sonst laeuft ein toter Thread weiter.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        installed = true
    }

    /**
     * Kopfzeile, Ringpuffer-Auszug und vollstaendiger Stacktrace in
     * `crash.log` anhaengen. Mehrere Abstuerze stehen hintereinander in
     * derselben Datei.
     */
    private fun writeReport(context: Context, thread: Thread, error: Throwable) {
        val dir = logDir ?: File(context.filesDir, "logs")
        val sink = LogFile(dir)
        sink.appendCrash(
            header = buildHeader(context, thread),
            ring = Log.snapshot(RING_LINES),
            stackTrace = stackTraceOf(error)
        )
    }

    private fun buildHeader(context: Context, thread: Thread): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val version = versionLabel(context)
        return buildString {
            append("==== ABSTURZ ").append(time).append(" ====\n")
            append("Version:  ").append(version).append('\n')
            append("Geraet:   ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android:  ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread:   ").append(thread.name).append('\n')
            append("PID:      ").append(android.os.Process.myPid()).append('\n')
        }
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "$name ($code)"
    }.getOrDefault("unbekannt")

    private fun stackTraceOf(error: Throwable): String = runCatching {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        writer.toString()
    }.getOrDefault(error.toString())
}
