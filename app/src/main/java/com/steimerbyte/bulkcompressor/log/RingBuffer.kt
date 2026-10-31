package com.steimerbyte.bulkcompressor.log

/**
 * Ringpuffer mit fester Kapazitaet: die aelteste Zeile faellt raus, sobald
 * der Puffer voll ist. Damit waechst der Speicherbedarf nie, auch wenn ein
 * Lauf Stunden dauert.
 *
 * Bewusst reines Kotlin ohne Android-Importe - so laeuft der Puffer im
 * Unit-Test auf der JVM.
 *
 * Thread-safe ueber einen eigenen Monitor statt CopyOnWrite: Logzeilen
 * kommen aus mehreren Threads (Kompressor, Dienst, Oberflaeche) und jedes
 * `add` soll nur einmal schreiben, nicht eine Liste kopieren.
 */
class RingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity muss positiv sein: $capacity" }
    }

    private val gate = Any()

    /**
     * Fester Speicher fuer alle Plaetze. `null` bedeutet "noch nie
     * beschrieben".
     */
    private val slots = arrayOfNulls<LogLine>(capacity)

    /** Index des aeltesten Eintrags, sobald der Puffer einmal voll war. */
    private var start = 0

    /** Anzahl der gueltigen Eintraege, hoechstens [capacity]. */
    private var count = 0

    fun add(line: LogLine) {
        synchronized(gate) {
            if (count < capacity) {
                slots[(start + count) % capacity] = line
                count++
            } else {
                // Voller Puffer: der aelteste Platz wird ueberschrieben und
                // der Anfang rueckt einen Platz weiter.
                slots[start] = line
                start = (start + 1) % capacity
            }
        }
    }

    /**
     * Die letzten [limit] Eintraege, aelteste zuerst. `limit <= 0` liefert
     * eine leere Liste, ein zu grosses Limit alles Vorhandene.
     */
    fun snapshot(limit: Int): List<LogLine> {
        if (limit <= 0) return emptyList()
        synchronized(gate) {
            val n = if (limit < count) limit else count
            val out = ArrayList<LogLine>(n)
            val first = (start + count - n) % capacity
            for (i in 0 until n) {
                slots[(first + i) % capacity]?.let(out::add)
            }
            return out
        }
    }

    fun clear() {
        synchronized(gate) {
            slots.fill(null)
            start = 0
            count = 0
        }
    }

    val size: Int
        get() = synchronized(gate) { count }

    companion object {
        /** 2000 Zeilen reichen fuer einen langen Lauf und bleiben klein im Speicher. */
        const val DEFAULT_CAPACITY = 2000
    }
}
