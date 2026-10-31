package com.steimerbyte.bulkcompressor.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Prueft die Download-Logdatei ohne Android-Runtime: die Sitzung schreibt
 * in einen echten Ordner, benennt sich wie am Geraet und beginnt bei
 * Ueberschreiten der Groessengrenze einen zweiten Teil. Genau diese drei
 * Dinge entscheiden, ob der Nutzer die Datei im Download-Ordner findet.
 */
class DownloadsLogTest {

    private fun tempDir(): File = Files.createTempDirectory("downloads-log").toFile()

    @Test
    fun `sitzung schreibt in die erwartete datei`() {
        val dir = tempDir()
        val started = 1_700_000_000_000L
        val session = DownloadsLog.openFileSession(dir, started)
        try {
            assertTrue("Sitzung ist nicht beschreibbar", session.isWritable)
            session.append("zeile eins\n")
            session.append("zeile zwei\n")
            session.flush()

            val file = File(dir, DownloadsLog.sessionName(started))
            assertTrue("Datei fehlt: ${file.absolutePath}", file.isFile)
            assertEquals("zeile eins\nzeile zwei\n", file.readText())
            assertEquals(DownloadsLog.displayPath(file.name), session.path)
            assertEquals(listOf(DownloadsLog.displayPath(file.name)), session.paths)
        } finally {
            session.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `grosse sitzung bekommt einen zweiten teil`() {
        val dir = tempDir()
        val started = 1_700_000_000_000L
        val session = DownloadsLog.openFileSession(dir, started, maxChars = 20)
        try {
            session.append("0123456789012345678\n") // 20 Zeichen: Grenze erreicht
            session.append("nach der Grenze\n")
            session.flush()

            val first = File(dir, DownloadsLog.sessionName(started))
            val second = File(dir, DownloadsLog.sessionName(started, 2))
            assertTrue("erster Teil fehlt", first.isFile)
            assertTrue("zweiter Teil fehlt - Ueberlauf landet sonst im Nichts", second.isFile)
            assertEquals("0123456789012345678\n", first.readText())
            assertTrue(second.readText().startsWith("nach der Grenze"))
            assertEquals(listOf(first.name, second.name).map(DownloadsLog::displayPath), session.paths)
            assertEquals(DownloadsLog.displayPath(second.name), session.path)
        } finally {
            session.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `schreiben nach dem schliessen wirft nicht`() {
        val dir = tempDir()
        val session = DownloadsLog.openFileSession(dir, 1_700_000_000_000L)
        session.append("vorher\n")
        session.close()
        // Ein spaeter Logaufruf darf keinen Kompressionslauf umbringen.
        session.append("nachher\n")
        session.flush()
        assertFalse(session.isWritable)
        assertTrue(File(dir, DownloadsLog.sessionName(1_700_000_000_000L)).isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `dateinamen sind sortierbar und enthalten die uhrzeit`() {
        val name = DownloadsLog.sessionName(1_700_000_000_000L)
        assertTrue("Praefix fehlt: $name", name.startsWith("bulkcompressor-"))
        assertTrue("Endung fehlt: $name", name.endsWith(".log"))
        assertTrue("Zeitstempel fehlt: $name", Regex("""bulkcompressor-\d{8}-\d{6}\.log""").matches(name))
        // Kein fester Zeitstempel: der haengt an der Zeitzone des Rechners.
        val second = DownloadsLog.sessionName(1_700_000_000_000L, 2)
        assertTrue("Teilsuffix fehlt: $second", second.endsWith("-teil2.log"))
        assertFalse(
            "Teil 1 und Teil 2 haben denselben Namen",
            DownloadsLog.sessionName(1_700_000_000_000L) == second
        )
    }
}
