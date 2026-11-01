package com.steimerbyte.bulkcompressor.engine

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.InputStream

/**
 * Die ersten [count] Bytes eines Stroms - ohne `readNBytes`.
 *
 * `InputStream.readNBytes` gibt es in Androids libcore erst ab API 33. Auf
 * `minSdk 24` wirft der Aufruf dort `NoSuchMethodError`; und weil Markersuche
 * wie Ausgabepruefung in `runCatching` stecken, wird daraus kein Absturz,
 * sondern ein stiller Fehlschlag: der Marker gilt als nicht vorhanden, und
 * die Pruefung meldet einen unbekannten Container. Genau das traefe jedes
 * Geraet mit Android 7 bis 12.
 */
internal fun InputStream.readAtMost(count: Int): ByteArray {
    if (count <= 0) return ByteArray(0)
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(out, filled, count - filled)
        if (read <= 0) break
        filled += read
    }
    return if (filled == count) out else out.copyOf(filled)
}

/**
 * Groesse und Ende eines Dateideskriptors, ohne dessen Besitz zu uebernehmen.
 *
 * Hier stand einmal `FileInputStream(pfd.fileDescriptor)`: dessen `close()`
 * schliesst auf Android den Deskriptor mit, und der umgebende `use`-Block
 * schloss ihn danach ein zweites Mal. Ein doppelt geschlossener Deskriptor
 * trifft aber womoeglich schon die Nummer, die ein anderer Thread in der
 * Zwischenzeit geoeffnet hat - ein Fehler, der sich als zufaelliger
 * Lesefehler an ganz anderer Stelle zeigt. `pread` liest ohne Besitz und
 * ohne gemeinsamen Dateizeiger.
 */
internal object DescriptorIo {

    /** Dateigroesse; der Deskriptor bleibt offen. */
    fun size(fd: FileDescriptor): Long = Os.lseek(fd, 0L, OsConstants.SEEK_END)

    /** [count] Bytes ab [offset]; kuerzer, wenn die Datei dort endet. */
    fun at(fd: FileDescriptor, offset: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = Os.pread(fd, buffer, filled, count - filled, offset + filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == count) buffer else buffer.copyOf(filled)
    }

    /** Die letzten [count] Bytes; null bei leerer Datei. */
    fun tail(fd: FileDescriptor, count: Int): ByteArray? {
        val size = size(fd)
        if (size <= 0) return null
        val read = minOf(count.toLong(), size).toInt()
        return at(fd, size - read, read)
    }
}
