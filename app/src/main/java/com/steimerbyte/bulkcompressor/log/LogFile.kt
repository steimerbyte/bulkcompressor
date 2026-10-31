package com.steimerbyte.bulkcompressor.log

import java.io.File
import java.io.FileOutputStream

/**
 * Dateisenke des Logs mit Groessenrotation.
 *
 * Bewusst reines Java-IO ohne Android-Importe: das Verhalten ist damit im
 * Unit-Test pruefbar, und die Datei-Schreibpfade brauchen kein Context.
 *
 * Dateien im Verzeichnis:
 *  - `app.log`     aktuelle Datei
 *  - `app.log.1`   vorherige Datei
 *  - `app.log.2`   davor, usw. bis `app.log.(keep-1)`
 *  - `crash.log`   Append-Datei des Absturzpfads, wird nie rotiert
 *
 * `keep` zaehlt die aktuelle Datei mit: bei `keep = 3` bleiben insgesamt
 * drei Dateien liegen (app.log, .1, .2), die aelteste faellt weg.
 *
 * Alle Methoden schlucken `IOException`: Logging darf den Aufrufer nie
 * umbringen. Ein voller Speicher ist ein Grund fuer eine fehlende Zeile,
 * nicht fuer einen Absturz beim Komprimieren.
 */
class LogFile(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = DEFAULT_KEEP
) {

    /** Aktuelle Logdatei; sie wird beim ersten Schreiben angelegt. */
    fun current(): File = File(dir, FILE_NAME)

    /** Absturzdatei; sie waechst als Historie und wird nur von Hand geleert. */
    fun crashFile(): File = File(dir, CRASH_NAME)

    /**
     * Haengt die Zeilen in einem Schreibvorgang an. Vorher wird geprueft, ob
     * die Datei die Groessengrenze sprengen wuerde; dann rotiert sie.
     */
    fun append(lines: List<LogLine>) {
        if (lines.isEmpty()) return
        runCatching {
            ensureDir()
            val payload = buildString {
                for (line in lines) append(line.format()).append('\n')
            }.toByteArray(Charsets.UTF_8)

            val target = current()
            if (target.length() + payload.size > maxBytes) rotate()

            // Kein fd.sync() hier: geschriebene Bytes ueberleben den
            // Prozess-Tod im Pagecache des Kernels (das ist der Absturzfall),
            // und ein fsync pro Zeile wuerde auf Flash rund 1-5 ms je Zeile
            // kosten. Der Absturzpfad synct selbst, weil dort niemand mehr
            // nachschreiben kann.
            FileOutputStream(target, true).use { out ->
                out.write(payload)
                out.flush()
            }
        }
    }

    /**
     * Die letzten [maxLines] Zeilen der aktuellen Datei, aelteste zuerst.
     * Die Datei ist durch [maxBytes] begrenzt, deshalb wird sie am Stueck
     * gelesen - das ist billiger und weniger fehleranfaellig als ein
     * Rueckwaerts-Scan.
     */
    fun readTail(maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        return runCatching {
            val file = current()
            if (!file.isFile || file.length() == 0L) {
                emptyList()
            } else {
                val all = file.readText(Charsets.UTF_8)
                    .split('\n')
                    .filter { it.isNotEmpty() }
                if (all.size <= maxLines) all else all.subList(all.size - maxLines, all.size)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Schreibt einen Absturzbericht: Kopfzeile, Ringpuffer-Auszug und
     * vollstaendigen Stacktrace, alles in einem Schreibvorgang.
     */
    fun appendCrash(header: String, ring: List<LogLine>, stackTrace: String) {
        runCatching {
            ensureDir()
            val report = buildString {
                append(header.trimEnd()).append('\n')
                append("---- Ringpuffer (").append(ring.size).append(" Zeilen) ----\n")
                for (line in ring) append(line.format()).append('\n')
                append("---- Stacktrace ----\n")
                append(stackTrace.trimEnd()).append('\n')
                append("==== Ende des Absturzberichts ====\n\n")
            }.toByteArray(Charsets.UTF_8)

            FileOutputStream(crashFile(), true).use { out ->
                out.write(report)
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** Loescht alle Logdateien, inklusive der rotierten und des Absturzlogs. */
    fun clear() {
        runCatching {
            for (file in dir.listFiles().orEmpty()) {
                val name = file.name
                val ours = name == FILE_NAME ||
                    name == CRASH_NAME ||
                    name.startsWith(FILE_NAME + ".")
                if (ours) runCatching { file.delete() }
            }
        }
    }

    /**
     * Rotiert: `app.log.(keep-1)` faellt weg, alle anderen ruecken eine
     * Nummer weiter, `app.log` wird zu `app.log.1`.
     */
    private fun rotate() {
        if (keep <= 1) {
            // Nur die aktuelle Datei halten: alte weg, neu anfangen.
            runCatching { current().delete() }
            return
        }
        runCatching { File(dir, "$FILE_NAME.${keep - 1}").delete() }
        for (index in keep - 2 downTo 1) {
            val from = File(dir, "$FILE_NAME.$index")
            if (from.isFile) {
                val to = File(dir, "$FILE_NAME.${index + 1}")
                runCatching { to.delete() }
                runCatching { from.renameTo(to) }
            }
        }
        runCatching { current().renameTo(File(dir, "$FILE_NAME.1")) }
    }

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    companion object {
        const val FILE_NAME = "app.log"
        const val CRASH_NAME = "crash.log"

        /** 512 KB pro Datei, drei Dateien: gut zwei Sitzungen Historie. */
        const val DEFAULT_MAX_BYTES = 512L * 1024L
        const val DEFAULT_KEEP = 3
    }
}
