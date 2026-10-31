package com.steimerbyte.bulkcompressor.log

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Zentrale Log-Fassade der App.
 *
 * Drei Senken, alle muessen mit derselben Reihenfolge arbeiten:
 *  1. Ringpuffer (sofort, im Aufrufer-Thread) - Vorrat fuer das
 *     Terminal-Panel und fuer den Absturzbericht.
 *  2. Datei (ueber genau einen Daemon-Thread) - damit ein langsamer
 *     Speicher nie den Kompressor blockiert.
 *  3. `android.util.Log` (sofort) - fuer `adb logcat` mit demselben Tag.
 *
 * Keine oeffentliche Methode wirft: intern laeuft alles in `runCatching`.
 * Ein fehlgeschlagener Logschreibvorgang darf keinen Kompressionslauf
 * abbrechen.
 */
object Log {

    /** 2000 Zeilen: genug Kontext fuer den Absturzbericht, klein im Speicher. */
    private const val RING_CAPACITY = RingBuffer.DEFAULT_CAPACITY

    /** So viele Zeilen der letzten Sitzung wandern beim Start in den Ring. */
    private const val CARRY_OVER_LINES = 200

    private const val SESSION_TAG = "Session"
    private const val SESSION_LINE = "---- Sitzung gestartet ----"
    private const val TERMINAL_TITLE = "=== Bulk Compressor Log ==="

    /** So viele Zeichen des Absturzberichts gehen in den Export. */
    private const val CRASH_EXPORT_LIMIT = 64_000

    /** Serialisiert Reihenfolge: Ring, Dateiauftrag und Listener-Zustellung. */
    private val gate = Any()

    private val ring = RingBuffer(RING_CAPACITY)

    /**
     * CopyOnWrite, weil Listener fast nur gelesen werden und `add`/`remove`
     * waehrend der Zustellung nicht in die Iteration eingreifen darf.
     */
    private val listeners = CopyOnWriteArrayList<(LogLine) -> Unit>()

    /**
     * Laufende Nummer ueber den ganzen Prozess. AtomicLong statt Zaehler
     * unter [gate], damit die Nummer auch dann eindeutig ist, wenn ein
     * Aufruf vor dem Init hereinkommt.
     */
    private val sequence = AtomicLong(0L)

    /**
     * Zeitstempel der zuletzt geschriebenen Zeile. Nur unter [gate] gelesen
     * und geschrieben; haelt die Anzeige auch bei einer Uhrkorrektur
     * aufsteigend.
     */
    private var lastTimestamp = 0L

    @Volatile
    private var file: LogFile? = null

    @Volatile
    private var writer: ExecutorService? = null

    @Volatile
    private var appVersion: String = "unbekannt"

    @Volatile
    private var handlerResolved = false

    @Volatile
    private var mainHandler: Handler? = null

    /**
     * Richtet Dateisenke und Daemon-Thread ein. Mehrfachaufrufe sind
     * wirkungslos - die Activity und die Application rufen beide.
     */
    fun init(context: Context) {
        // Schneller Weg ohne Monitor: nach dem ersten Lauf ist nichts zu tun.
        if (file != null) return
        runCatching {
            synchronized(gate) {
                if (file == null) setUp(context)
            }
        }
    }

