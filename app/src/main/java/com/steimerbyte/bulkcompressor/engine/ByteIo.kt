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

/** So viel wird je Aufruf gelesen, wenn [InputStream.copyTail] den Ring fuellt. */
private const val TAIL_CHUNK_BYTES = 16 * 1024

/**
 * Die letzten [count] Bytes eines Stroms - als Ringpuffer.
 *
 * Gebraucht wird das fuer den Marker am Dateiende eines Videos: er steht in
 * einer `uuid`-Box, und die ist nie groesser als ein paar hundert Bytes.
 * Ueber einen Strom gibt es keinen Sprung ans Ende; ein SAF-Anbieter liefert
 * dafuer nur einen Deskriptor, und der ist nicht immer seekbar (siehe
 * [DescriptorIo] - genau daran scheiterte die Markersuche, wenn der Anbieter
 * eine Pipe reichte). Deshalb wird der Strom hier bis zum Ende gelesen und
 * nur der Ring behalten: der Speicher bleibt bei [count] Bytes, egal wie
 * gross die Datei ist. Der Preis ist die Lesemenge - ein 2-GB-Video geht
 * einmal durch den Anbieter, dafuer wird kein einziges Frame dekodiert.
 *
 * Liefert weniger als [count] Bytes, wenn der Strom kuerzer ist; nie null.
 * Bricht der Strom ab, fliegt die Ausnahme des Stroms - ein halber Ring ist
 * kein Dateiende und damit kein Beweis fuer "kein Marker".
 */
internal fun InputStream.copyTail(count: Int): ByteArray {
    if (count <= 0) return ByteArray(0)
    val ring = ByteArray(count)
    val chunk = ByteArray(count.coerceAtMost(TAIL_CHUNK_BYTES))
    var next = 0 // naechste Schreibstelle im Ring
    var held = 0 // gueltige Bytes, hoechstens count
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        if (read >= count) {
            // Der Block allein uebertrifft den Ring: nur sein Ende zaehlt.
            System.arraycopy(chunk, read - count, ring, 0, count)
            next = 0
            held = count
            continue
        }
        val first = minOf(read, count - next)
        System.arraycopy(chunk, 0, ring, next, first)
        if (first < read) System.arraycopy(chunk, first, ring, 0, read - first)
        next = (next + read) % count
        held = minOf(count, held + read)
    }
    // Ohne Umlauf steht das Gelesene ab Index 0 und ist schon die Antwort.
    if (held < count) return ring.copyOf(held)
    // Mit Umlauf beginnt das Aelteste dort, wo als naechstes geschrieben
    // wuerde.
    if (next == 0) return ring
    val last = ByteArray(count)
    System.arraycopy(ring, next, last, 0, count - next)
    System.arraycopy(ring, 0, last, count - next, next)
    return last
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
