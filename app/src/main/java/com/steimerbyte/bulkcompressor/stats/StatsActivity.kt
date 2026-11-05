package com.steimerbyte.bulkcompressor.stats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.steimerbyte.bulkcompressor.ui.AppTheme
import com.steimerbyte.bulkcompressor.ui.Bar
import com.steimerbyte.bulkcompressor.ui.Dim
import com.steimerbyte.bulkcompressor.ui.Line
import com.steimerbyte.bulkcompressor.ui.Micro
import com.steimerbyte.bulkcompressor.ui.MiniButton
import com.steimerbyte.bulkcompressor.ui.Mono
import com.steimerbyte.bulkcompressor.ui.Osw
import com.steimerbyte.bulkcompressor.ui.StatsFormat
import com.steimerbyte.bulkcompressor.ui.SummaryPanel
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
class StatsActivity : ComponentActivity() {

    internal var history by mutableStateOf(History())
    private val readerDelegate = lazy { HistoryReader(applicationContext) }
    private val reader by readerDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Screen() } }
        loadHistory()
    }

    /**
     * Die Datei ist klein, aber sie ist eine Datei: gelesen wird im
     * Hintergrund, gezeichnet wird auf dem Haupt-Thread. Derselbe Weg wie
     * im Hauptbildschirm - die Regel gilt fuer jede Datei, nicht nach
     * Groesse.
     */
    private fun loadHistory() {
        reader.read { loaded ->
            if (isFinishing || isDestroyed) return@read
            history = loaded
        }
    }

    /** Nur fuer Tests: wartet auf das Ende des Lesers. */
    internal fun awaitHistoryForTest(timeoutMs: Long = 2000) {
        reader.awaitForTest(timeoutMs)
    }

    override fun onDestroy() {
        if (readerDelegate.isInitialized()) reader.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val totals = history.totals
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Osw(
                kicker = "NOTHING OS · STATISTIK",
                title = "Bisher",
                action = { MiniButton("ZURÜCK") { finish() } }
            )
            Spacer(Modifier.height(12.dp))
            SummaryPanel(
                listOf(
                    "EINGESPART" to StatsFormat.formatFreed(totals.savedBytes),
                    "LÄUFE" to totals.runs.toString(),
                    "DATEIEN" to totals.files.toString(),
                    "KLEINER" to totals.savedFiles.toString()
                )
            )
            Spacer(Modifier.height(6.dp))
            Micro("DURCHSCHNITT ${StatsFormat.formatFreed(totals.bytesPerRun)} JE LAUF")
            Spacer(Modifier.height(12.dp))

            if (history.isEmpty) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Micro("KEINE LÄUFE")
                }
            } else {
                val newest = history.newestFirst
                // Balken relativ zum groessten Lauf: absolute Byte-Werte in
                // einem Balken waeren bei 20 MB neben 4 GB nicht zu sehen.
                val max = newest.maxOfOrNull { it.savedBytes }?.coerceAtLeast(1L) ?: 1L
                LazyColumn(Modifier.weight(1f)) {
                    items(newest) { run -> RunRow(run, max) }
                }
                Micro("DIE LETZTEN ${RunHistory.MAX_RUNS} LÄUFE · NEUESTE ZUERST")
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    @Composable
    private fun RunRow(run: RunRecord, maxSaved: Long) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            run.folder, color = Color.White, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Mono("${formatWhen(run.id)} · ${run.savedFiles} / ${run.files} KLEINER")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        StatsFormat.formatFreed(run.savedBytes),
                        color = if (run.savedBytes > 0) Color.White else Dim,
                        fontSize = 22.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(6.dp))
                Bar(run.savedBytes.toFloat() / maxSaved.toFloat())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        }
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

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY)
    }
}
