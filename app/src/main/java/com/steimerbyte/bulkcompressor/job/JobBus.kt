package com.steimerbyte.bulkcompressor.job

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Zustand des laufenden Auftrags. Der Dienst schreibt, die Oberflaeche
 * liest. Ein einfacher Beobachter statt Broadcast: beide leben im selben
 * Prozess, und ein Intent-Tanz waere hier nur Fehlerquelle.
 */
object JobBus {

    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val cancelled: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        /**
         * Dateien, deren Marker nicht lesbar war.
         *
         * Eigener Zaehler neben [skipped], weil es eine andere Aussage
         * ist: "schon erledigt" ist belegt, "unbekannt" ist es nicht.
         * Wird beides in eine Zahl geworfen, bleibt genau der Fall
         * unsichtbar, in dem eine Datei liegen bleibt, ohne dass jemand
         * sagen kann warum - und der Nutzer sieht einen Lauf, der
         * "fertig" meldet, obwohl er nichts getan hat.
         */
        val unreadable: Int = 0,
        val currentName: String = "",
        val overall: Float = 0f,
        val beforeBytes: Long = 0,
        val afterBytes: Long = 0,
        /**
         * Zuletzt fertig gewordene Datei und ihre Groesse danach.
         *
         * Die Oberflaeche baut daraus die Vorher/Nachher-Spalte der Liste:
         * eine ganze Ergebnisliste im Zustand waere bei zehntausend Dateien
         * bei jeder Meldung eine Kopie derselben.
         */
        val lastDocId: String = "",
        val lastAfterBytes: Long = 0,
        /** Startzeit des Laufs; 0 heisst: in diesem Prozess noch keiner. */
        val startedAt: Long = 0,
        /** Endzeit, sobald der Lauf abgeschlossen oder abgebrochen ist. */
        val endedAt: Long = 0,
        val log: List<String> = emptyList()
    ) {
        /** Laufzeit bis jetzt - der Wert steht nach dem Ende still. */
        val elapsedMs: Long
            get() = when {
                startedAt <= 0 -> 0
                endedAt > 0 -> endedAt - startedAt
                else -> System.currentTimeMillis() - startedAt
            }

        val savedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)
        val savedPercent: Int
            get() = if (beforeBytes <= 0) 0
            else ((savedBytes * 100) / beforeBytes).toInt()
    }

    interface Listener {
        fun onState(state: State)
    }

    @Volatile
    var state: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        listener.onState(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(next: State) {
        state = next
        for (listener in listeners) {
            runCatching { listener.onState(next) }
        }
    }

    fun appendLog(line: String): State {
        val current = state
        val lines = (current.log + line).takeLast(MAX_LOG_LINES)
        val next = current.copy(log = lines)
        publish(next)
        return next
    }

    fun reset() {
        publish(State())
    }

    const val MAX_LOG_LINES = 300
}
