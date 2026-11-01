package com.steimerbyte.bulkcompressor.job

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Prueft die Rueckfallebene beim In-Place-Ersetzen.
 *
 * Der interessante Fall ist der Fehlschlag: auf einem Geraet tritt er
 * dann auf, wenn der Nutzer den Auftrag abbricht oder der Akku ausgeht -
 * und genau dann muss das Original unveraendert dastehen. Mit
 * Speicherkanaelen laesst sich dieser Fall erzwingen.
 */
class SafeReplaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Ein Dokument, das sich wie eine SAF-Datei verhaelt. */
    private class FakeDocument(
        var bytes: ByteArray,
        private val failAfter: Long = -1,
        private val failOnlyFirst: Boolean = false
    ) {
        var opens = 0
            private set

        fun openOutput(): OutputStream {
            opens++
            // Wie ein echter SAF-Kanal: beim Oeffnen wird gekuerzt.
            bytes = ByteArray(0)
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val broken = failAfter >= 0 && (toByteArray().size + len) > failAfter &&
                        (!failOnlyFirst || opens == 1)
                    if (broken) {
                        super.write(b, off, off + 1)
                        throw IOException("Kanal weg")
                    }
                    super.write(b, off, len)
                }

                override fun close() {
                    bytes = toByteArray()
                    super.close()
                }
            }
        }

        fun openInput(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun payload(bytes: String): File =
        temp.newFile("payload.tmp").apply { writeText(bytes) }

    private fun replace(
        document: FakeDocument,
        payload: File,
        expectedSize: Long,
        freeSpace: Long = 1024L * 1024 * 1024,
        verify: String? = null
    ) = SafeReplace(temp.root, freeSpace = { freeSpace }).replaceCore(
        payload = payload,
        sourceSize = document.bytes.size.toLong(),
        expectedSize = expectedSize,
        label = "test.mp4",
        openOriginalInput = { document.openInput() },
        openOriginalOutput = { document.openOutput() },
        sizeOfTarget = { document.bytes.size.toLong() },
        verifyTarget = { verify }
    )

    @Test
    fun `erfolgreiches Schreiben ersetzt das Original`() {
        val document = FakeDocument("original-daten".toByteArray())
        val outcome = replace(document, payload("neu"), expectedSize = 3)

        assertTrue(outcome is SafeReplace.Outcome.Written)
        assertArrayEquals("neu".toByteArray(), document.bytes)
    }

    @Test
    fun `Abbruch mitten im Schreiben stellt das Original wieder her`() {
        val original = "0123456789".toByteArray()
        val document = FakeDocument(original.copyOf(), failAfter = 2, failOnlyFirst = true)
        val outcome = replace(document, payload("ABCDEFGHIJ"), expectedSize = 10)

        assertTrue("Erwartet: wiederhergestellt, war: $outcome", outcome is SafeReplace.Outcome.Restored)
        assertEquals(
            "Der erste Kanal scheitert, der zweite (Wiederherstellung) schreibt",
            2,
            document.opens
        )
        assertArrayEquals(original, document.bytes)
    }

    @Test
    fun `falsche Zielgroesse gilt als Fehlschlag und wird zurueckgedreht`() {
        val original = "original".toByteArray()
        val document = FakeDocument(original.copyOf())
        // Erwartet 99 Bytes, geschrieben werden 3 - so sieht eine halb
        // geschriebene Datei aus, die der Provider nicht bemerkt.
        val outcome = replace(document, payload("neu"), expectedSize = 99)

        assertTrue(outcome is SafeReplace.Outcome.Restored)
        assertArrayEquals(original, document.bytes)
    }

    @Test
    fun `ohne Platz fuer die Sicherung wird gar nicht erst geschrieben`() {
        val original = "original".toByteArray()
        val document = FakeDocument(original.copyOf(), failAfter = 1)
        val outcome = replace(
            document,
            payload("neu"),
            expectedSize = 3,
            freeSpace = 0
        )

        // Frueher wurde trotzdem geschrieben - und weil SAF die Datei schon
        // beim Oeffnen kuerzt, blieb dann eine halbe Datei stehen. Ohne
        // Rueckfallebene ist das Oeffnen bereits der Datenverlust, deshalb
        // verweigert der Austausch die Arbeit, bevor er das Ziel anfasst.
        assertTrue("Erwartet: Refused, war: $outcome", outcome is SafeReplace.Outcome.Refused)
        assertEquals(0, document.opens)
        assertArrayEquals(original, document.bytes)
    }

    @Test
    fun `leere Ausgabe gilt als Fehlschlag`() {
        val document = FakeDocument("original".toByteArray())
        val empty = temp.newFile("empty.tmp").apply { writeText("") }
        val outcome = replace(document, empty, expectedSize = 1)

        assertTrue(outcome is SafeReplace.Outcome.Restored)
        assertEquals("original", document.bytes.decodeToString())
    }

    @Test
    fun `copy liefert die Anzahl der Bytes`() {
        val source = ByteArrayInputStream(ByteArray(70_000) { 7 })
        val sink = ByteArrayOutputStream()
        val written = SafeReplace.copy(source, sink, null)

        assertEquals(70_000L, written)
        assertEquals(70_000, sink.toByteArray().size)
    }

    @Test
    fun `Sicherung wird nach Erfolg geloescht`() {
        val document = FakeDocument("original".toByteArray())
        val before = temp.root.listFiles()?.size ?: 0
        replace(document, payload("neu"), expectedSize = 3)
        val after = temp.root.listFiles()?.size ?: 0

        assertTrue("Cache darf keine Sicherung behalten", after <= before + 1)
        val backups = temp.root.listFiles()?.filter { it.name.endsWith(".bak") }.orEmpty()
        assertTrue("Sicherungen uebrig: $backups", backups.isEmpty())
    }

    @Test
    fun `Beanstandete Pruefung stellt das Original wieder her`() {
        val document = FakeDocument("original".toByteArray())
        val outcome = replace(document, payload("neu"), expectedSize = 3, verify = "kein ftyp an Offset 4")

        assertTrue(outcome is SafeReplace.Outcome.Restored)
        assertEquals("original", document.bytes.decodeToString())
    }
}
