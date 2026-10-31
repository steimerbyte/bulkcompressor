package com.steimerbyte.bulkcompressor.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Zahlen der Speichertafel. Bewusst ohne Android-Abhaengigkeiten: so
 * laesst sich ohne Geraet pruefen, was bei 0 Bytes, 1 KB, 999 MB und
 * 1,4 GB auf dem Bildschirm steht - die Faelle, die auf einem Testgeraet
 * nie alle vorkommen.
 */
object StatsFormat {

    /** Groesse fuer die Speicherzeile: ganzen MB unter einem GB. */
    fun formatMb(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb < 1.0 -> String.format(Locale.GERMANY, "%.1f MB", mb)
            mb < 1024.0 -> String.format(Locale.GERMANY, "%.0f MB", mb)
            else -> String.format(Locale.GERMANY, "%.2f GB", mb / 1024.0)
        }
    }

    /**
     * Heap als Paar "belegt/obergrenze", in einer Einheit fuer beide
     * Zahlen - sonst stuende in der Benachrichtigung "0,98/1,00 GB" neben
     * "980/1020 MB" je nach Rundung.
     */
    fun formatHeapPair(usedBytes: Long, maxBytes: Long): String {
        val used = usedBytes.coerceAtLeast(0)
        val max = maxBytes.coerceAtLeast(0)
        val maxMb = max / 1024.0 / 1024.0
        return if (maxMb < 1024.0) {
            "${(used / 1024.0 / 1024.0).toLong()}/${maxMb.toLong()} MB"
        } else {
            String.format(
                Locale.GERMANY,
                "%.2f/%.2f GB",
                used / 1024.0 / 1024.0 / 1024.0,
                maxMb / 1024.0
            )
        }
    }

    /**
     * Freigegebener Plattenplatz. Ab einem Gigabyte wird in GB gerechnet -
     * genau die Zahl, nach der jemand fragt, wenn er wissen will, was der
     * Lauf gebracht hat. Darunter bleibt die Einheit, die zum Wert passt.
     */
    fun formatFreed(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        if (gb >= 1.0) return String.format(Locale.GERMANY, "%.2f GB", gb)
        val mb = bytes / 1024.0 / 1024.0
        if (mb >= 1.0) return String.format(Locale.GERMANY, "%.0f MB", mb)
        val kb = bytes / 1024.0
        if (kb >= 1.0) return String.format(Locale.GERMANY, "%.0f kB", kb)
        return "$bytes B"
    }

    /** "heute", "gestern", "vor 3 Tagen", "vor 2 Wochen". */
    fun formatWhen(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0) return "unbekannt"
        val days = TimeUnit.MILLISECONDS.toDays((now - timestamp).coerceAtLeast(0))
        return when {
            days <= 0L -> "heute"
            days == 1L -> "gestern"
            days < 7L -> "vor $days Tagen"
            else -> "vor ${days / 7} Wochen"
        }
    }
}
