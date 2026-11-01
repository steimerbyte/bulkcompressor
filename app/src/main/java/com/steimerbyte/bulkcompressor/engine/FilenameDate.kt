package com.steimerbyte.bulkcompressor.engine

import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Aufnahmedatum aus dem Dateinamen ableiten.
 *
 * Warum: Nicht jede Quelle liefert ein Datum. Videos tragen in der Regel
 * gar keine EXIF-Daten, Screenshots und Messenger-Dateien werden ohne
 * Aufnahmezeit geschrieben, und ein Lauf, der die Aenderungszeit am Ziel
 * nicht setzen konnte (Provider ohne aufloesbaren Pfad), hinterlaesst eine
 * Datei mit dem Datum des Komprimierens. Die Galerie sortiert dann nach
 * "gerade eben" - die Chronologie ist hin. Sehr viele Kameras und
 * Messenger schreiben den Zeitpunkt aber **in den Dateinamen**, und der
 * ist die letzte verbleibende Quelle.
 *
 * Das ist bewusst die **schwaechste** Quelle: EXIF `DateTimeOriginal` und
 * eine vorhandene Aenderungszeit sind besser und haben Vorrang. Diese
 * Klasse beantwortet nur die Frage "steht im Namen ein plausibles
 * Datum?", sie entscheidet nicht, welches Datum gewinnt.
 *
 * Regeln:
 *
 * - Nur Jahre 2000-2099. Ein Namensfragment wie `19981231` in einem
 *   Dateinamen ist oefter eine Nummer als ein Datum.
 * - Das Datum muss ein echter Kalendertag sein (kein 31.02., kein Monat
 *   13). Geprueft wird mit einem nicht-nachsichtigen [GregorianCalendar].
 * - Zeitzone ist die **Ortszeit** des Geraets: Kameras schreiben die
 *   Wanduhrzeit, nicht UTC. Dieselbe Datei ergibt auf zwei Geraeten
 *   verschiedener Zonen verschiedene Zeitpunkte - das ist richtig so, der
 *   Zeitpunkt ist ja lokal gemeint.
 * - Liegt das Ergebnis mehr als einen Tag in der Zukunft, wird es
 *   verworfen: eine falsch gestellte Kamerauhr sortiert sonst alles nach
 *   oben, und ein Datum, das den Lauf selbst ueberholt, ist keine
 *   Aufnahmezeit.
 * - Steht im Namen nur ein Tag ohne Uhrzeit, wird 12:00 gesetzt
 *   ([Guess.hasTime] ist dann false). Mittags ist die Verschiebung im
 *   Tagesverlauf am kleinsten, egal zu welcher Stunde wirklich
 *   aufgenommen wurde.
 */
object FilenameDate {

    /**
     * @param millis Zeitpunkt in Ortszeit.
     * @param pattern Kurzform des Musters, das gegriffen hat - fuer das
     *                Protokoll ("IMG_JJJJMMTT_HHMMSS").
     * @param hasTime false, wenn im Namen nur das Datum stand.
     */
    data class Guess(val millis: Long, val pattern: String, val hasTime: Boolean)

    /**
     * Mustervorlagen, in dieser Reihenfolge geprueft. Traegt das erste
     * Muster kein gueltiges Datum (31.02.), wird das naechste versucht -
     * ein Dateiname kann mehrere Zahlenfolgen enthalten.
     */
    private class Rule(
        val label: String,
        val regex: Regex,
        /** false nur bei Datumsangaben ohne Uhrzeit. */
        val hasTime: Boolean = true
    )

    private fun pattern(body: String, anchored: Boolean = false): Regex =
        Regex(if (anchored) ".*$body\\s*$" else body, RegexOption.IGNORE_CASE)

    /**
     * Trenner vor der Uhrzeit. Deutsche Geraete schreiben "um", englische
     * "at" ("Bildschirmfoto 2024-03-15 um 14.25.30", "WhatsApp Image
     * 2024-03-15 at 14.25.30"); sonst steht dort ein Trennzeichen oder
     * gar nichts.
     */
    private val separator = "(?:\\s*(?:um|at)\\s*|[ _T.-]?)"
    private val time = separator + "(\\d{2})[:.-]?(\\d{2})[:.-]?(\\d{2})"
    private val date = "(20\\d{2})(\\d{2})(\\d{2})"
    private val isoDate = "(20\\d{2})[-_.](\\d{2})[-_.](\\d{2})"

