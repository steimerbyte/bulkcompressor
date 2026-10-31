package com.steimerbyte.bulkcompressor.job

import com.steimerbyte.bulkcompressor.engine.MemoryGuard
import com.steimerbyte.bulkcompressor.ui.StatsFormat

/**
 * Was in der Fortschrittsbenachrichtigung steht.
 *
 * Zwei Zeilen, weil sie zwei verschiedene Fragen beantworten: die
 * Fortschrittszeile sagt, wie weit der Lauf ist und wie viel Speicher er
 * gerade zieht, der Dateiname sagt, was in diesem Moment bearbeitet wird.
 *
 * Der Speicher gehoert in die immer sichtbare Zeile, nicht in den
 * zugeklappten Kopfbereich: `subText` blenden manche Hersteller aus, und
 * "wie knapp wird es" ist genau die Information, die man im Vorbeischauen
 * braucht - ohne die App zu oeffnen.
 *
 * Ohne Android-Abhaengigkeiten, damit die Zahlenfaelle (leerer Lauf,
 * Heap ueber 1 GB, kein Geraetespeicher ermittelbar) ohne Geraet
 * pruefbar sind.
 */
object NotificationText {

    /**
     * "[12 / 300] · RAM [412/768 MB] · Gerät [2,10 GB frei]" - das
     * Druckwort kommt nur dazu, wenn es eines gibt; bei freiem Speicher
     * waere es Rauschen neben einer Zahl, die schon alles sagt.
     */
    fun progressLine(
        done: Int,
        total: Int,
        heapUsed: Long,
        heapMax: Long,
        deviceFree: Long,
        level: MemoryGuard.Level
    ): String = buildString {
        append(done.coerceAtLeast(0)).append(" / ").append(total.coerceAtLeast(0))
        append(" · RAM ").append(StatsFormat.formatHeapPair(heapUsed, heapMax))
        if (deviceFree > 0) {
            append(" · Gerät ").append(StatsFormat.formatMb(deviceFree)).append(" frei")
        }
        if (level != MemoryGuard.Level.OK) append(" · ").append(level.label)
    }
}
