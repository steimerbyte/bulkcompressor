package com.steimerbyte.bulkcompressor.stats

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Die Laufhistorie ohne Geraet: Die drei Zusagen, auf denen die
 * Statistikseite steht, sind genau die, die beim Lesen des Codes leicht
 * brechen - Summe ueber alle Laeufe trotz gekappter Liste, kein doppelter
 * Eintrag fuer denselben Lauf, und eine kaputte Datei kostet hoechstens
 * die Anzeige, nie die App.
 *
 * Robolectric nur wegen org.json: die Unit-Test-Variante der Android-
 * Bibliothek wirft bei jedem Aufruf.
 */
@RunWith(RobolectricTestRunner::class)
class RunHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file() = File(temp.root, "run-history.json")

    private fun record(id: Long, savedBytes: Long = 1000, files: Int = 4, smaller: Int = 3) =
        RunRecord(id = id, folder = "Intern / DCIM", files = files, savedFiles = smaller, savedBytes = savedBytes)

    // ------------------------------------------------------------------
    // Die Regel, was als Lauf zaehlt
    // ------------------------------------------------------------------

    @Test
    fun `uebersprungene und gescheiterte dateien sind kein erfolg`() {
        val entry = runRecord(
            id = 1L,
            folder = "Intern",
            total = 10,
            done = 10,
            skipped = 2,
            failed = 3,
            savedBytes = 700
        )!!

        assertEquals("uebersprungen und gescheitert nicht mitzaehlen", 5, entry.savedFiles)
        assertEquals(10, entry.files)
        assertEquals(700L, entry.savedBytes)
    }

    @Test
    fun `ein lauf ohne bearbeitete datei bekommt keinen eintrag`() {
        assertNull(
            runRecord(id = 1L, folder = "Intern", total = 0, done = 0, skipped = 0, failed = 0, savedBytes = 0)
        )
        assertNull(
            runRecord(id = 2L, folder = "Intern", total = 5, done = 0, skipped = 0, failed = 0, savedBytes = 0)
        )
    }

    @Test
    fun `mehr abgelehnte als bearbeitete dateien werden nicht negativ`() {
        val entry = runRecord(
            id = 3L,
            folder = "Intern",
            total = 3,
            done = 3,
            skipped = 2,
            failed = 2,
            savedBytes = 0
        )!!

        assertEquals(0, entry.savedFiles)
    }

    @Test
    fun `gleichzeitige Schreiber verlieren keinen Lauf`() {
        // Der Dienst schreibt waehrend eines Laufs, die Oberflaeche liest -
        // das sind zwei Instanzen auf zwei Threads. Ohne ein Schloss je
        // Datei wuerde ein Lesen-Schreiben-Paar den Eintrag eines anderen
        // ueberschreiben.
        val threads = 6
        val perThread = 10
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) { slot ->
            Thread({
                start.await()
                repeat(perThread) { index ->
                    RunHistory(file()).record(record(id = slot * 1000L + index))
                }
                done.countDown()
            }, "schreiber-$slot").start()
        }

        start.countDown()
        assertTrue("Schreiber haengen", done.await(20, java.util.concurrent.TimeUnit.SECONDS))

        val history = RunHistory(file()).load()
        assertEquals(threads * perThread, history.totals.runs)
        assertEquals(threads * perThread, history.runs.size)
        assertEquals(threads * perThread, history.runs.map { it.id }.toSet().size)
    }

    @Test
    fun `ohne Datei ist die Historie leer`() {
        val history = RunHistory(file()).load()
        assertEquals(History(), history)
    }

    @Test
    fun `ein Lauf landet in Liste und Summe`() {
        val store = RunHistory(file())
        val history = store.record(record(id = 1_700_000_000_000, savedBytes = 4096))

        assertEquals(1, history.totals.runs)
        assertEquals(4L, history.totals.files)
        assertEquals(3L, history.totals.savedFiles)
        assertEquals(4096L, history.totals.savedBytes)
        assertEquals("Intern / DCIM", history.runs.single().folder)
    }

    @Test
    fun `Summe waechst ueber die Kappung hinaus`() {
        val store = RunHistory(file())
        repeat(RunHistory.MAX_RUNS + 5) { index ->
            store.record(record(id = 1000L + index, savedBytes = 10, files = 1, smaller = 1))
        }

        val history = store.load()
        // Die Liste ist gekappt, die Summe nicht - sonst saehe es aus, als
        // haette die App nur 100 Laeufe lang Platz geschaffen.
        assertEquals(RunHistory.MAX_RUNS, history.runs.size)
        assertEquals(RunHistory.MAX_RUNS + 5, history.totals.runs)
        assertEquals((RunHistory.MAX_RUNS + 5) * 10L, history.totals.savedBytes)
        // Neueste zuerst in der Liste, aeltester Eintrag herausgefallen.
        assertEquals(1000L + RunHistory.MAX_RUNS + 4, history.runs.last().id)
        assertEquals(1000L + 5, history.runs.first().id)
    }

    @Test
    fun `derselbe Lauf wird nicht doppelt gezaehlt`() {
        val store = RunHistory(file())
        store.record(record(id = 7L, savedBytes = 500))
        val history = store.record(record(id = 7L, savedBytes = 500))

        assertEquals(1, history.totals.runs)
        assertEquals(1, history.runs.size)
        assertEquals(500L, history.totals.savedBytes)
    }

    @Test
    fun `kaputte Datei kostet nur die Anzeige`() {
        file().writeText("{ das ist kein json")
        val store = RunHistory(file())
        assertEquals(History(), store.load())

        // Und danach laeuft es normal weiter - die kaputte Datei wird
        // durch einen gueltigen Stand ersetzt, nicht weitervererbt.
        val history = store.record(record(id = 42L))
        assertEquals(1, history.totals.runs)
        assertTrue(JSONObject(file().readText()).has("runs"))
    }

    @Test
    fun `die Datei bleibt sparsam`() {
        val store = RunHistory(file())
        repeat(10) { store.record(record(id = 100L + it)) }
        val text = file().readText()
        // Zehn Laeufe passen in wenige hundert Bytes: kein Schluessel-
        // geschoepfe, feste kurze Namen.
        assertTrue("Datei ist ${text.length} Zeichen gross", text.length < 1200)
        assertTrue(text.startsWith("{\"v\":1"))
    }

    @Test
    fun `ein alter Stand ohne Summenfelder wird nachgerechnet`() {
        // Moeglich, wenn eine spaetere Fassung Felder ergaenzt: die Liste
        // ist vorhanden, die Summe fehlt.
        file().writeText(
            JSONObject()
                .put("v", 1)
                .put(
                    "runs",
                    org.json.JSONArray().put(
                        JSONObject().put("id", 5L).put("f", "Intern").put("n", 2).put("s", 1).put("b", 300L)
                    )
                )
                .toString()
        )

        val history = RunHistory(file()).load()
        assertEquals(1, history.totals.runs)
        assertEquals(300L, history.totals.savedBytes)
        assertEquals(1L, history.totals.savedFiles)
    }
}
