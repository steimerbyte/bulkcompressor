package com.steimerbyte.bulkcompressor.log

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logdatei im oeffentlichen Download-Ordner, damit sie ohne Kabel und ohne
 * adb auf den Rechner geholt werden kann.
 *
 * Ab Android 10 laeuft das ueber `MediaStore.Downloads`: eine eigene Datei
 * im Download-Ordner anzulegen braucht **keine** Berechtigung. Android 7-9
 * haben diesen Weg nicht - dort wird der Ordner direkt beschrieben und
 * `WRITE_EXTERNAL_STORAGE` verlangt (im Manifest auf maxSdkVersion 28
 * begrenzt).
 *
 * Eine Sitzung = eine Datei. Waechst sie ueber die Groessengrenze, entsteht
 * ein weiterer Teil (`-teil2`). Beim Oeffnen werden alte Dateien geloescht,
 * damit der Download-Ordner nicht volllaeuft.
 */
object DownloadsLog {

    /** Unterordner in Download/. */
    const val FOLDER = "BulkCompressor"

    private const val MIME = "text/plain"
    private const val PREFIX = "bulkcompressor-"
    private const val CRASH_PREFIX = "bulkcompressor-crash-"

    /**
     * Ab dieser Menge beginnt ein neuer Teil. Gezaehlt werden Zeichen; das
     * Log ist ASCII, damit entspricht das den Bytes.
     */
    const val MAX_CHARS_PER_FILE = 8L * 1024 * 1024

    /** Hoechstzahl der Teile einer Sitzung. */
    private const val MAX_PARTS = 10

    /** So viele Dateien bleiben liegen. */
    private const val KEEP_FILES = 12

    /** Und so alt duerfen sie hoechstens werden (Tage). */
    private const val KEEP_DAYS = 21

    /** Anzeigepfad fuer Terminal und Export. */
    @JvmStatic
    fun displayPath(name: String): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"

