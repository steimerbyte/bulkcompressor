package com.steimerbyte.bulkcompressor.job

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.steimerbyte.bulkcompressor.log.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Ersetzt eine Originaldatei an ihrem Platz, ohne sie bei einem Abbruch
 * zu zerstoeren.
 *
 * Das Problem: SAF kennt kein atomares Ersetzen. Der Schreibkanal auf ein
 * Dokument kuerzt die Datei in dem Moment, in dem er geoeffnet wird.
 * Bricht das Kopieren danach ab - Akku leer, Prozess beendet, Speicher
 * voll, USB-Verbindung weg - steht eine halbe Datei da und das Original
 * ist verloren. Bei einem Stapellauf ueber Hunderte Videos ist das keine
 * theoretische Sorge, sondern die eine Sache, die den Nutzer wirklich
 * trifft: seine Datei ist weg, nicht nur schlecht komprimiert.
 *
 * Der Ausweg ist eine Rueckfallebene im Cache: das Original wird vor dem
 * Schreiben kopiert, danach wird die neue Groesse geprueft, und bei jedem
 * Fehlschlag wird die Kopie zurueckgeschrieben. Kostet eine Kopie und
 * doppelten Platz; beides ist billig gegen den Verlust einer Datei, und
 * wenn der Platz nicht reicht, sagt das Protokoll es vorher deutlich.
 *
 * Das Kopieren und Zurueckschreiben steckt in [copy] mit austauschbaren
 * Kanaelen, damit es ohne Geraet pruefbar ist.
 */
