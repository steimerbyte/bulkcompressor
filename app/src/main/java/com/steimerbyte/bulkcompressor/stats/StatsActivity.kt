package com.steimerbyte.bulkcompressor.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.ui.SegmentBar
import com.steimerbyte.bulkcompressor.ui.StatsFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Statistik als eigene Seite, bewusst getrennt vom Kompressor.
 *
 * Der Hauptbildschirm beantwortet "was mache ich jetzt"; diese Seite
 * beantwortet "was hat es gebracht". Zwei Fragen, zwei Bildschirme - im
 * Hauptbildschirm haette die Liste der Laeufe nur Platz von der Auswahl
 * weggenommen.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var textTotal: TextView
    private lateinit var textRuns: TextView
    private lateinit var textFiles: TextView
    private lateinit var textSavedFiles: TextView
    private lateinit var textAverage: TextView
    private lateinit var listRuns: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var textFooter: TextView

    private val readerDelegate = lazy { HistoryReader(applicationContext) }
    private val reader by readerDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        textTotal = findViewById(R.id.text_stats_total)
        textRuns = findViewById(R.id.text_stats_runs)
        textFiles = findViewById(R.id.text_stats_files)
        textSavedFiles = findViewById(R.id.text_stats_saved_files)
        textAverage = findViewById(R.id.text_stats_average)
        listRuns = findViewById(R.id.list_stats_runs)
        textEmpty = findViewById(R.id.text_stats_empty)
        textFooter = findViewById(R.id.text_stats_footer)

        findViewById<View>(R.id.button_stats_back).setOnClickListener { finish() }
        loadHistory()
    }

    /**
     * Die Datei ist klein, aber sie ist eine Datei: gelesen wird im
     * Hintergrund, gezeichnet wird auf dem Haupt-Thread. Derselbe Weg wie
     * im Hauptbildschirm - die Regel gilt fuer jede Datei, nicht nach
     * Groesse.
     */
    private fun loadHistory() {
        reader.read { history ->
            if (isFinishing || isDestroyed) return@read
            render(history)
        }
    }

    /**
     * Nur fuer Tests: wartet auf das Ende des Lesers. Gezeichnet wird im
     * Rueckruf auf dem Haupt-Thread, der im Test noch abgearbeitet werden
     * muss.
     */
    internal fun awaitHistoryForTest(timeoutMs: Long = 2000) {
        reader.awaitForTest(timeoutMs)
    }

    private fun render(history: History) {
        val totals = history.totals
        textTotal.text = StatsFormat.formatFreed(totals.savedBytes)
        textRuns.text = totals.runs.toString()
        textFiles.text = totals.files.toString()
        textSavedFiles.text = totals.savedFiles.toString()
        textAverage.text = StatsFormat.formatFreed(totals.bytesPerRun)

        textEmpty.visibility = if (history.isEmpty) View.VISIBLE else View.GONE
        listRuns.visibility = if (history.isEmpty) View.GONE else View.VISIBLE

        listRuns.removeAllViews()
        val newest = history.newestFirst
        // Balken relativ zum groessten Lauf: absolute Byte-Werte in einem
        // Balken waeren bei 20 MB neben 4 GB nicht mehr zu sehen.
        val max = newest.maxOfOrNull { it.savedBytes }?.coerceAtLeast(1L) ?: 1L
        val inflater = LayoutInflater.from(this)
        newest.forEach { run ->
            listRuns.addView(row(inflater, run, max))
        }

        // Die Fusszeile erklaert nur dann etwas, wenn es eine Liste gibt.
        // Ohne Laeufe steht der Hinweis oben schon - eine zweite Erklaerung
        // waere eine Zeile, die nichts hinzufuegt.
        textFooter.visibility = if (history.isEmpty) View.GONE else View.VISIBLE
        textFooter.text = getString(R.string.runstats_footer, RunHistory.MAX_RUNS)
    }

    private fun row(inflater: LayoutInflater, run: RunRecord, maxSaved: Long): View {
        val view = inflater.inflate(R.layout.item_run, listRuns, false)
        view.findViewById<TextView>(R.id.text_run_folder).text = run.folder
        view.findViewById<TextView>(R.id.text_run_when).text = formatWhen(run.id)
        view.findViewById<TextView>(R.id.text_run_files).text = getString(
            R.string.runstats_run_files,
            run.savedFiles,
            run.files
        )
        val saved = view.findViewById<TextView>(R.id.text_run_saved)
        if (run.savedBytes > 0) {
            saved.text = getString(R.string.runstats_run_saved, StatsFormat.formatFreed(run.savedBytes))
            saved.setTextColor(0xFFFFFFFF.toInt())
        } else {
            // Ein Lauf ohne Einsparung ist kein Erfolg - das darf man sehen.
            saved.setText(R.string.runstats_run_nothing_saved)
            saved.setTextColor(0xFF6B6B6B.toInt())
        }
        view.findViewById<SegmentBar>(R.id.bar_run).progress =
            (run.savedBytes.toFloat() / maxSaved.toFloat()).coerceIn(0f, 1f)
        return view
    }

    /**
     * Zeitpunkt einer Zeile. Relativ nur, solange das etwas sagt: "vor 55
     * Wochen" ist keine Information, das Datum daneben schon. Ab einer
     * Woche steht deshalb nur das Datum da.
     */
    private fun formatWhen(timestamp: Long): String {
        if (timestamp <= 0) return StatsFormat.formatWhen(timestamp)
        val full = DATE_FORMAT.format(Date(timestamp))
        val days = TimeUnit.MILLISECONDS.toDays(
            (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
        )
        return if (days < 7) "${StatsFormat.formatWhen(timestamp)} · $full" else full
    }

    override fun onDestroy() {
        if (readerDelegate.isInitialized()) reader.shutdown()
        super.onDestroy()
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY)
    }
}