    // 1. Kamera-Praefixe mit kompaktem Datum: IMG_20240315_142530,
    //    VID_20240315_142530, PXL_20240315_142530123 (Pixel, 9 Ziffern
    //    Bruchteil), Screenshot_20240315-142530, Bildschirmfoto,
    //    photo_2024-03-15_14-25-30 (Telegram), signal-2024-03-15-142530.
    private val cameraPrefix = Rule(
        "Praefix_JJJJMMTT_HHMMSS",
        pattern(
            "(?:IMG|VID|PXL|DSC|Screenshot|Bildschirmfoto|photo|signal|WA|MVIMG|" +
                "Screen[ _-]?recording)[_-]?\\s*" + date + time
        )
    )

    // 2. Ausgeschriebenes ISO-Datum mit Uhrzeit, beliebiger Praefix:
    //    "WhatsApp Image 2024-03-15 at 14.25.30", "2024-03-15 14:25:30",
    //    "Beleg 2024-03-15_14-25-30".
    private val isoWithTime = Rule(
        "JJJJ-MM-TT_HH:MM:SS",
        pattern(isoDate + time)
    )

    // 3. Nacktes kompaktes Datum mit Uhrzeit: 20240315_142530,
    //    20240315-142530. Bewusst nach den Praefix-Mustern, damit ein
    //    Datum im Praefix nicht als nackte Zahl missverstanden wird.
    private val compactWithTime = Rule(
        "JJJJMMTT_HHMMSS",
        pattern(date + time)
    )

    // 4. Nur ein Tag, am Namensende: "IMG_20240315", "Screenshot_2024-03-15".
    //    Nicht irgendwo im Namen - eine reine Ziffernfolge in der Mitte
    //    ist oefter eine Nummer als ein Datum.
    private val compactDateOnly = Rule(
        "JJJJMMTT",
        pattern(date, anchored = true),
        hasTime = false
    )

    private val isoDateOnly = Rule(
        "JJJJ-MM-TT",
        pattern(isoDate, anchored = true),
        hasTime = false
    )

    private val rules = listOf(
        cameraPrefix,
        isoWithTime,
        compactWithTime,
        compactDateOnly,
        isoDateOnly
    )

    /**
     * @param fileName Dateiname mit Endung; die Endung wird ignoriert,
     *                 weil sie nie ein Datum traegt.
     * @param now aktuelle Zeit in Millisekunden (einspeisbar, damit die
     *            Zukunftspruefung testbar ist).
     * @return der erste plausible Treffer oder null.
     */
    fun parse(fileName: String, now: Long = System.currentTimeMillis()): Guess? {
        if (fileName.isBlank()) return null
        val base = fileName.substringBeforeLast('.', fileName)
        if (base.length < 8) return null
        for (rule in rules) {
            val match = rule.regex.find(base) ?: continue
            val guess = toGuess(match, rule, now) ?: continue
            return guess
        }
        return null
    }

    private fun toGuess(match: MatchResult, rule: Rule, now: Long): Guess? {
        val groups = match.groupValues
        val year = groups.getOrNull(1)?.toIntOrNull() ?: return null
        val month = groups.getOrNull(2)?.toIntOrNull() ?: return null
        val day = groups.getOrNull(3)?.toIntOrNull() ?: return null
        val hour = if (rule.hasTime) groups.getOrNull(4)?.toIntOrNull() ?: return null else 12
        val minute = if (rule.hasTime) groups.getOrNull(5)?.toIntOrNull() ?: return null else 0
        val second = if (rule.hasTime) groups.getOrNull(6)?.toIntOrNull() ?: return null else 0
        if (hour > 23 || minute > 59 || second > 59) return null

        val calendar = GregorianCalendar(TimeZone.getDefault()).apply {
            isLenient = false
            clear()
        }
        val millis = runCatching {
            calendar.set(year, month - 1, day, hour, minute, second)
            calendar.timeInMillis
        }.getOrNull() ?: return null

        // Ein Datum, das den Lauf selbst ueberholt, ist keine Aufnahmezeit.
        if (millis > now + ONE_DAY_MS) return null
        return Guess(millis, rule.label, rule.hasTime)
    }

    private const val ONE_DAY_MS = 86_400_000L
}
