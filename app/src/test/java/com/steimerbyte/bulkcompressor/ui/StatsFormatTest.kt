package com.steimerbyte.bulkcompressor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zahlen der Speichertafel: die Grenzfaelle sind hier wichtig, weil
 * "0,00 GB" nach einem Lauf ueber 900 MB falsch wirkt und "1 Bytes" nie
 * auf dem Bildschirm stehen soll.
 */
class StatsFormatTest {

    private val mb = 1024L * 1024

    @Test
    fun `Megabyte mit ganzen Zahlen`() {
        assertEquals("0 MB", StatsFormat.formatMb(0))
        assertEquals("0,5 MB", StatsFormat.formatMb(mb / 2))
        assertEquals("512 MB", StatsFormat.formatMb(512 * mb))
        assertEquals("768 MB", StatsFormat.formatMb(768 * mb))
    }

    @Test
    fun `ab einem Gigabyte in Gigabyte`() {
        assertEquals("1,50 GB", StatsFormat.formatMb(1536 * mb))
    }

    @Test
    fun `freigegebener Platz in der Groesse, in der er zaehlt`() {
        assertEquals("0 B", StatsFormat.formatFreed(0))
        assertEquals("1 kB", StatsFormat.formatFreed(1024))
        assertEquals("640 MB", StatsFormat.formatFreed(640 * mb))
        assertEquals("1,00 GB", StatsFormat.formatFreed(1024 * mb))
        assertEquals("12,50 GB", StatsFormat.formatFreed((12.5 * 1024 * mb).toLong()))
    }

    @Test
    fun `Zeitangabe bleibt grob und richtig`() {
        val now = 1_700_000_000_000L
        val day = 24 * 60 * 60 * 1000L

        assertEquals("unbekannt", StatsFormat.formatWhen(0, now))
        assertEquals("heute", StatsFormat.formatWhen(now - 1000, now))
        assertEquals("gestern", StatsFormat.formatWhen(now - day, now))
        assertEquals("vor 3 Tagen", StatsFormat.formatWhen(now - 3 * day, now))
        assertEquals("vor 2 Wochen", StatsFormat.formatWhen(now - 15 * day, now))
    }
}
