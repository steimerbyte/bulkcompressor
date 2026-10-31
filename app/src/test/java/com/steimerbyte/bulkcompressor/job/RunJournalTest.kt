package com.steimerbyte.bulkcompressor.job

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Prueft das Lauf-Journal ohne Geraet: genau der Fall, der auf einem
 * Testgeraet nie eintritt - ein Prozess, der mitten im Schreiben stirbt.
 */
class RunJournalTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file() = File(temp.root, "run.journal")

    @Test
    fun `start schreibt die volle Dateiliste`() {
        val journal = RunJournal.forFile(file())
        journal.start("content://tree/dcim", listOf("a.jpg", "b.mp4"))
        journal.close()

        val snapshot = RunJournal.loadFile(file())!!
        assertEquals("content://tree/dcim", snapshot.treeUri)
        assertEquals(listOf("a.jpg", "b.mp4"), snapshot.queue)
        assertEquals(0, snapshot.doneCount)
        assertEquals(2, snapshot.remaining.size)
        assertFalse(snapshot.finished)
    }

    @Test
    fun `mark haelt Ergebnisse fest und nimmt sie aus der Liste`() {
        val journal = RunJournal.forFile(file())
        journal.start("content://tree", listOf("a", "b", "c"))
        journal.mark("a", RunJournal.Result.OK, beforeBytes = 100, afterBytes = 30)
        journal.mark("b", RunJournal.Result.SKIP)
        journal.mark("c", RunJournal.Result.FAIL, reason = "Codec defekt")
        journal.close()

        val snapshot = RunJournal.loadFile(file())!!
        assertEquals(3, snapshot.doneCount)
        assertEquals(RunJournal.Result.SKIP, snapshot.completed["b"])
        assertEquals(RunJournal.Result.FAIL, snapshot.completed["c"])
        assertEquals(70L, snapshot.beforeBytes - snapshot.afterBytes)
        assertTrue(snapshot.remaining.isEmpty())
    }

    @Test
    fun `unterbrochener Lauf liefert die offenen Dateien`() {
        val journal = RunJournal.forFile(file())
        journal.start("content://tree", listOf("a", "b", "c", "d"))
        journal.mark("a", RunJournal.Result.OK, 10, 4)
        journal.mark("b", RunJournal.Result.OK, 10, 4)
        journal.close()

        val snapshot = RunJournal.loadFile(file())!!
        assertEquals(listOf("c", "d"), snapshot.remaining)
        assertTrue(snapshot.interrupted)
        assertTrue(snapshot.describe().contains("2 von 4 erledigt"))
    }

    @Test
    fun `abgeschnittene letzte Zeile verhindert das Fortsetzen nicht`() {
        val journal = RunJournal.forFile(file())
        journal.start("content://tree", listOf("a", "b"))
        journal.close()
        file().appendText("doc a ok 10")

        val snapshot = RunJournal.loadFile(file())
        assertNotNull(snapshot)
        assertEquals(listOf("a", "b"), snapshot!!.remaining)
    }

    @Test
    fun `finish loescht das Journal`() {
        val journal = RunJournal.forFile(file())
        journal.start("content://tree", listOf("a"))
        journal.mark("a", RunJournal.Result.OK, 5, 2)
        journal.finish("fertig")

        assertNull(RunJournal.loadFile(file()))
    }

    @Test
    fun `resume haengt an und kuerzt nicht`() {
        RunJournal.forFile(file()).apply {
            start("content://tree", listOf("a", "b"))
            mark("a", RunJournal.Result.OK, 9, 3)
            close()
        }
        val resumed = RunJournal.forFile(file())
        assertTrue(resumed.resume())
        resumed.mark("b", RunJournal.Result.OK, 9, 3)
        resumed.close()

        val snapshot = RunJournal.loadFile(file())!!
        assertEquals(2, snapshot.doneCount)
        assertEquals(listOf("a", "b"), snapshot.queue)
    }

    @Test
    fun `Umkodierung haelt Leerzeichen und Prozentzeichen aus dem Format`() {
        val tricky = "content://tree/DCIM/Meine Bilder/100%25.jpg"
        val journal = RunJournal.forFile(file())
        journal.start(tricky, listOf("Mein Video (1).mp4", "foo%bar"))
        journal.close()

        val snapshot = RunJournal.loadFile(file())!!
        assertEquals(tricky, snapshot.treeUri)
        assertEquals(listOf("Mein Video (1).mp4", "foo%bar"), snapshot.queue)
    }

    @Test
    fun `fremde Datei mit falschem Kopf wird verworfen`() {
        val alien = file()
        alien.writeText("irgendwas anderes\n")
        assertNull(RunJournal.loadFile(alien))
        assertFalse(alien.exists())
    }
}