    /** Ordner, in dem die Logs liegen - fuer Meldungen an den Nutzer. */
    @JvmStatic
    fun folderPath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    /** Dateiname der Sitzung; fest im Test pruefbar. */
    @JvmStatic
    fun sessionName(startedAt: Long, part: Int = 1): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        return if (part <= 1) "$PREFIX$stamp.log" else "$PREFIX$stamp-teil$part.log"
    }

    @JvmStatic
    fun crashName(at: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "$CRASH_PREFIX$stamp.log"
    }

    /**
     * Oeffnet die Sitzungsdatei und raeumt alte Dateien weg.
     * @return null, wenn kein Ziel beschreibbar ist - dann bleibt nur der
     *         app-private Speicher.
     */
    @JvmStatic
    fun open(context: Context): Session? {
        val app = context.applicationContext ?: context
        val startedAt = System.currentTimeMillis()
        val session = runCatching {
            Session(startedAt, MAX_CHARS_PER_FILE) { name -> openSink(app, name) }
        }.getOrNull()?.takeIf { it.isWritable } ?: return null
        runCatching { cleanUp(app) }
        return session
    }

    /**
     * Sitzung in einem beliebigen Ordner - gleiche Teile- und Pfadlogik wie
     * am Geraet, nur ohne MediaStore. Wird vom Test benutzt.
     */
    @JvmStatic
    @JvmOverloads
    fun openFileSession(
        dir: File,
        startedAt: Long,
        maxChars: Long = MAX_CHARS_PER_FILE
    ): Session = Session(startedAt, maxChars) { name -> fileSink(dir, name) }

    /** Schreibt einen Absturzbericht sofort in eine eigene Datei. */
    @JvmStatic
    fun writeCrash(context: Context, report: String): String? {
        val app = context.applicationContext ?: context
        val name = crashName(System.currentTimeMillis())
        val sink = runCatching { openSink(app, name) }.getOrNull() ?: return null
        return runCatching {
            sink.write(if (report.endsWith('\n')) report else report + "\n")
            sink.close()
            sink.path
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Sitzung
    // ------------------------------------------------------------------

    /**
     * Eine offene Sitzungsdatei. Keine Methode wirft nach aussen -
     * Loggen darf nie wichtiger sein als der Kompressor.
     */
    class Session internal constructor(
        private val startedAt: Long,
        private val maxChars: Long,
        private val opener: (name: String) -> Sink?
    ) {
        private var part = 1
        private var written = 0L
        private var sink: Sink? = runCatching { opener(sessionName(startedAt, 1)) }.getOrNull()
        private val opened = mutableListOf<String>()

        init {
            sink?.let { opened.add(it.path) }
        }

        val isWritable: Boolean get() = sink != null

        /** Anzeigepfad der aktuellen Datei; null, wenn nichts beschreibbar ist. */
        val path: String? get() = sink?.path

        /** Jede Datei dieser Sitzung, in Reihenfolge. */
        val paths: List<String> get() = opened.toList()

        fun append(text: String) {
            if (!isWritable) return
            if (written >= maxChars && part < MAX_PARTS) roll()
            val current = sink ?: return
            try {
                current.write(text)
                written += text.length
            } catch (t: Throwable) {
                // Kanal verloren (z.B. MediaStore raeumt auf): einmal neu
                // oeffnen. Klappt auch das nicht, ist das Log ab hier still.
                if (runCatching { current.reopen() }.getOrDefault(false)) {
                    runCatching { current.write(text) }
                    written += text.length
                } else {
                    sink = null
                }
            }
        }

        fun flush() {
            runCatching { sink?.flush() }
        }

        fun close() {
            runCatching { sink?.flush() }
            runCatching { sink?.close() }
            sink = null
        }

        /** Neuer Teil, weil die aktuelle Datei zu gross wird. */
        private fun roll() {
            runCatching { sink?.close() }
            val next = part + 1
            val fresh = runCatching { opener(sessionName(startedAt, next)) }.getOrNull()
            if (fresh == null) return
            part = next
            sink = fresh
            written = 0L
            opened.add(fresh.path)
        }
    }

    /**
     * Ein beschreibbares Ziel. Der [opener] liefert beim Konstruktor den
     * ersten Kanal und bei [reopen] einen neuen auf denselben Pfad.
     */
    class Sink(val path: String, private val opener: () -> Writer?) {

        private var writer: Writer? = runCatching { opener() }.getOrNull()

        val isOpen: Boolean get() = writer != null

        fun write(text: String) {
            val out = writer ?: throw IOException("Sink nicht offen: $path")
            out.write(text)
        }

        fun flush() {
            runCatching { writer?.flush() }
        }

        fun close() {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }

        fun reopen(): Boolean {
            runCatching { writer?.close() }
            writer = runCatching { opener() }.getOrNull()
            return writer != null
        }
    }

    // ------------------------------------------------------------------
    // Ziele
    // ------------------------------------------------------------------

    private fun openSink(context: Context, name: String): Sink? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreSink(context, name)
        } else {
            legacyDir(context)?.let { fileSink(it, name) }
        }

    /** Android 10+: Dokument in MediaStore.Downloads, ohne Berechtigung. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreSink(context: Context, name: String): Sink? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }
        val uri: Uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        var first = true
        val sink = Sink(displayPath(name)) {
            val mode = if (first) "w" else "wa"
            val opened = runCatching { resolver.openOutputStream(uri, mode) }.getOrNull()
            first = false
            opened?.let { OutputStreamWriter(it, Charsets.UTF_8) as Writer }
        }
        if (!sink.isOpen) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return sink
    }

    /** Ordner `Download/BulkCompressor` als echtes Verzeichnis (API < 29). */
    @Suppress("DEPRECATION")
    private fun legacyDir(context: Context): File? {
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER
        )
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    /**
     * Schreibt in eine echte Datei. Der erste Kanal legt die Datei neu an,
     * ein spaeterer Kanal haengt an, damit ein Neuoefnen nichts wegwirft.
     */
    internal fun fileSink(dir: File, name: String): Sink {
        var first = true
        return Sink(displayPath(name)) {
            if (!dir.isDirectory && !dir.mkdirs()) return@Sink null
            val append = !first
            first = false
            runCatching {
                OutputStreamWriter(FileOutputStream(File(dir, name), append), Charsets.UTF_8) as Writer
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // Aufraeumen
    // ------------------------------------------------------------------

    private fun cleanUp(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cleanUpMediaStore(context)
        } else {
            cleanUpFiles(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanUpMediaStore(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val cursor = runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%${FOLDER}%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )
        }.getOrNull() ?: return
        val doomed = mutableListOf<Uri>()
        runCatching {
            cursor.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                var index = 0
                while (c.moveToNext()) {
                    val logName = c.getString(nameColumn) ?: continue
                    if (!logName.startsWith(PREFIX)) continue
                    index++
                    if (index > KEEP_FILES) {
                        doomed.add(Uri.withAppendedPath(collection, c.getLong(idColumn).toString()))
                    }
                }
            }
        }
        for (item in doomed) runCatching { resolver.delete(item, null, null) }
    }

    @Suppress("DEPRECATION")
    private fun cleanUpFiles(context: Context) {
        val dir = runCatching { legacyDir(context) }.getOrNull() ?: return
        val logs = runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isFile && it.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
        logs.forEachIndexed { index, file ->
            if (index >= KEEP_FILES || file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
