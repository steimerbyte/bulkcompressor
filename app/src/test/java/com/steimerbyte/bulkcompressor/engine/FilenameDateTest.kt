package com.steimerbyte.bulkcompressor.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Das Aufnahmedatum aus dem Dateinamen.
 *
 * Diese Quelle ist die schwaechste, die es gibt - und deshalb muss sie
 * beide Richtungen koennen: echte Namen erkennen **und** Zahlenfolgen
 * ablehnen, die nur wie ein Datum aussehen. Ein falsch gesetztes Datum
 * sortiert die Galerie um, ein fehlendes bleibt harmlos.
 *
 * Die Zeitzone wird im Test fest gesetzt: die Ableitung ist bewusst
 * Ortszeit, und ein Test, der von der Zone des Build-Rechners abhaengt,
 * ist kein Test.
 */
class FilenameDateTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun berlinSetzen() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun zoneZuruecksetzen() {
        TimeZone.setDefault(originalZone)
    }

    /** Fester Bezugspunkt: 01.06.2025 18:00 Ortszeit. */
    private val now = millis(2025, 6, 1, 18, 0, 0)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        GregorianCalendar(TimeZone.getDefault()).apply {
            isLenient = false
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    private fun utcHour(millis: Long): Int =
        GregorianCalendar(TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = millis }
            .get(Calendar.HOUR_OF_DAY)

    @Test
    fun `erkennt Pixel und Samsung`() {
        assertEquals(millis(2024, 3, 15, 14, 25, 30), FilenameDate.parse("IMG_20240315_142530.jpg", now)?.millis)
        assertEquals(millis(2024, 3, 15, 14, 25, 30), FilenameDate.parse("VID_20240315_142530.mp4", now)?.millis)
        // Pixel haengt drei Ziffern Bruchteil an - sie duerfen nicht in die
        // Sekunde laufen.
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("PXL_20240315_142530123.jpg", now)?.millis
        )
    }

    @Test
    fun `erkennt Screenshots mit Bindestrich`() {
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("Screenshot_20240315-142530.png", now)?.millis
        )
    }

    @Test
    fun `erkennt Messenger-Namen`() {
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("WhatsApp Image 2024-03-15 at 14.25.30.jpeg", now)?.millis
        )
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("signal-2024-03-15-14-25-30.jpg", now)?.millis
        )
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("photo_2024-03-15_14-25-30.jpg", now)?.millis
        )
    }

    @Test
    fun `erkennt deutsches Geraetewort um`() {
        // Android schreibt seinen Bildschirmfoto-Namen in der Geraete-
        // sprache; "um" steht zwischen Datum und Uhrzeit.
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("Bildschirmfoto 2024-03-15 um 14.25.30.png", now)?.millis
        )
    }

    @Test
    fun `erkennt nacktes Kompaktdatum`() {
        assertEquals(
            millis(2024, 3, 15, 14, 25, 30),
            FilenameDate.parse("20240315_142530.mp4", now)?.millis
        )
    }

    @Test
    fun `Datum ohne Uhrzeit ergibt Mittag`() {
        val guess = FilenameDate.parse("IMG_20240315.jpg", now)
        assertNotNull("Ein Tag am Namensende ist ein Datum", guess)
        assertEquals(false, guess!!.hasTime)
        assertEquals(millis(2024, 3, 15, 12, 0, 0), guess.millis)
    }

    @Test
    fun `unmoegliche Tage werden abgelehnt`() {
        // Wichtig: der Parser darf bei ungueltigen Tagen nicht
        // "grosszuegig" runden. Der Kalender laeuft mit isLenient = false,
        // sonst wird aus dem 31.02. ein 03. Maerz.
        assertNull(FilenameDate.parse("IMG_20240231_120000.jpg", now))
        assertNull(FilenameDate.parse("20241315_120000.mp4", now))
        assertNull("Stunde 24 gibt es nicht", FilenameDate.parse("IMG_20240315_240000.jpg", now))
    }

    @Test
    fun `Schaltjahr wird unterschieden`() {
        assertNotNull(FilenameDate.parse("IMG_20240229_120000.jpg", now))
        assertNull("2023 hatte keinen 29. Februar", FilenameDate.parse("IMG_20230229_120000.jpg", now))
    }

    @Test
    fun `Zahlenfolgen ohne Datum werden abgelehnt`() {
        assertNull(FilenameDate.parse("DSC_0001.jpg", now))
        assertNull(FilenameDate.parse("Beleg_4711.pdf", now))
        assertNull("Jahr vor 2000", FilenameDate.parse("IMG_19981231_120000.jpg", now))
        assertNull(FilenameDate.parse("2024.jpg", now))
        assertNull(FilenameDate.parse("", now))
    }

    @Test
    fun `Ziffernfolge in der Mitte ist kein Datum`() {
        // Ohne Uhrzeit und nicht am Ende: das ist eine Nummer, kein Tag.
        assertNull(FilenameDate.parse("Rechnung 20240315 Kunde.jpg", now))
    }

    @Test
    fun `Datum in der Zukunft wird verworfen`() {
        assertNull(FilenameDate.parse("IMG_20261231_120000.jpg", now))
        // Ein Tag Vorlauf bleibt erlaubt: Kamerauhren laufen manchmal
        // leicht vor, und die Aufnahme kann von morgen stammen.
        assertNotNull(FilenameDate.parse("IMG_20250602_120000.jpg", now))
    }

    @Test
    fun `Sommerzeit wird beruecksichtigt`() {
        // Dieselbe Wanduhrzeit liegt im Sommer eine Stunde frueher in UTC
        // als im Winter. Ein Parser, der fest mit einem Versatz rechnet,
        // verrutscht die halbe Jahreszeit um 60 Minuten.
        val winter = FilenameDate.parse("IMG_20240115_140000.jpg", now)!!.millis
        val sommer = FilenameDate.parse("IMG_20240715_140000.jpg", now)!!.millis
        assertEquals("Winterzeit: 14:00 Ortszeit ist 13:00 UTC", 13, utcHour(winter))
        assertEquals("Sommerzeit: 14:00 Ortszeit ist 12:00 UTC", 12, utcHour(sommer))
        val tage = (sommer - winter) / 86_400_000L
        assertTrue("Der Abstand muss ein halbes Jahr sein, war $tage Tage", tage in 181..183)
    }
}
