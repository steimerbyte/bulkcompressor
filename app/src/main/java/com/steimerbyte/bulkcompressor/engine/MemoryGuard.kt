package com.steimerbyte.bulkcompressor.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.steimerbyte.bulkcompressor.log.Log

/**
 * Bremse fuer Speicherdruck.
 *
 * Ein Stapellauf ueber Hunderte Videos belastet den Speicher des ganzen
 * Geraets, nicht nur den eigenen Heap: der Encoder haelt Puffer, der
 * Bilddecode legt Bitmaps an, und daneben laufen Galerie, Kamera und was
 * der Nutzer sonst offen hat. Android beendet dann irgendwann den
 * Prozess - meist den, der am meisten belegt: diesen hier. Mitten in
 * Datei 200 ist der Lauf dann weg.
 *
 * Deshalb zwei Signale, beide zusammen:
 *
 * 1. **Der Heap des Prozesses.** Belegt er mehr als [THROTTLE_RATIO] der
 *    Obergrenze, wird vor der naechsten Datei gewartet; ueber
 *    [HARD_RATIO] laenger. Warten klingt nach wenig, ist aber genau das
 *    Richtige: der Garbage Collector bekommt die Zeit, die er sonst nicht
 *    hat, und der naechste Bilddecode bekommt ein kleineres Budget.
 * 2. **Die Meldung des Systems** (`onTrimMemory`). Sie kommt, bevor der
 *    Prozess stirbt, und ist damit die verlaesslichere Quelle. Sie
 *    ueberstimmt die Heapschaetzung: was Android als knapp meldet, ist
 *    knapp - egal, was der eigene Heap sagt.
 *
 * Gedrosselt wird, nie abgebrochen: ein langsamer Lauf ist ein Erfolg,
 * ein abgeschossener Prozess nicht. Nur wenn der Speicher auch nach
 * [MAX_PAUSE_MS] nicht frei wird, geht es weiter - dann steht im
 * Protokoll, dass gewartet wurde und warum.
 */
object MemoryGuard {

    /** Wie knapp der Speicher ist. */
    enum class Level(val label: String) {
        OK("frei"),
        THROTTLE("knapp"),
        HARD("sehr knapp")
    }

    /** Anteil des Heaps, ab dem vor der naechsten Datei gewartet wird. */
    const val THROTTLE_RATIO = 0.72f

    /** Anteil, ab dem laenger gewartet und groeber dekodiert wird. */
    const val HARD_RATIO = 0.85f

    /** Laengste Wartezeit an einer Stelle; danach wird weitergearbeitet. */
    const val MAX_PAUSE_MS = 30_000L

    /** Wartezeit je Runde. */
    private const val THROTTLE_STEP_MS = 120L
    private const val HARD_STEP_MS = 400L

    @Volatile
    private var trimLevel = 0

    @Volatile
    private var pauses = 0

    @Volatile
    private var waitedMs = 0L

