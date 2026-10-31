package com.steimerbyte.bulkcompressor.ui

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import com.steimerbyte.bulkcompressor.R
import com.steimerbyte.bulkcompressor.engine.MemoryGuard

/**
 * Die Tafel ganz oben: was der Lauf gerade an Arbeitsspeicher zieht und
 * was er an Platz freigemacht hat.
 *
 * Warum ein eigener Baustein statt ein paar Zeilen in der Activity: die
 * Tafel wird im Sekundentakt aktualisiert, und das gehoert nicht in eine
 * Methode, die schon Formulare, Auswahl und Protokoll bedient.
 *
 * Angezeigt werden zwei Dinge, weil sie zwei verschiedene Grenzen
 * darstellen:
 *
 * - **App-Heap**: der Speicher, den dieser Prozess belegen darf. Ueber
 *   ihm liegt die Obergrenze, an der Android die App beendet - und der
 *   Waechter bremst, bevor es so weit kommt.
 * - **Geraet**: der freie Arbeitsspeicher des ganzen Telefons, so wie
 *   das System ihn meldet. Er kann knapp sein, obwohl der eigene Heap
 *   noch Luft hat, weil die Galerie oder die Kamera daneben laufen.
 *
 * Nach einem erfolgreichen Lauf steht unten, wie viel Plattenplatz
 * tatsaechlich freigeworden ist. Der Wert wird gespeichert, damit er
 * beim naechsten Start noch da ist - sonst waere die Zahl weg, sobald
 * die Oberflaeche neu aufgebaut wird.
 */
class StatsBoard(
    private val context: Context,
    root: View,
    private val prefs: SharedPreferences
) {

    private val memoryState: TextView = root.findViewById(R.id.text_stats_memory_state)
    private val heapValue: TextView = root.findViewById(R.id.text_stats_heap)
    private val heapTotal: TextView = root.findViewById(R.id.text_stats_heap_total)
    private val heapBar: SegmentBar = root.findViewById(R.id.bar_stats_heap)
    private val deviceLine: TextView = root.findViewById(R.id.text_stats_device)
    private val savedGroup: View = root.findViewById(R.id.group_stats_saved)
    private val savedValue: TextView = root.findViewById(R.id.text_stats_saved)
    private val savedDetail: TextView = root.findViewById(R.id.text_stats_saved_detail)

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Liest Heap und Geraetespeicher neu. Wird vom Sekundentakt der
     * Activity aufgerufen, solange sie sichtbar ist - im Hintergrund waere
     * es Stromverbrauch ohne Betrachter.
     */
    fun refresh() {
        val runtime = Runtime.getRuntime()
        val maxBytes = runtime.maxMemory()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        val ratio = if (maxBytes > 0) usedBytes.toFloat() / maxBytes else 0f

        val level = MemoryGuard.level()
        heapValue.text = StatsFormat.formatMb(usedBytes)
        heapTotal.text = "/ ${StatsFormat.formatMb(maxBytes)}"
        heapBar.progress = ratio
        heapBar.setLevel(level)
        memoryState.text = context.getString(
            when (level) {
                MemoryGuard.Level.OK -> R.string.stats_state_ok
                MemoryGuard.Level.THROTTLE -> R.string.stats_state_throttle
                MemoryGuard.Level.HARD -> R.string.stats_state_hard
            }
        )
        memoryState.setTextColor(
            context.getColor(
                when (level) {
                    MemoryGuard.Level.OK -> R.color.nothing_white
                    MemoryGuard.Level.THROTTLE -> R.color.nothing_grey
                    MemoryGuard.Level.HARD -> R.color.nothing_red
                }
            )
        )

        val info = ActivityManager.MemoryInfo()
        runCatching { activityManager.getMemoryInfo(info) }
        deviceLine.text = if (info.totalMem > 0) {
            context.getString(
                R.string.stats_device,
                StatsFormat.formatMb(info.availMem),
                StatsFormat.formatMb(info.totalMem),
                (info.availMem * 100 / info.totalMem).toInt()
            ) + if (info.lowMemory) " · " + context.getString(R.string.stats_device_low) else ""
        } else {
            context.getString(R.string.stats_device_unknown)
        }

        renderSaved()
    }

    /**
     * Uebernimmt das Ergebnis eines beendeten Laufs. Nur bei Erfolg und
     * nur, wenn wirklich Platz gewonnen wurde - ein abgebrochener Lauf
     * hat keine Zahl verdient, die wie ein Ergebnis aussieht.
     */
    fun onRunFinished(running: Boolean, cancelled: Boolean, finished: Boolean, savedBytes: Long, files: Int) {
        if (running || cancelled || !finished) return
        if (savedBytes <= 0 || files <= 0) return
        prefs.edit()
            .putLong(KEY_SAVED, savedBytes)
            .putInt(KEY_FILES, files)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        renderSaved()
    }

    /** Zeigt den gespeicherten Stand aus dem letzten erfolgreichen Lauf. */
    fun renderSaved() {
        val saved = prefs.getLong(KEY_SAVED, 0L)
        val files = prefs.getInt(KEY_FILES, 0)
        if (saved <= 0) {
            savedGroup.visibility = View.GONE
            return
        }
        savedGroup.visibility = View.VISIBLE
        savedValue.text = StatsFormat.formatFreed(saved)
        val at = prefs.getLong(KEY_AT, 0L)
        savedDetail.text = context.getString(
            R.string.stats_freed_detail,
            files,
            StatsFormat.formatWhen(at)
        )
    }

    companion object {
        private const val KEY_SAVED = "stats_saved_bytes"
        private const val KEY_FILES = "stats_saved_files"
        private const val KEY_AT = "stats_saved_at"
    }
}
