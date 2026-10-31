package com.steimerbyte.bulkcompressor.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Der Ringpuffer traegt die Reihenfolge des Logs: er muss bei voller
 * Kapazitaet das Aelteste verwerfen, die Reihenfolge halten und unter
 * parallelen Schreibern intakt bleiben. Diese drei Eigenschaften sind die
 * Voraussetzung dafuer, dass der Absturzbericht die letzten 200 Zeilen in
 * richtiger Reihenfolge zeigt.
 */
class RingBufferTest {

    private fun line(seq: Long, level: LogLevel = LogLevel.I, message: String = "z$seq") =
        LogLine(seq, 1_700_000_000_000L + seq, level, "Test", message)

    @Test
    fun `volle kapazitaet verwirft das aelteste und haelt die reihenfolge`() {
        val ring = RingBuffer(4)
        for (i in 0 until 6) ring.add(line(i.toLong()))

        assertEquals(4, ring.size)
        assertEquals(listOf(2L, 3L, 4L, 5L), ring.snapshot(4).map { it.seq })
    }

    @Test
    fun `snapshot mit limit liefert die letzten n und aelteste zuerst`() {
        val ring = RingBuffer(10)
        for (i in 0 until 10) ring.add(line(i.toLong()))

        assertEquals(listOf(7L, 8L, 9L), ring.snapshot(3).map { it.seq })
        assertEquals(10, ring.snapshot(100).size)
        // Zehn Eintraege mit den Nummern 0..9: der letzte ist also 9.
        assertEquals(9L, ring.snapshot(1).single().seq)
        assertTrue(ring.snapshot(0).isEmpty())
    }

    @Test
    fun `clear leert den puffer`() {
        val ring = RingBuffer(8)
        for (i in 0 until 5) ring.add(line(i.toLong()))
        ring.clear()

        assertEquals(0, ring.size)
        assertTrue(ring.snapshot(8).isEmpty())
    }

    @Test
    fun `vier threads mit je 500 eintraegen fuellen den puffer vollstaendig`() {
        val ring = RingBuffer(2000)
        val errors = CopyOnWriteArrayList<Throwable>()
        val threads = (0 until 4).map { t ->
            Thread {
                try {
                    for (i in 0 until 500) {
                        // Eindeutige Nummer ueber alle Threads: so laesst sich
                        // pruefen, dass keine Zeile verloren geht.
                        val seq = (t * 500 + i).toLong()
                        ring.add(line(seq, LogLevel.D, "thread $t eintrag $i"))
                    }
                } catch (thrown: Throwable) {
                    errors.add(thrown)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertTrue("Thread-Fehler: $errors", errors.isEmpty())
        assertEquals(2000, ring.size)
        val seen = ring.snapshot(2000).map { it.seq }.toSet()
        assertEquals((0L until 2000L).toSet(), seen)
    }

    @Test
    fun `format setzt zeit stufe tag und rueckt folgezeilen ein`() {
        val single = line(1, LogLevel.I, "fertig").format()
        // matches() prueft die ganze Zeile, Anker sind deshalb unnoetig.
        assertTrue(single, Regex("""\d{2}:\d{2}:\d{2}\.\d{3} I/Test {2}fertig""").matches(single))

        val multi = line(2, LogLevel.E, "kaputt\ndanach").format()
        assertEquals(2, multi.split("\n").size)
        assertTrue(multi.contains("kaputt\n    danach"))
    }
}
