package com.steimerbyte.bulkcompressor.stats

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Liest die Laufhistorie abseits des Haupt-Threads und liefert das
 * Ergebnis dort ab.
 *
 * Warum eine eigene Klasse: die Historie ist eine Datei, und Datei-I/O
 * gehoert nicht auf den Haupt-Thread - weder in `onCreate` noch in
 * `onStart`. Gleichzeitig muss das Ergebnis dort ankommen, weil daraus
 * Ansichten gefuellt werden. Damit sind zwei Threads klar getrennt:
 * gelesen wird auf [THREAD_NAME], gezeichnet im Rueckruf auf dem
 * Haupt-Thread.
 *
 * Ein einzelner Thread je Leser, kein Pool: so kann ein neuer Stand einen
 * aelteren nicht ueberholen, und die Reihenfolge der Auftraege ist die
 * Reihenfolge der Rueckrufe.
 *
 * [load] ist einspeisbar, damit der Test gesehen werden kann, auf welchem
 * Thread gelesen wird - ohne dass die Produktion dafuer etwas mitschreiben
 * muss.
 */
class HistoryReader(
    private val context: Context,
    private val load: (Context) -> History = { RunHistory.of(it).load() }
) {

    private val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Liest im Hintergrund; [onLoaded] laeuft danach auf dem Haupt-Thread. */
    fun read(onLoaded: (History) -> Unit) {
        loader.execute {
            // Fehler bleiben hier: eine unlesbare Datei heisst "kein Stand",
            // nicht "Bildschirm kaputt".
            val history = runCatching { load(context) }.getOrDefault(History())
            main.post { onLoaded(history) }
        }
    }

    /**
     * Nur fuer Tests: wartet, bis das Lesen durch ist. Nicht das
     * Zeichnen - das kommt als eigener Auftrag auf dem Haupt-Thread und
     * will dort abgearbeitet werden.
     */
    fun awaitForTest(timeoutMs: Long = 2000) {
        runCatching { loader.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() {
        loader.shutdownNow()
    }

    companion object {
        internal const val THREAD_NAME = "run-history"
    }
}