    /** Belegter Anteil des Prozess-Heaps, 0..1. */
    fun usedRatio(): Float {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory()
        if (max <= 0) return 0f
        val used = runtime.totalMemory() - runtime.freeMemory()
        return (used.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Reine Einordnung - ohne Uhr und ohne Warten, damit sie ohne Geraet
     * pruefbar ist. [trim] ist der Wert aus `onTrimMemory`.
     *
     * Nur die RUNNING_*-Stufen gelten als Speicherdruck. Die Zahlenwerte
     * darueber taugen nicht als Schwelle: `TRIM_MEMORY_UI_HIDDEN` (20)
     * ist groesser als `TRIM_MEMORY_RUNNING_LOW` (10), bedeutet aber nur
     * "App nicht mehr sichtbar". Wuerde man stumpf auf den Zahlenwert
     * pruefen, bremste jeder Wechsel in den Hintergrund den Lauf - das
     * Gegenteil des Zwecks, denn genau im Hintergrund soll er ja laufen.
     * `BACKGROUND`, `MODERATE` und `COMPLETE` beschreiben ausserdem eine
     * App, die das System gerade wegraeumt; Warten hilft dort nicht mehr.
     */
    fun classify(ratio: Float, trim: Int): Level {
        val systemPressure =
            if (trim >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE &&
                trim < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            ) trim else 0
        return when {
            systemPressure >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || ratio >= HARD_RATIO -> Level.HARD
            systemPressure >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE || ratio >= THROTTLE_RATIO -> Level.THROTTLE
            else -> Level.OK
        }
    }

    fun level(): Level = classify(usedRatio(), trimLevel)

    /**
     * Meldung des Systems. Ab `RUNNING_MODERATE` wird gedrosselt, ab
     * `RUNNING_LOW` deutlich. `TRIM_MEMORY_UI_HIDDEN` (App im
     * Hintergrund) sagt nichts ueber Speicher und bleibt folgenlos -
     * sonst wuerde jeder Wechsel in den Hintergrund den Lauf bremsen.
     */
    fun onTrimMemory(value: Int) {
        // Nur die RUNNING_*-Stufen bedeuten Druck fuer diesen Lauf.
        if (value < ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) return
        if (value >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        val previous = level()
        if (value > trimLevel) trimLevel = value
        val now = level()
        if (now != previous) {
            Log.i("Ram", "Speicherdruck ${previous.label} -> ${now.label} (onTrimMemory $value) · ${snapshot()}")
        }
    }

    /** Nach einem abgeschlossenen Schritt ist die Systemmeldung verbraucht. */
    fun relax() {
        trimLevel = 0
    }

    /**
     * Wartet, solange der Speicher knapp ist.
     *
     * @return tatsaechlich gewartete Millisekunden, 0 wenn nichts zu tun war.
     */
    fun pauseBefore(isCancelled: () -> Boolean = { false }): Long {
        val initial = level()
        if (initial == Level.OK) {
            return 0L
        }
        val began = System.currentTimeMillis()
        Log.w("Ram", "Drosselung: Speicher ${initial.label} vor dem naechsten Schritt · ${snapshot()}")
        var current = initial
        while (!isCancelled()) {
            runCatching { Thread.sleep(if (current == Level.HARD) HARD_STEP_MS else THROTTLE_STEP_MS) }
            val waited = System.currentTimeMillis() - began
            // Hat der Heap sich erholt, ist auch die Systemmeldung erledigt.
            if (usedRatio() < THROTTLE_RATIO) trimLevel = 0
            current = level()
            if (current == Level.OK || waited >= MAX_PAUSE_MS) break
        }
        val waited = System.currentTimeMillis() - began
        pauses++
        waitedMs += waited
        val tail = if (current == Level.OK) {
            ""
        } else {
            " · Speicher wurde nicht frei, Arbeit geht weiter"
        }
        Log.i("Ram", "Drosselung beendet nach $waited ms (Start ${initial.label})$tail · ${snapshot()}")
        return waited
    }

    /**
     * Wie reichlich der Bilddecode rechnen darf: der Nenner des Budgets
     * `maxMemory / n`. Je knapper der Speicher, desto kleiner die
     * Bitmaps und desto haeufiger darf der Decoder verkleinern.
     */
    fun decodeBudgetDivisor(): Int = when (level()) {
        Level.OK -> 6
        Level.THROTTLE -> 10
        Level.HARD -> 16
    }

    /** Zeile fuer Protokoll und Oberflaeche. */
    fun snapshot(): String {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val freeMb = runtime.freeMemory() / 1024 / 1024
        return "Heap ${usedMb}/${maxMb} MB (${(usedRatio() * 100).toInt()} %), frei $freeMb MB" +
            " · Systemmeldung ${trimLabel(trimLevel)}" +
            " · Stufe ${level().label}" +
            if (pauses > 0) " · ${pauses} Drosselungen, ${waitedMs} ms gewartet" else ""
    }

    fun pauseCount(): Int = pauses

    fun waitedMs(): Long = waitedMs

    /** Fuer Wiederholungen in Tests und nach einem neuen Auftrag. */
    fun reset() {
        trimLevel = 0
        pauses = 0
        waitedMs = 0L
    }

    /** Kuerzel der Systemmeldung - die Zahlen sagen ohne Namen niemandem etwas. */
    fun trimLabel(value: Int): String = when {
        value >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND ($value)"
        value >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN ($value)"
        else -> "keine ($value)"
    }

    /**
     * Einmal aus der Application heraus aufrufen. Ohne diese Verbindung
     * hoert der Waechter das System nicht und muss raten.
     */
    fun attach(context: Context) {
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    this@MemoryGuard.onTrimMemory(level)
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    this@MemoryGuard.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }
            }
        )
        Log.i("Ram", "Speicherwaechter verbunden · ${snapshot()}")
    }
}
