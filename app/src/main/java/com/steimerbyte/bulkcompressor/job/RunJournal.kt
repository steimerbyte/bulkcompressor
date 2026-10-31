package com.steimerbyte.bulkcompressor.job

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.Writer

/**
 * Dauerhafter Zustand eines Auftrags.
 *
 * Ein Auftrag ueber Hunderte Videos laeuft Stunden bis Tage. In dieser
 * Zeit beendet Android den Prozess regelmaessig - Speicherdruck, Thermal,
 * ein App-Update, "Akku-Optimierung" eines Herstellers. Ohne Journal
 * waere der Lauf dann wortlos weg: die Benachrichtigung verschwindet, die
 * Oberflaeche zeigt nichts, und der Nutzer merkt erst Tage spaeter, dass
 * bei Datei 143 Schluss war.
 *
 * Das Journal liegt in `filesDir` und ist damit klein, unauffaellig und
 * ohne Berechtigung schreibbar. Es haelt die angeforderte Dateiliste und
 * je Datei das Ergebnis. Ein neuer Start desselben Auftrags nimmt die
 * erledigten Dateien heraus und arbeitet beim Rest weiter.
 *
 * Format: eine Zeile je Eintrag, Feldtrenner ist genau ein Leerzeichen.
 * Zeilen, die sich nicht lesen lassen (Stromausfall mitten im Schreiben),
 * werden beim Laden uebergangen - ein halb geschriebenes Journal darf den
 * Lauf nicht verhindern.
 */