    /** Einmalige Einrichtung; laeuft unter [gate]. */
    private fun setUp(context: Context) {
        val app = context.applicationContext ?: context
        appVersion = readVersion(app)

        val dir = File(app.filesDir, "logs")
        if (!dir.isDirectory) dir.mkdirs()

        val logFile = LogFile(dir)
        // Erst die Senke setzen, damit die Trennzeile schon in die Datei
        // geht; der Thread existiert als Daemon und stirbt mit dem Prozess.
        file = logFile
        writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "log-writer").apply {
                isDaemon = true
                // Logging ist nie dringend: der Kompressor hat Vorrang.
                priority = Thread.MIN_PRIORITY
            }
        }

        // Kontext der letzten Sitzung zurueckholen. Nur in den Ring, nicht
        // erneut in die Datei - sonst verdoppelt sich der Bestand bei jedem
        // Start und die Rotation greift zu frueh.
        val fallback = System.currentTimeMillis()
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { isLenient = false }
        for (text in logFile.readTail(CARRY_OVER_LINES)) {
            ring.add(
                LogLine(
                    seq = sequence.getAndIncrement(),
                    // Die Uhrzeit der Zeile selbst uebernehmen, sonst tragen
                    // alle Zeilen der letzten Sitzung den Neustartzeitpunkt
                    // und das Panel zeigt eine falsche Uhrzeit an.
                    timeMs = carriedTime(text, clock, fallback),
                    level = LogLevel.I,
                    tag = SESSION_TAG,
                    message = carriedMessage(text)
                )
            )
        }

        emit(LogLevel.I, SESSION_TAG, SESSION_LINE)
    }

    /**
     * Die Rohzeile aus der Datei beginnt mit ihrem eigenen Zeitstempel. Der
     * wird entfernt, damit das Panel pro Zeile nur eine Uhrzeit zeigt; die
     * urspruengliche Stufe und der Tag bleiben als Text erhalten.
     */
    private fun carriedMessage(raw: String): String =
        if (raw.length > 13 && raw[12] == ' ') raw.substring(13) else raw

    /**
     * Uhrzeit aus dem Zeilenkopf als Zeitstempel. Nur die Tageszeit ist
     * darstellbar, deshalb liegt das Datum auf dem Epochennullpunkt - das
     * Format zeigt ohnehin nur `HH:mm:ss.SSS`.
     */
    private fun carriedTime(raw: String, clock: SimpleDateFormat, fallback: Long): Long {
        if (raw.length < 13 || raw[12] != ' ') return fallback
        return runCatching { clock.parse(raw.substring(0, 12))?.time ?: fallback }
            .getOrDefault(fallback)
    }

    fun v(tag: String, message: String) {
        runCatching { emit(LogLevel.V, tag, message) }
    }

    fun d(tag: String, message: String) {
        runCatching { emit(LogLevel.D, tag, message) }
    }

    fun i(tag: String, message: String) {
        runCatching { emit(LogLevel.I, tag, message) }
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        runCatching { emit(LogLevel.W, tag, message, error) }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        runCatching { emit(LogLevel.E, tag, message, error) }
    }

    /** Die letzten [limit] Zeilen, aelteste zuerst. */
    fun snapshot(limit: Int = 500): List<LogLine> =
        runCatching { ring.snapshot(limit) }.getOrDefault(emptyList())

    /**
     * Haengt einen Beobachter an. Er wird auf dem Main-Thread aufgerufen -
     * so darf er direkt in Views schreiben. Wirft er eine Exception, wird er
     * entfernt; ein kaputter Beobachter darf die Logkette nicht dauerhaft
     * stoeren.
     */
    fun addListener(listener: (LogLine) -> Unit) {
        runCatching { listeners.addIfAbsent(listener) }
    }

    fun removeListener(listener: (LogLine) -> Unit) {
        runCatching { listeners.remove(listener) }
    }

    /** Vollstaendiger Bericht fuer die Zwischenablage oder eine ZIP-Datei. */
    fun exportText(): String = runCatching {
        buildString {
            append(TERMINAL_TITLE).append('\n')
            append("Version: ").append(appVersion).append('\n')
            append("Geraet: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append('\n')
            for (line in ring.snapshot(RING_CAPACITY)) append(line.format()).append('\n')
            appendCrashSection(this)
        }
    }.getOrDefault(TERMINAL_TITLE)

    /**
     * Haengt den letzten Absturzbericht an den Bericht an.
     *
     * Nach einem Absturz startet die App neu, der Ringpuffer enthaelt dann
     * nur noch die letzten Zeilen der alten Sitzung - der Stacktrace steckt
     * ausschliesslich in `crash.log`. Ohne diesen Abschnitt ginge er beim
     * Weiterreichen verloren.
     */
    private fun appendCrashSection(out: StringBuilder) {
        val crash = runCatching { file?.crashFile() }.getOrNull() ?: return
        if (!crash.isFile || crash.length() == 0L) return
        val text = runCatching { crash.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        out.append('\n')
        out.append("=== Letzter Absturzbericht (crash.log) ===").append('\n')
        // Nur das Ende: bei mehreren Abstuerzen waechst die Datei beliebig.
        out.append(if (text.length > CRASH_EXPORT_LIMIT) text.takeLast(CRASH_EXPORT_LIMIT) else text)
        if (!text.endsWith('\n')) out.append('\n')
    }

    fun appLogFile(): File? = runCatching { file?.current() }.getOrNull()

    fun crashLogFile(): File? = runCatching { file?.crashFile() }.getOrNull()

    /** Leert Ringpuffer und Logdateien; das Absturzlog geht mit. */
    fun clear() {
        runCatching { ring.clear() }
        val executor = writer
        if (executor == null) {
            runCatching { file?.clear() }
            return
        }
        runCatching { executor.execute { runCatching { file?.clear() } } }
    }

    /**
     * Gemeinsamer Schreibpfad aller Stufen.
     *
     * Reihenfolge: zuerst die Nummer, dann - unter [gate] - Ring, Datei-
     * Auftrag und Listener-Zustellung. Der Monitor macht daraus eine
     * Critical Section: die Datei sieht die Zeilen in Ringreihenfolge, und
     * die Zustellung an den Main-Thread wird in derselben Reihenfolge
     * eingereiht, in der die Zeilen in den Ring kamen. Beobachter-Code
     * selbst laeuft nie unter dem Monitor, sondern im Main-Thread-Runnable.
     */
    private fun emit(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) message else message + "\n" + error.stackTraceToString()

        // Spiegeln nach logcat: genau ein Aufruf pro Logzeile, mit dem Tag
        // des Aufrufers. Mehrzeilige Nachrichten zerlegt logcat selbst.
        runCatching { AndroidLog.println(priorityOf(level), tag, text) }

        val handler = mainHandlerOrNull()
        val line = synchronized(gate) {
            // Nie rueckwaerts springen, auch wenn die Systemuhr korrigiert
            // wird: sonst steht im Panel eine Zeile vor ihrer Vorgaengerin.
            val now = System.currentTimeMillis()
            val timeMs = if (now > lastTimestamp) now else lastTimestamp
            lastTimestamp = timeMs

            val entry = LogLine(
                seq = sequence.getAndIncrement(),
                timeMs = timeMs,
                level = level,
                tag = tag,
                message = text
            )
            ring.add(entry)
            // Datei-I/O nie im Aufrufer: ein Auftrag pro Zeile, abgearbeitet
            // in genau der Reihenfolge, in der er eingereiht wurde.
            writer?.execute { runCatching { file?.append(listOf(entry)) } }
            if (handler != null) handler.post { deliver(entry) }
            entry
        }
        // Ohne Looper (JVM-Test, sehr frueher Aufruf) direkt zustellen.
        if (handler == null) deliver(line)
    }

    private fun deliver(line: LogLine) {
        for (listener in listeners) {
            try {
                listener(line)
            } catch (t: Throwable) {
                listeners.remove(listener)
            }
        }
    }

    /**
     * Main-Looper einmal aufloesen. Die Suche kann in einer Umgebung ohne
     * Android-Runtime scheitern; dann gibt es schlicht keinen Main-Thread,
     * und die Zustellung laeuft direkt.
     */
    private fun mainHandlerOrNull(): Handler? {
        if (handlerResolved) return mainHandler
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        mainHandler = handler
        handlerResolved = true
        return handler
    }

    private fun priorityOf(level: LogLevel): Int = when (level) {
        LogLevel.V -> AndroidLog.VERBOSE
        LogLevel.D -> AndroidLog.DEBUG
        LogLevel.I -> AndroidLog.INFO
        LogLevel.W -> AndroidLog.WARN
        LogLevel.E -> AndroidLog.ERROR
    }

    /** "1.0.0 (1)" aus dem PackageManager, sonst "unbekannt". */
    @Suppress("DEPRECATION")
    private fun readVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "$name ($code)"
    }.getOrDefault("unbekannt")
}
