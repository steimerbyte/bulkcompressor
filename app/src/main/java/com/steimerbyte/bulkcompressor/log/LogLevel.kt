package com.steimerbyte.bulkcompressor.log

/**
 * Schweregrad einer Logzeile, bewusst kurz gehalten: der Buchstabe landet
 * direkt im Dateiformat ("I/Video") und muss dort lesbar bleiben.
 *
 * Die Reihenfolge ist aufsteigend - wer spaeter filtern will, kann die
 * Ordinalwerte vergleichen.
 */
enum class LogLevel { V, D, I, W, E }
