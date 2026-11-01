package com.steimerbyte.bulkcompressor.engine

import android.content.ContentResolver
import android.net.Uri
import com.steimerbyte.bulkcompressor.log.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entscheidet, welcher Zeitstempel nach einem Lauf an der Datei steht.
 *
 * Der Zeitstempel ist der Grund, warum die App in-place arbeitet - er
 * entscheidet in der Galerie ueber die Position in der Chronologie. Nach
 * dem Neuschreiben ist er erst einmal der des Laufs, also "gerade eben";
 * alles, was der Nutzer sortiert hat, waere damit verschoben.
 *
 * Es gibt dafuer drei Quellen, und sie sind **nicht** gleich gut:
 *
 * 1. Die Aenderungszeit der **Quelldatei** ([decide] als
 *    `sourceMillis`) - das ist die staerkste Quelle: Kamera oder Anbieter
 *    haben sie selbst gesetzt, sie beschreibt den Zeitpunkt, den auch die
 *    Galerie bisher benutzt hat.
 * 2. Das Datum aus dem **Dateinamen** ([FilenameDate]) - die schwaechste
 *    Quelle, aber oft die einzige. Videos tragen meist gar kein EXIF,
 *    Screenshots und Messenger-Dateien werden ohne Aufnahmezeit
 *    geschrieben, und manche Anbieter liefern keine Aenderungszeit.
 * 3. Nichts. Dann bleibt die Datei auf der Zeit des Laufs und rutscht
 *    nach oben - sichtbar im Protokoll, statt still zu passieren.
 *
 * EXIF `DateTimeOriginal` steht daneben und ist fuer Bilder der bessere
 * Weg (siehe [MetaRestore.copyExif]); er wird hier nicht angefasst.
 */
object TimestampResolver {

    /** Ein Tag Vorlauf bleibt erlaubt: Kamerauhren laufen manchmal leicht vor. */
    private const val FUTURE_TOLERANCE_MS = 86_400_000L

    enum class Source(val label: String) {
        SOURCE_TIME("Aenderungszeit der Quelle"),
        FILE_NAME("Datum aus dem Dateinamen"),
        NONE("kein Datum")
    }

    /**
     * @param millis gesetzter Zeitpunkt; 0, wenn nichts gesetzt wird.
     * @param source woher der Zeitpunkt stammt - fuer das Protokoll.
     * @param detail welches Namensmuster gegriffen hat, leer sonst.
     */
    data class Decision(val millis: Long, val source: Source, val detail: String = "")

    /**
     * Die Prioritaetskette. Rein rechnerisch, ohne Android-Bezug, damit
     * sie ohne Geraet pruefbar bleibt.
     *
     * Die Quellzeit gewinnt immer, auch wenn der Dateiname ein anderes
     * Datum traegt: der Name ist eine Ableitung, die Quellzeit ist eine
     * Messung. Nur wenn sie fehlt oder unplausibel ist, wird der Name
     * befragt.
     *
     * @param sourceMillis Aenderungszeit der Quelldatei; <= 0 heisst
     *                     "der Anbieter lieferte keine".
     * @param fileName Name der Zieldatei, Endung inklusive.
     */
    fun decide(sourceMillis: Long, fileName: String, now: Long = System.currentTimeMillis()): Decision {
        if (sourceMillis > 0L && sourceMillis <= now + FUTURE_TOLERANCE_MS) {
            return Decision(sourceMillis, Source.SOURCE_TIME)
        }
        val guess = FilenameDate.parse(fileName, now)
        if (guess != null) {
            return Decision(guess.millis, Source.FILE_NAME, guess.pattern)
        }
        return Decision(0L, Source.NONE)
    }

    /**
     * Setzt den entschiedenen Zeitstempel und schreibt eine Zeile, die
     * sagt, woher er kam und ob das Setzen gewirkt hat.
     *
     * @return true nur, wenn der Wert danach wirklich an der Datei steht.
     */
    fun apply(resolver: ContentResolver, uri: Uri, decision: Decision): Boolean {
        if (decision.millis <= 0L) {
            Log.d("Meta", "Zeitstempel: kein Datum ermittelbar · bleibt auf Laufzeit · $uri")
            return false
        }
        val applied = MetaRestore.applyTimestamp(resolver, uri, decision.millis)
        val origin = when (decision.source) {
            Source.SOURCE_TIME -> Source.SOURCE_TIME.label
            Source.FILE_NAME -> "${Source.FILE_NAME.label} (${decision.detail})"
            Source.NONE -> Source.NONE.label
        }
        Log.d(
            "Meta",
            "Zeitstempel: ${format(decision.millis)} aus $origin" +
                (if (applied) "" else " · konnte nicht gesetzt werden") +
                " · $uri"
        )
        return applied
    }

    fun format(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date(millis))
}