class SafeReplace(
    private val cacheDir: File,
    private val freeSpace: () -> Long = { cacheDir.usableSpace }
) {

    /** Ergebnis einer Ersetzung. */
    sealed interface Outcome {
        /** Die neue Datei steht am Platz und hat die erwartete Groesse. */
        data object Written : Outcome

        /** Schreiben scheiterte, das Original wurde aus der Kopie wiederhergestellt. */
        data class Restored(val reason: String) : Outcome

        /** Schreiben scheiterte und es gab keine Rueckfallebene. */
        data class Damaged(val reason: String) : Outcome
    }

    /**
     * Schreibt [payload] in das Dokument [target] und sichert vorher das
     * Original.
     *
     * @param sourceSize Groesse des Originals; 0, wenn unbekannt.
     * @param expectedSize Groesse, die das Ziel danach haben muss.
     * @param verifyTarget Inhaltliche Pruefung nach dem Schreiben:
     *        `null`, wenn das Ziel in Ordnung ist, sonst den Grund. Ein
     *        Befund hier fuehrt zum Zurueckschreiben des Originals - die
     *        Groesse allein reicht nicht, weil ein Container mit richtiger
     *        Laenge und kaputtem Rest sie erfuellt.
     */
    fun replace(
        resolver: ContentResolver,
        target: Uri,
        payload: File,
        sourceSize: Long,
        expectedSize: Long,
        label: String,
        verifyTarget: () -> String?
    ): Outcome = replaceCore(
        payload = payload,
        sourceSize = sourceSize,
        expectedSize = expectedSize,
        label = label,
        openOriginalInput = { resolver.openInputStream(target) },
        openOriginalOutput = { openWritable(resolver, target) },
        sizeOfTarget = { sizeOf(resolver, target) },
        verifyTarget = verifyTarget
    )

    /**
     * Der eigentliche Ablauf, ohne SAF: Original sichern, schreiben,
     * pruefen, im Fehlerfall zurueckschreiben. Die Kanaele kommen von
     * aussen, damit derselbe Weg im Test mit Speicherkanaelen laeuft -
     * auf einem Geraet kaeme der Fehlerfall sonst nie vor.
     */
    internal fun replaceCore(
        payload: File,
        sourceSize: Long,
        expectedSize: Long,
        label: String,
        openOriginalInput: () -> InputStream?,
        openOriginalOutput: () -> OutputStream?,
        sizeOfTarget: () -> Long,
        verifyTarget: () -> String?
    ): Outcome {
        val backup = prepareBackup(openOriginalInput, sourceSize, label)
        val reason = try {
            val written = writePayload(openOriginalOutput, payload)
            val actual = sizeOfTarget()
            when {
                written <= 0 -> "es wurden keine Bytes geschrieben"
                actual >= 0 && expectedSize > 0 && actual != expectedSize ->
                    "Groesse nach dem Schreiben $actual statt $expectedSize"
                else -> verifyTarget()?.let { "Inhalt: $it" }
            }
        } catch (t: Throwable) {
            "Schreiben fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}"
        }

        if (reason == null) {
            backup?.delete()
            return Outcome.Written
        }

        if (backup == null) {
            Log.e("Replace", "$label · $reason · keine Rueckfallebene vorhanden, Original bleibt beschaedigt")
            return Outcome.Damaged(reason)
        }

        val challenges = restore(openOriginalOutput, backup, label)
        val space = backup.length()
        backup.delete()
        return if (challenges) {
            Log.w("Replace", "$label · $reason · Original aus $space Bytes Kopie wiederhergestellt")
            Outcome.Restored(reason)
        } else {
            Log.e("Replace", "$label · $reason · Wiederherstellung ebenfalls fehlgeschlagen, Kopie lag in ${backup.path}")
            Outcome.Damaged(reason)
        }
    }

    private fun prepareBackup(
        openOriginalInput: () -> InputStream?,
        sourceSize: Long,
        label: String
    ): File? {
        val needed = sourceSize + RESERVE_BYTES
        val available = freeSpace()
        if (sourceSize > 0 && available < needed) {
            Log.w(
                "Replace",
                "$label · keine Rueckfallebene: Cache hat ${available / 1024 / 1024} MB frei," +
                    " gebraucht werden ${needed / 1024 / 1024} MB (Original + Reserve)"
            )
            return null
        }
        val backup = File(cacheDir, "replace_${System.currentTimeMillis()}.bak")
        val copied = runCatching {
            val input = openOriginalInput() ?: throw IOException("Original nicht lesbar")
            input.use { source -> copy(source, backup.outputStream(), null) }
            backup.length()
        }.getOrElse { t ->
            Log.w("Replace", "$label · Rueckfallebene nicht anlegbar: ${t.message}", t)
            backup.delete()
            return null
        }
        if (sourceSize > 0 && copied != sourceSize) {
            Log.w(
                "Replace",
                "$label · Rueckfallebene unvollstaendig ($copied von $sourceSize Bytes) - verworfen"
            )
            backup.delete()
            return null
        }
        Log.d("Replace", "$label · Rueckfallebene im Cache: $copied Bytes")
        return backup
    }

    private fun writePayload(openOriginalOutput: () -> OutputStream?, payload: File): Long {
        val out = openOriginalOutput() ?: throw IOException("Datei nicht beschreibbar")
        out.use { sink ->
            payload.inputStream().use { source -> return copy(source, sink, null) }
        }
    }

    private fun restore(openOriginalOutput: () -> OutputStream?, backup: File, label: String): Boolean =
        runCatching {
            val out = openOriginalOutput() ?: throw IOException("Datei nicht beschreibbar")
            out.use { sink ->
                backup.inputStream().use { source ->
                    val written = copy(source, sink, null)
                    if (written != backup.length()) {
                        throw IOException("nur $written von ${backup.length()} Bytes zurueckgeschrieben")
                    }
                }
            }
            true
        }.getOrElse { t ->
            Log.e("Replace", "$label · Rueckschreiben der Kopie fehlgeschlagen", t)
            false
        }

    /**
     * "wt" kuerzt die Datei und ist der Modus, den der
     * ExternalStorageProvider erwartet. Manche Provider kennen nur "w" -
     * dann ist das der Rueckfall.
     */
    private fun openWritable(resolver: ContentResolver, target: Uri): OutputStream? =
        runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(target, "w") }.getOrNull()

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    companion object {
        /** Platz, der neben der Kopie immer frei bleiben muss. */
        private const val RESERVE_BYTES = 64L * 1024 * 1024

        private const val BUFFER = 64 * 1024

        /**
         * Kopiert [source] nach [sink] und liefert die Anzahl Bytes.
         * [onChunk] bekommt nach jedem Block die Summe - so kann der
         * Aufrufer abbrechen, ohne dass diese Klasse einen Zustand kennt.
         */
        internal fun copy(source: InputStream, sink: OutputStream, onChunk: ((Long) -> Unit)?): Long {
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                total += read
                onChunk?.invoke(total)
            }
            sink.flush()
            return total
        }

        /** Nur fuer das Protokoll: ist der Zielpfad ein Dokument der App? */
        internal fun isDocument(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT

        /** Dokument-Kennung eines SAF-Dokuments, sonst null. */
        internal fun documentId(uri: Uri): String? = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull()
    }
}
