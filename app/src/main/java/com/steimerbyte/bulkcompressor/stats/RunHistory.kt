package com.steimerbyte.bulkcompressor.stats

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ein abgeschlossener Lauf, so knapp wie moeglich.
 *
 * Fuenf Felder, und alle werden auch angezeigt: die Startzeit als
 * Run-ID, der Ordner, die Zahl der Dateien im Auftrag, davon die Zahl,
 * die tatsaechlich kleiner geworden ist, und die gesparten Bytes. Kein
 * Preset, keine Dauer, keine Einzeldateien - das steht im Log, und es
 * wuerde diese Datei bei jedem Lauf um ein Vielfaches verlaengern.
 */
data class RunRecord(
    /** Startzeit in Millisekunden - eindeutig und ohne eigene Zaehlung. */
    val id: Long,
    val folder: String,
    val files: Int,
    val savedFiles: Int,
    val savedBytes: Long
)

/** Summe ueber alle Laeufe, auch ueber die aus der Datei gefallenen. */
data class HistoryTotals(
    val runs: Int = 0,
    val files: Long = 0,
    val savedFiles: Long = 0,
    val savedBytes: Long = 0
) {
    /** Was ein Lauf im Schnitt gebracht hat; 0 statt Division durch 0. */
    val bytesPerRun: Long get() = if (runs <= 0) 0 else savedBytes / runs
}

data class History(
    val totals: HistoryTotals = HistoryTotals(),
    val runs: List<RunRecord> = emptyList()
) {
    val isEmpty: Boolean get() = totals.runs == 0
    /** Neuester Lauf zuerst - so steht es auch auf dem Bildschirm. */
    val newestFirst: List<RunRecord> get() = runs.asReversed()
}

/**
 * Macht aus dem Endzustand eines Laufs einen Eintrag - oder `null`, wenn
 * es nichts zu berichten gibt.
 *
 * Die Regel steht hier und nicht im Dienst, weil sie die einzige Stelle
 * ist, an der sich die Statistik selbst beluegen koennte: eine
 * uebersprungene oder gescheiterte Datei ist *nicht* kleiner geworden,
 * auch wenn der Lauf als Ganzes durch ist. Ein Lauf ohne eine einzige
 * bearbeitete Datei (sofort abgebrochen, Ordner leer) bekommt gar keinen
 * Eintrag - sonst fuellt ein Missverstaendnis die Liste mit Nullen.
 */
fun runRecord(
    id: Long,
    folder: String,
    total: Int,
    done: Int,
    skipped: Int,
    failed: Int,
    savedBytes: Long
): RunRecord? {
    if (total <= 0 || done <= 0) return null
    return RunRecord(
        id = id,
        folder = folder,
        files = total,
        savedFiles = (done - skipped - failed).coerceAtLeast(0),
        savedBytes = savedBytes.coerceAtLeast(0)
    )
}

/**
 * Laeufe in einer kleinen JSON-Datei im app-privaten Speicher.
 *
 * Warum JSON und nicht SQLite: es geht um eine Handvoll Zahlen je Lauf.
 * Eine Datenbank waere mehr Code, mehr Abhaengigkeiten und mehr, was
 * schiefgehen kann - und die Datei muesste trotzdem migriert werden.
 *
 * Warum die Datei nicht waechst: die Liste ist auf [MAX_RUNS] Laeufe
 * begrenzt, und die Summen stehen getrennt davon in `t`. Aeltere Laeufe
 * fallen aus der Liste, zaehlen aber weiter in der Summe - die Datei
 * bleibt dadurch dauerhaft bei wenigen Kilobyte, waehrend "insgesamt
 * gespart" trotzdem ueber die ganze Lebenszeit der App stimmt.
 *
 * Kuerzel statt ausgeschriebener Schluessel: bei 100 Laeufen faellt der
 * Unterschied zwischen `{"id":…,"folder":…}` und `{"i":…,"f":…}` ins
 * Gewicht, lesbar bleibt beides.
 */
class RunHistory(private val file: File) {

    /**
     * Ein Schloss je Datei, nicht je Objekt.
     *
     * Der Dienst schreibt und die Oberflaeche liest - das sind zwei
     * Instanzen auf zwei Threads. Mit einem Schloss je Instanz wuerde ein
     * Lesen-Schreiben-Paar (lesen, einen Lauf anhaengen, schreiben) des
     * Dienstes mit einem gleichzeitigen Lesevorgang verschraenken, und ein
     * zweiter Schreiber koennte den Eintrag des ersten ueberschreiben.
     */
    private val lock: Any = locks.computeIfAbsent(file.absolutePath) { Any() }

    fun load(): History = synchronized(lock) { read() }

