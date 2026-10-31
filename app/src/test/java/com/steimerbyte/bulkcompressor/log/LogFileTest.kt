package com.steimerbyte.bulkcompressor.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Rotation und Rueckwaertslesen der Dateisenke. Wichtig fuer den Neustart:
 * eine falsche Rotation loescht entweder die Historie oder laesst die Datei
 * unbegrenzt wachsen.
 */
class LogFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(seq: Long, filler: String = "x") =
        LogLine(seq, 1_700_000_000_000L + seq, LogLevel.I, "Test", filler + seq)

    @Test
    fun `rotation legt app_log_1 an und beginnt eine neue datei`() {
        // Eine Zeile ist rund 45 Byte lang, 400 Byte fassen also wenige.
        val sink = LogFile(tmp.root, maxBytes = 400, keep = 3)
        repeat(40) { sink.append(listOf(line(it.toLong(), "abcdefghijklmnopqrstuvwxyz"))) }

        val current = File(tmp.root, LogFile.FILE_NAME)
        assertTrue("app.log muss nach der Rotation neu entstehen", current.isFile)
        assertTrue("app.log.1 muss angelegt sein", File(tmp.root, "app.log.1").isFile)
        assertTrue("app.log.2 muss angelegt sein", File(tmp.root, "app.log.2").isFile)
        assertFalse("bei keep=3 darf app.log.3 nicht entstehen", File(tmp.root, "app.log.3").exists())
    }

    @Test
    fun `readtail liefert die letzten zeilen in reihenfolge`() {
        val sink = LogFile(tmp.root, maxBytes = 64 * 1024, keep = 3)
        for (i in 0 until 10) sink.append(listOf(line(i.toLong())))

        val tail = sink.readTail(3)
        assertEquals(3, tail.size)
        assertTrue(tail[0], tail[0].endsWith("x7"))
        assertTrue(tail[2], tail[2].endsWith("x9"))
        assertEquals(10, sink.readTail(100).size)
        assertTrue(sink.readTail(0).isEmpty())
    }

    @Test
    fun `crashbericht landet in eigener datei und wird angehaengt`() {
        val sink = LogFile(tmp.root)
        sink.appendCrash("Kopf eins", listOf(line(1)), "java.lang.RuntimeException: test")
        sink.appendCrash("Kopf zwei", listOf(line(2)), "java.lang.IllegalStateException: test")

        val crash = sink.crashFile()
        assertTrue(crash.isFile)
        val text = crash.readText()
        assertTrue(text.contains("Kopf eins"))
        assertTrue(text.contains("Kopf zwei"))
        assertTrue(text.contains("RuntimeException"))
        // Die Crashdatei ist nicht die aktuelle Logdatei.
        assertFalse(sink.current().isFile)
    }

    @Test
    fun `clear loescht aktuelle rotierte und crashdatei`() {
        val sink = LogFile(tmp.root, maxBytes = 200, keep = 3)
        repeat(20) { sink.append(listOf(line(it.toLong(), "abcdefghijklmnopqrstuvwxyz"))) }
        sink.appendCrash("Kopf", listOf(line(1)), "trace")

        sink.clear()

        assertEquals(emptyList<String>(), tmp.root.listFiles()?.map { it.name }?.filter { it.startsWith("app.log") || it == "crash.log" })
    }
}
