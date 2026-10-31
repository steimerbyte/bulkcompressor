package com.steimerbyte.bulkcompressor.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eine fertige Logzeile. [seq] ist eine laufende Nummer ueber den ganzen
 * Prozess: sie macht die Reihenfolge im Ringpuffer eindeutig, auch wenn
 * mehrere Threads gleichzeitig schreiben und die Uhr nur Millisekunden
 * aufloest.
 *
 * [message] darf Zeilenumbrueche enthalten (Stacktraces); [format] rueckt
 * die Folgezeilen ein, damit das Dateilog spaltenweise lesbar bleibt.
 */
data class LogLine(
    val seq: Long,
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {

    /** Uhrzeit als `HH:mm:ss.SSS` in 24-Stunden-Anzeige. */
    fun formatTime(): String = TIME_FORMAT.get()!!.format(Date(timeMs))

    /**
     * Vollstaendige Zeile: `HH:mm:ss.SSS I/Video  text`.
     *
     * Folgezeilen einer mehrzeiligen Nachricht bekommen vier Leerzeichen
     * vorangestellt, damit ein Stacktrace nicht wie eine neue Meldung
     * aussieht. Das Praefix wird nur einmal berechnet.
     */
    fun format(): String {
        val prefix = formatTime() + " " + level.name + "/" + tag + "  "
        return prefix + message.replace("\n", "\n" + CONTINUATION)
    }

    private companion object {
        const val CONTINUATION = "    "

        /**
         * SimpleDateFormat ist nicht thread-safe. Ein ThreadLocal ist hier
         * die billigste Loesung: der Logschreiber-Thread und der Aufrufer-
         * Thread bekommen je ein eigenes Exemplar, ohne Synchronisation.
         */
        val TIME_FORMAT: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            }
    }
}
