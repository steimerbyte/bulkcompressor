package com.steimerbyte.bulkcompressor.job

import android.net.Uri
import com.steimerbyte.bulkcompressor.engine.tag.CompressionTag
import com.steimerbyte.bulkcompressor.engine.tag.MarkerProbe
import com.steimerbyte.bulkcompressor.engine.tag.MarkerReading
import com.steimerbyte.bulkcompressor.engine.tag.MarkerState

/**
 * Was mit einer Datei geschieht, bevor der Kompressor sie anfasst.
 *
 * Drei Faelle, mehr gibt es nicht - und der Unterschied zwischen den
 * beiden letzten ist der Grund fuer diese Datei: "hat einen Marker" ist
 * belegt, "Marker nicht lesbar" ist **unbekannt**. Wer beides
 * zusammenwirft, komprimiert entweder eine bereits bearbeitete Datei
 * noch einmal, oder er laesst eine liegen, ohne dass jemand erfaehrt
 * warum.
 */
enum class GateDecision {
    /** Marker bewusst ignoriert oder keiner vorhanden: es darf komprimiert werden. */
    COMPRESS,

    /** Belegt bearbeitet - die Datei wird nicht angefasst. */
    SKIP_MARKED,

    /**
     * Der Marker liess sich nicht lesen. Die Datei wird **nicht**
     * komprimiert: der Lauf schreibt verlustbehaftet, und ein unbekannter
     * Zustand ist dafuer kein Freibrief. Der Grund gehoert in Anzeige und
     * Protokoll - sonst sieht der Lauf aus wie "nichts zu tun".
     */
    SKIP_UNREADABLE
}

/**
 * Ergebnis einer Einordnung samt der Begruendung, die der Benutzer sieht.
 *
 * Entscheidung und Text entstehen gemeinsam, damit nicht eine Stelle die
 * Entscheidung trifft und eine andere den Grund dazu erfindet.
 */
data class GateVerdict(
    val decision: GateDecision,
    /** Marker, wenn einer gelesen wurde - traegt `runs` in den Lauf weiter. */
    val tag: CompressionTag? = null,
    /** Warum der Marker nicht lesbar war; leer, wenn er gelesen wurde. */
    val reason: String = ""
) {

    /** Der Teil, der hinter "SKIP <name>: " bzw. "Grund: " steht. */
    fun describeCause(): String = when (decision) {
        GateDecision.COMPRESS -> ""
        GateDecision.SKIP_MARKED ->
            "Marker vorhanden" + (tag?.let { " (${it.describe()})" } ?: "")
        GateDecision.SKIP_UNREADABLE ->
            "Marker nicht lesbar (${reason.ifBlank { UNKNOWN_REASON }})"
    }

    companion object {
        /** Steht da, wenn ein Leser einen leeren Grund liefert - nie eine leere Klammer. */
        const val UNKNOWN_REASON = "Grund unbekannt"
    }
}

/**
 * Einzige Stelle, die aus einem Marker-Leser eine Entscheidung macht.
 *
 * Job und Scan stellen dieselbe Frage an denselben Leser
 * ([MarkerProbe]); diese Klasse zieht daraus die Folge fuer den Lauf.
 * Getrennt vom Job, weil die Regel damit ohne Datei, ohne Codec und ohne
 * Android pruefbar ist - und weil eine zweite Kopie der Regel genau der
 * Fehler war, um den es hier geht.
 *
 * [ignoreTag] schaltet das Lesen **ab**, nicht nur die Folge: wer den
 * Marker nicht beachtet, soll auch keinen Deskriptor dafuer oeffnen. Ein
 * Aufruf von [classify] in diesem Modus fragt den Leser deshalb **nicht**.
 */
class MarkerGate(
    private val probe: MarkerProbe,
    /** Entspricht `CompressSettings.ignoreCompressionTag`. */
    val ignoreTag: Boolean
) {

    /**
     * Ordnet eine Datei ein.
     *
     * Ein Leser, der trotz Vertrag wirft, gilt als
     * [MarkerState.UNREADABLE] - ein Fehlschlag darf nie als "kein
     * Marker gefunden" durchgehen.
     */
    fun classify(uri: Uri): GateVerdict {
        if (ignoreTag) return GateVerdict(GateDecision.COMPRESS)
        val reading = runCatching { probe.probe(uri) }.getOrElse {
            MarkerReading(
                state = MarkerState.UNREADABLE,
                reason = it.message ?: it.javaClass.simpleName
            )
        }
        val decision = decide(reading, ignoreTag)
        return GateVerdict(
            decision = decision,
            tag = reading.tag,
            reason = if (decision == GateDecision.SKIP_UNREADABLE) reading.reason else ""
        )
    }

    companion object {

        /**
         * Die Regel selbst - ohne Leser, ohne Zaehler, ohne Uhr.
         *
         * Oeffentlich, weil sie der einzige Teil ist, der sich vollstaendig
         * ohne Datei pruefen laesst: welche Folgerung aus welchem Zustand
         * folgt, ist die Wahrheit, die Job und Scan teilen.
         */
        fun decide(reading: MarkerReading, ignoreTag: Boolean): GateDecision = when {
            ignoreTag -> GateDecision.COMPRESS
            reading.state == MarkerState.MARKED -> GateDecision.SKIP_MARKED
            reading.state == MarkerState.UNREADABLE -> GateDecision.SKIP_UNREADABLE
            else -> GateDecision.COMPRESS
        }
    }
}
