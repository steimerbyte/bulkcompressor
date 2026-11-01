package com.steimerbyte.bulkcompressor.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Die Prioritaetskette fuer den Zeitstempel.
 *
 * Die Regel, um die es geht: eine gemessene Zeit schlaegt eine abgeleitete.
 * Der Dateiname darf nur einspringen, wenn die Quelle nichts liefert - er
 * ist eine Vermutung aus Text, waehrend die Quellzeit das ist, wonach die
 * Galerie die Datei bisher eingeordnet hat.
 */
class TimestampResolverTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun berlin() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun zoneZurueck() {
        TimeZone.setDefault(originalZone)
    }

    private val now = millis(2025, 6, 1, 18, 0, 0)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        GregorianCalendar(TimeZone.getDefault()).apply {
            isLenient = false
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun `Quellzeit schlaegt den Dateinamen`() {
        val source = millis(2023, 12, 24, 9, 30, 0)
        val decision = TimestampResolver.decide(
            sourceMillis = source,
            fileName = "IMG_20240315_142530.jpg",
            now = now
        )
        assertEquals(TimestampResolver.Source.SOURCE_TIME, decision.source)
        assertEquals(source, decision.millis)
    }

    @Test
    fun `ohne Quellzeit entscheidet der Dateiname`() {
        val decision = TimestampResolver.decide(
            sourceMillis = 0L,
            fileName = "IMG_20240315_142530.jpg",
            now = now
        )
        assertEquals(TimestampResolver.Source.FILE_NAME, decision.source)
        assertEquals(millis(2024, 3, 15, 14, 25, 30), decision.millis)
        assertTrue("Das Muster muss im Protokoll landen", decision.detail.isNotEmpty())
    }

    @Test
    fun `ohne beide Quellen bleibt nichts`() {
        val decision = TimestampResolver.decide(0L, "DSC_0001.jpg", now)
        assertEquals(TimestampResolver.Source.NONE, decision.source)
        assertEquals(0L, decision.millis)
    }

    @Test
    fun `Quellzeit in der Zukunft ist unplausibel`() {
        // Eine falsch gestellte Geraeteuhr steht auch in der Quellzeit. Sie
        // wuerde die Datei an den Anfang der Galerie setzen, also wird sie
        // wie eine fehlende behandelt - und der Name bekommt seine Chance.
        val decision = TimestampResolver.decide(
            sourceMillis = now + 40L * 86_400_000L,
            fileName = "IMG_20240315_142530.jpg",
            now = now
        )
        assertEquals(TimestampResolver.Source.FILE_NAME, decision.source)
        assertEquals(millis(2024, 3, 15, 14, 25, 30), decision.millis)
    }

    @Test
    fun `ein Tag Vorlauf der Quellzeit bleibt gueltig`() {
        val source = now + 3_600_000L
        val decision = TimestampResolver.decide(source, "IMG_20240315_142530.jpg", now)
        assertEquals(TimestampResolver.Source.SOURCE_TIME, decision.source)
        assertEquals(source, decision.millis)
    }

    @Test
    fun `negative Quellzeit faellt durch`() {
        val decision = TimestampResolver.decide(-1L, "IMG_20240315_142530.jpg", now)
        assertEquals(TimestampResolver.Source.FILE_NAME, decision.source)
    }
}
