package com.steimerbyte.bulkcompressor.engine.tag

/**
 * Der Marker, der in die Metadaten jeder bearbeiteten Datei geschrieben
 * wird - "diese Datei war schon in einem Lauf".
 *
 * Zweck: Wer denselben Ordner ein zweites Mal auswaehlt, soll nicht
 * versehentlich alles noch einmal verlustbehaftet komprimieren. Der
 * Marker steht **in der Datei selbst**, nicht in einer Liste der App:
 * damit ueberlebt er einen Neuinstall, einen anderen Dateimanager und
 * einen anderen Ordner.
 *
 * Format, eine Zeile, ASCII, stabil:
 *
 * ```
 * nothing-bulk:1 runs=2 src=4194304 out=1048576 at=1762000000 app=1.7.0
 * ```
 *
 * Gelesen wird bewusst tolerant: gesucht wird der Marker irgendwo in
 * einem vorhandenen Kommentar, unbekannte Schluessel werden ignoriert und
 * eine fremde Versionsnummer macht den Marker nicht ungueltig. Eine
 * strikte Grammatik wuerde bei der ersten Format-Aenderung alle alten
 * Dateien als "nicht komprimiert" einstufen - und genau das ist der
 * Fehler, den der Marker verhindern soll.
 */
data class CompressionTag(
    /** Wie oft diese Datei durch einen Lauf ging, mindestens 1. */
    val runs: Int = 1,
    /** Groesse vor dem ersten Lauf, 0 wenn unbekannt. */
    val sourceBytes: Long = 0,
    /** Groesse nach dem letzten Lauf, 0 wenn unbekannt. */
    val resultBytes: Long = 0,
    /** Zeitpunkt des letzten Laufs in Millisekunden, 0 wenn unbekannt. */
    val atMillis: Long = 0,
    /** Version der App, die den Marker zuletzt geschrieben hat. */
    val appVersion: String = ""
) {

    /** Die Zeile, die in die Metadaten wandert. */
    fun format(): String = buildString(48) {
        append(MAGIC).append(':').append(VERSION)
        append(" runs=").append(runs.coerceAtLeast(1))
        if (sourceBytes > 0) append(" src=").append(sourceBytes)
        if (resultBytes > 0) append(" out=").append(resultBytes)
        if (atMillis > 0) append(" at=").append(atMillis)
        if (appVersion.isNotBlank()) append(" app=").append(appVersion)
    }

    /** Kurzform fuer eine Protokollzeile. */
    fun describe(): String = buildString {
        append("runs=").append(runs)
        if (sourceBytes > 0 && resultBytes > 0) {
            append(" · ").append(sourceBytes).append(" B -> ").append(resultBytes).append(" B")
        }
        if (atMillis > 0) append(" · ").append(atMillis)
    }

    companion object {
        const val MAGIC = "nothing-bulk"
        const val VERSION = 1

        /**
         * Findet den Marker an beliebiger Stelle eines Kommentars. Der
         * Bereich hinter dem Marker reicht bis zum naechsten `|` oder
         * Zeilenende - so bleibt ein angehaengter Fremdkommentar
         * ("nothing-bulk:1 ... | mein Text") lesbar.
         */
        private val PATTERN = Regex("$MAGIC:(\\d+)((?:\\s+[A-Za-z]+=[^\\s|]*)*)")

        /** Liest den Marker; `null`, wenn keiner drinsteht. */
        fun find(raw: String?): CompressionTag? {
            if (raw.isNullOrBlank()) return null
            val match = PATTERN.find(raw) ?: return null
            val fields = HashMap<String, String>(4)
            for (pair in match.groupValues[2].trim().split(' ')) {
                val at = pair.indexOf('=')
                if (at <= 0) continue
                fields[pair.substring(0, at)] = pair.substring(at + 1)
            }
            return CompressionTag(
                runs = fields["runs"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                sourceBytes = fields["src"]?.toLongOrNull() ?: 0,
                resultBytes = fields["out"]?.toLongOrNull() ?: 0,
                atMillis = fields["at"]?.toLongOrNull() ?: 0,
                appVersion = fields["app"].orEmpty()
            )
        }

        fun isTagged(raw: String?): Boolean = find(raw) != null

        /**
         * Setzt den Marker in einen vorhandenen Kommentar, ohne fremden
         * Text zu verlieren. Ein Kommentar des Nutzers oder einer anderen
         * App ist dessen Eigenschaft, nicht unsere Ablage.
         *
         * Steht schon ein Marker drin, wird er ersetzt (der Lauf-Zaehler
         * kommt aus dem gelesenen Wert). Steht ein fremder Text drin,
         * kommt der Marker davor - so bleibt er auch dann auffindbar,
         * wenn der fremde Text sehr lang ist.
         */
        fun merge(existing: String?, tag: CompressionTag): String {
            val fresh = tag.format()
            if (existing.isNullOrBlank()) return fresh
            val match = PATTERN.find(existing)
            if (match != null) {
                return existing.substring(0, match.range.first) + fresh +
                    existing.substring(match.range.last + 1)
            }
            return "$fresh | $existing"
        }
    }
}
