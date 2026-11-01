package com.steimerbyte.bulkcompressor.engine.tag

import android.net.Uri

/**
 * Was in einem Dokument steht: markiert, nicht markiert - oder unbekannt.
 *
 * Der dritte Zustand ist der Grund fuer diese Datei. Vorher gab es nur
 * `CompressionTag?`, und `null` bedeutete zweierlei: "kein Marker drin" und
 * "konnte nicht gelesen werden". Der Nutzer hat den Unterschied zu spueren
 * bekommen: ein Ordner war komprimiert, der zweite Scan meldete "nichts
 * komprimiert", weil der Anbieter einen nicht-seekbaren Deskriptor lieferte
 * und das Scheitern stillschweigend zu `null` wurde.
 */
enum class MarkerState {

    /** Der Marker steht in der Datei; [MarkerReading.tag] ist gesetzt. */
    MARKED,

    /** Gelesen und nichts gefunden: die Datei ist nicht bearbeitet. */
    CLEAR,

    /**
     * Der Marker liess sich nicht lesen - der Zustand ist **unbekannt**,
     * nicht "nicht komprimiert".
     *
     * Wer hier `CLEAR` annimmt, komprimiert eine bereits komprimierte Datei
     * ein zweites Mal (Qualitaetsverlust) oder verschweigt sie dem Nutzer.
     * Beides ist verboten: die Oberflaeche zaehlt und nennt den Grund.
     */
    UNREADABLE
}

/**
 * Das Ergebnis einer Marker-Pruefung.
 *
 * [reason] ist bei [MarkerState.UNREADABLE] nie leer - eine Zeile, die dem
 * Nutzer sagt, warum diese Datei nicht eingeordnet werden konnte. Bei
 * [MarkerState.MARKED] und [MarkerState.CLEAR] bleibt sie leer, weil es
 * nichts zu erklaeren gibt.
 */
data class MarkerReading(
    val state: MarkerState,
    val tag: CompressionTag? = null,
    /** Warum UNREADABLE - nie leer, wenn der Zustand unbekannt ist. */
    val reason: String = ""
)

/**
 * Die **einzige** Quelle fuer "diese Datei war schon in einem Lauf".
 *
 * Job und Scan fragen dieselbe Schnittstelle, damit sie sich in der
 * Reaktion unterscheiden koennen (der Scan listet die Datei mit Warnung,
 * der Job ueberspringt sie mit Grund), aber nie in der Einordnung. Ein
 * zweiter Leser waere eine zweite Wahrheit - und genau daraus entstand der
 * Fehler, den diese Schnittstelle behebt.
 */
interface MarkerProbe {

    /** Ordnet ein Dokument ein. Wirft nicht: jeder Fehlschlag ist UNREADABLE. */
    fun probe(uri: Uri): MarkerReading
}