class RunJournal private constructor(
    val file: File,
    private var writer: Writer?
) {

    /** Ergebnis einer Datei, wie es im Journal steht. */
    enum class Result(val token: String) {
        /** Erfolgreich ersetzt oder neu angelegt. */
        OK("ok"),

        /** Uebersprungen, weil keine Einsparung zu erwarten war. */
        SKIP("skip"),

        /** Fehlgeschlagen; die Begruendung steht als Rest der Zeile dabei. */
        FAIL("fail")
    }

    /**
     * Legt den Auftrag an und ueberschreibt ein Journal der Vorsitzung.
     * Die Dateiliste steht sofort vollstaendig in der Datei: erst danach
     * faengt die Arbeit an, sonst fehlte nach einem Abbruch die Haelfte.
     */
    fun start(treeUri: String, docIds: List<String>) {
        val out = open(append = false) ?: return
        val now = System.currentTimeMillis()
        out.write("run $FORMAT\n")
        out.write("started $now\n")
        out.write("tree ${encode(treeUri)}\n")
        for (id in docIds) out.write("queue ${encode(id)}\n")
        out.flush()
    }

    /**
     * Haelt das Ergebnis einer Datei fest und schickt es sofort auf die
     * Platte. Eine Schreiboperation je Datei ist gemessen am Transcode
     * kostenlos, und nur so ueberlebt der Eintrag einen Absturz im
     * naechsten Bild.
     */
    fun mark(docId: String, result: Result, beforeBytes: Long = 0, afterBytes: Long = 0, reason: String = "") {
        val out = writer ?: return
        val line = when (result) {
            Result.FAIL -> "doc ${encode(docId)} ${result.token} ${encode(reason)}"
            else -> "doc ${encode(docId)} ${result.token} $beforeBytes $afterBytes"
        }
        runCatching {
            out.write(line + "\n")
            out.flush()
        }.onFailure { close() }
    }

    /**
     * Setzt einen unterbrochenen Lauf fort: die Datei wird nur noch
     * angehaengt. Die Dateiliste steht schon drin, sie darf nicht neu
     * geschrieben werden - sonst waere der Fortschritt weg.
     */
    fun resume(): Boolean = open(append = true) != null

    /** Schliesst den Auftrag ab; die Datei verschwindet. */
    fun finish(reason: String) {
        val out = writer
        runCatching {
            out?.write("end ${System.currentTimeMillis()} ${encode(reason)}\n")
            out?.flush()
        }
        close()
        runCatching { file.delete() }
    }

    fun close() {
        val out = writer ?: return
        writer = null
        runCatching { out.flush() }
        runCatching { out.close() }
    }

    val isOpen: Boolean get() = writer != null

    private fun open(append: Boolean): Writer? {
        close()
        return runCatching {
            file.parentFile?.mkdirs()
            FileWriter(file, append).also { writer = it }
        }.getOrNull()
    }

    companion object {
        private const val FORMAT = 1

        /** Namen der Journaldatei im app-privaten Speicher. */
        private const val NAME = "run.journal"

        /** Ein Journal aelter als das wird nicht wieder aufgenommen. */
        const val MAX_RESUME_AGE_MS = 24L * 60 * 60 * 1000

        fun fileFor(context: Context): File = File(context.filesDir, NAME)

        /** Neues Journal fuer diesen Prozess; die Datei wird beim Start gekuerzt. */
        fun open(context: Context): RunJournal = RunJournal(fileFor(context), null)

        /** Journal auf einer beliebigen Datei - fuer Pruefungen ohne Geraet. */
        fun forFile(file: File): RunJournal = RunJournal(file, null)

        /**
         * Liest ein vorhandenes Journal. Liefert null, wenn keins da ist,
         * es sich nicht oeffnen laesst oder der Kopf fehlt (dann ist es
         * wertlos und wird geloescht).
         */
        fun load(context: Context): Snapshot? = loadFile(fileFor(context))

        fun loadFile(file: File): Snapshot? {
            if (!file.isFile) return null
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            val queue = ArrayList<String>()
            val completed = LinkedHashMap<String, Result>()
            var tree: String? = null
            var startedAt = 0L
            var endedAt = 0L
            var totalBefore = 0L
            var totalAfter = 0L
            var sawHeader = false

            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split(' ')
                when (parts[0]) {
                    "run" -> sawHeader = parts.size > 1 && parts[1].toIntOrNull() == FORMAT
                    "started" -> startedAt = parts.getOrNull(1)?.toLongOrNull() ?: startedAt
                    "tree" -> tree = parts.getOrNull(1)?.let { decode(it) }
                    "queue" -> parts.getOrNull(1)?.let { queue.add(decode(it)) }
                    "doc" -> {
                        val id = parts.getOrNull(1)?.let { decode(it) } ?: continue
                        val result = Result.entries.firstOrNull { it.token == parts.getOrNull(2) } ?: continue
                        // Eine abgebrochene letzte Zeile hat zu wenige
                        // Felder. Sie wird uebergangen - die Datei gilt
                        // dann als offen und wird erneut bearbeitet.
                        val expectedFields = if (result == Result.FAIL) 3 else 5
                        if (parts.size < expectedFields) continue
                        completed[id] = result
                        if (result == Result.OK) {
                            totalBefore += parts.getOrNull(3)?.toLongOrNull() ?: 0L
                            totalAfter += parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        }
                    }
                    "end" -> endedAt = parts.getOrNull(1)?.toLongOrNull() ?: endedAt
                }
            }

            val treeUri = tree
            if (!sawHeader || treeUri == null || queue.isEmpty()) {
                runCatching { file.delete() }
                return null
            }
            val updatedAt = runCatching { file.lastModified() }.getOrDefault(0L)
            return Snapshot(
                treeUri = treeUri,
                queue = queue,
                completed = completed,
                startedAt = startedAt,
                updatedAt = updatedAt,
                finished = endedAt > 0,
                beforeBytes = totalBefore,
                afterBytes = totalAfter
            )
        }

        /**
         * Leerzeichen und Zeilenumbrueche brechen das Format, Prozent
         * koennte ein echter Bestandteil einer URI sein - deshalb wird
         * alles Aussergewoehnliche als %XX geschrieben.
         */
        internal fun encode(value: String): String {
            if (value.isEmpty()) return "%empty"
            val out = StringBuilder(value.length)
            for (ch in value) {
                // Prozent gehoert NICHT zu den erlaubten Zeichen: sonst
                // waere ein echtes Prozentzeichen von einem kodierten
                // Zeichen nicht zu unterscheiden, und "100%25.jpg" kaeme
                // als "100%.jpg" zurueck.
                if (ch.isLetterOrDigit() || ch == ':' || ch == '/' || ch == '.' || ch == '_' || ch == '-' || ch == '~') {
                    out.append(ch)
                } else {
                    out.append('%').append(String.format("%02X", ch.code and 0xFF))
                }
            }
            return out.toString()
        }

        internal fun decode(value: String): String {
            if (value == "%empty") return ""
            if (!value.contains('%')) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%' && i + 2 < value.length) {
                    val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 3
                        continue
                    }
                }
                out.append(ch)
                i++
            }
            return out.toString()
        }
    }

    /**
     * Gelesener Stand des Journals. [remaining] ist die Arbeitsliste des
     * naechsten Starts, [finished] nur dann true, wenn der Auftrag mit
     * einem Abschluss versehen wurde.
     */
    data class Snapshot(
        val treeUri: String,
        val queue: List<String>,
        val completed: Map<String, Result>,
        val startedAt: Long,
        val updatedAt: Long,
        val finished: Boolean,
        val beforeBytes: Long,
        val afterBytes: Long
    ) {
        val doneCount: Int get() = completed.size
        val total: Int get() = queue.size
        val remaining: List<String> get() = queue.filterNot { completed.containsKey(it) }

        /** Fehlt irgendwo der Abschluss, wurde der Lauf unterbrochen. */
        val interrupted: Boolean get() = !finished

        fun ageMs(now: Long = System.currentTimeMillis()): Long = now - updatedAt

        /** Kurze Zeile fuer Protokoll und Oberflaeche. */
        fun describe(): String {
            val ok = completed.count { it.value == Result.OK }
            val skip = completed.count { it.value == Result.SKIP }
            val fail = completed.count { it.value == Result.FAIL }
            return "$doneCount von $total erledigt ($ok ersetzt, $skip uebersprungen, $fail fehlgeschlagen)" +
                " · ${remaining.size} offen"
        }
    }
}