    /**
     * Haengt einen Lauf an und schreibt sofort. Doppelte Run-IDs werden
     * uebergangen: der Dienst meldet einen Abschluss genau einmal, aber
     * ein Neustart des Dienstes darf denselben Lauf nicht doppelt
     * zaehlen.
     */
    fun record(run: RunRecord): History = synchronized(lock) {
        val current = read()
        if (current.runs.any { it.id == run.id }) return@synchronized current
        val trimmed = (current.runs + run).takeLast(MAX_RUNS)
        val totals = current.totals.let {
            HistoryTotals(
                runs = it.runs + 1,
                files = it.files + run.files,
                savedFiles = it.savedFiles + run.savedFiles,
                savedBytes = it.savedBytes + run.savedBytes
            )
        }
        val next = History(totals = totals, runs = trimmed)
        write(next)
        next
    }

    /** Alles loeschen - fuer den Fall, dass jemand die Statistik zuruecksetzen will. */
    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        Unit
    }

    private fun read(): History = runCatching {
        if (!file.exists()) return History()
        val root = JSONObject(file.readText())
        val runs = ArrayList<RunRecord>()
        val array = root.optJSONArray(KEY_RUNS) ?: JSONArray()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            runs.add(
                RunRecord(
                    id = entry.optLong(KEY_ID),
                    folder = entry.optString(KEY_FOLDER),
                    files = entry.optInt(KEY_FILES),
                    savedFiles = entry.optInt(KEY_SAVED_FILES),
                    savedBytes = entry.optLong(KEY_BYTES)
                )
            )
        }
        val totals = root.optJSONObject(KEY_TOTALS)
        History(
            totals = if (totals == null) totalsOf(runs) else HistoryTotals(
                runs = totals.optInt(KEY_TOTALS_RUNS, runs.size),
                files = totals.optLong(KEY_TOTALS_FILES),
                savedFiles = totals.optLong(KEY_TOTALS_SAVED_FILES),
                savedBytes = totals.optLong(KEY_TOTALS_BYTES)
            ),
            runs = runs.sortedBy { it.id }
        )
    }.getOrElse {
        // Eine halb geschriebene oder fremde Datei darf die App nicht
        // aufhalten: dann steht die Statistik eben bei null.
        History()
    }

    private fun write(history: History) {
        val root = JSONObject()
        root.put(KEY_VERSION, FORMAT)
        root.put(
            KEY_TOTALS,
            JSONObject()
                .put(KEY_TOTALS_RUNS, history.totals.runs)
                .put(KEY_TOTALS_FILES, history.totals.files)
                .put(KEY_TOTALS_SAVED_FILES, history.totals.savedFiles)
                .put(KEY_TOTALS_BYTES, history.totals.savedBytes)
        )
        val array = JSONArray()
        for (run in history.runs) {
            array.put(
                JSONObject()
                    .put(KEY_ID, run.id)
                    .put(KEY_FOLDER, run.folder.take(MAX_FOLDER_CHARS))
                    .put(KEY_FILES, run.files)
                    .put(KEY_SAVED_FILES, run.savedFiles)
                    .put(KEY_BYTES, run.savedBytes)
            )
        }
        root.put(KEY_RUNS, array)

        // Erst daneben schreiben, dann umbenennen: ein Stromausfall
        // mitten im Schreiben laesst so die alte Datei heil.
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun totalsOf(runs: List<RunRecord>) = HistoryTotals(
        runs = runs.size,
        files = runs.sumOf { it.files.toLong() },
        savedFiles = runs.sumOf { it.savedFiles.toLong() },
        savedBytes = runs.sumOf { it.savedBytes }
    )

    companion object {
        /** Ein Schloss je Pfad: alle Instanzen auf dieselbe Datei teilen es. */
        private val locks = java.util.concurrent.ConcurrentHashMap<Any, Any>()

        private const val FORMAT = 1
        private const val KEY_VERSION = "v"
        private const val KEY_TOTALS = "t"
        private const val KEY_RUNS = "runs"
        private const val KEY_ID = "i"
        private const val KEY_FOLDER = "f"
        private const val KEY_FILES = "n"
        private const val KEY_SAVED_FILES = "s"
        private const val KEY_BYTES = "b"
        private const val KEY_TOTALS_RUNS = "r"
        private const val KEY_TOTALS_FILES = "n"
        private const val KEY_TOTALS_SAVED_FILES = "s"
        private const val KEY_TOTALS_BYTES = "b"

        /** So viele Laeufe werden behalten; aeltere zaehlen nur in der Summe. */
        const val MAX_RUNS = 100

        /** Ein SAF-Pfad kann beliebig tief sein; der Anfang reicht zum Erkennen. */
        const val MAX_FOLDER_CHARS = 120

        fun of(context: Context): RunHistory =
            RunHistory(File(context.filesDir, "run-history.json"))
    }
}
