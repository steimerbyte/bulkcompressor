package com.steimerbyte.bulkcompressor.job

import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Fortschrittszeile der Benachrichtigung. Sie ist der einzige Text,
 * den man waehrend eines Laufs sieht, ohne die App zu oeffnen - die
 * Zahlenfaelle gehoeren deshalb ohne Geraet geprueft.
 */
class NotificationTextTest {

    private val mb = 1024L * 1024

    private fun line(
        done: Int = 3,
        total: Int = 12,
        used: Long = 412 * mb,
        max: Long = 768 * mb,
        deviceFree: Long = 2 * 1024 * mb,
        level: MemoryGuard.Level = MemoryGuard.Level.OK
    ) = NotificationText.progressLine(done, total, used, max, deviceFree, level)

    @Test
    fun `Fortschritt und Speicher in einer Zeile`() {
        assertEquals(
            "3 / 12 · RAM 412/768 MB · Gerät 2,00 GB frei",
            line()
        )
    }

    @Test
    fun `ohne Geraetespeicher faellt nur dieser Teil weg`() {
        assertEquals("3 / 12 · RAM 412/768 MB", line(deviceFree = 0))
        assertEquals("3 / 12 · RAM 412/768 MB", line(deviceFree = -1))
    }

    @Test
    fun `Druck wird benannt statt nur an der Zahl gezeigt`() {
        assertEquals(
            "3 / 12 · RAM 412/768 MB · Gerät 2,00 GB frei · knapp",
            line(level = MemoryGuard.Level.THROTTLE)
        )
        assertTrue(line(level = MemoryGuard.Level.HARD).endsWith("· sehr knapp"))
        assertFalse(
            "Bei freiem Speicher darf kein Druckwort stehen",
            line(level = MemoryGuard.Level.OK).contains("knapp")
        )
    }

    @Test
    fun `Heap ueber einem Gigabyte in einer Einheit fuer beide Zahlen`() {
        assertEquals(
            "3 / 12 · RAM 0,50/2,00 GB · Gerät 2,00 GB frei",
            line(used = 512 * mb, max = 2048 * mb)
        )
    }

    @Test
    fun `leerer Lauf und unbekannter Heap bleiben lesbar`() {
        assertEquals(
            "0 / 0 · RAM 0/0 MB",
            NotificationText.progressLine(
                done = 0,
                total = 0,
                heapUsed = 0,
                heapMax = 0,
                deviceFree = 0,
                level = MemoryGuard.Level.OK
            )
        )
        val negative =
            NotificationText.progressLine(0, 0, -5, -1, -9, MemoryGuard.Level.OK)
        assertFalse("Negative Werte sind Anzeigefehler, keine Zahl: $negative", negative.contains("-"))
    }
}
