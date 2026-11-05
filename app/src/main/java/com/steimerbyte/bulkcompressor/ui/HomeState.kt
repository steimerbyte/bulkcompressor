package com.steimerbyte.bulkcompressor.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.steimerbyte.bulkcompressor.model.CompressSettings
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult

/** Was in der Liste steht. Der Lauf nimmt weiter die ganze Auswahl, nicht die Treffer. */
internal enum class MediaFilter(val label: String) {
    ALL("ALLE"), IMAGES("BILDER"), VIDEOS("VIDEOS")
}

/**
 * Alles, was der Hauptbildschirm an Zustand haelt: Ordner, Scan, Auswahl,
 * Suche, Filter und Preset.
 *
 * Bewusst eine eigene Klasse statt Feldern in der Activity: derselbe
 * Zustand laesst sich ohne Geraet pruefen, und rememberSaveable braucht
 * genau eine Stelle, an der er gesichert wird.
 */
internal class HomeState(
    initial: CompressSettings,
    private val persist: (CompressSettings) -> Unit
) {
    var folder by mutableStateOf<Uri?>(null)
    var scan by mutableStateOf<ScanResult?>(null)
    var scanning by mutableStateOf(false)
    var scanFolders by mutableStateOf(0)
    var scanFiles by mutableStateOf(0)
    var scanLive by mutableStateOf("")
    var scanError by mutableStateOf("")
    /** Zaehlt "Neu scannen" - der LaunchedEffect haengt daran. */
    var scanToken by mutableStateOf(0)
    /** Nur fuer Tests: verhindert, dass der Effekt das gesetzte Ergebnis ueberschreibt. */
    var scanningDisabled = false

    var query by mutableStateOf("")
    var filter by mutableStateOf(MediaFilter.ALL)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var presetOpen by mutableStateOf(false)

    var settings by mutableStateOf(initial)
        private set

    /** Nachher-Groesse je Dokument, gefuellt aus dem Lauf. */
    val afterBytes = mutableStateMapOf<String, Long>()

    fun updateSettings(next: CompressSettings) {
        settings = next
        persist(next)
    }

    /** Treffer der Suche und des Filters - die Auswahl bleibt davon unberuehrt. */
    fun rows(): List<MediaItem> {
        val q = query.trim()
        return scan?.items.orEmpty().filter { item ->
            matchesFilter(item) && (q.isEmpty() || item.displayName.contains(q, ignoreCase = true))
        }
    }

    fun matchesFilter(item: MediaItem): Boolean = when (filter) {
        MediaFilter.ALL -> true
        MediaFilter.IMAGES -> item.kind == MediaKind.IMAGE
        MediaFilter.VIDEOS -> item.kind == MediaKind.VIDEO
    }

    fun toggle(item: MediaItem) {
        selected = if (item.docId in selected) selected - item.docId else selected + item.docId
    }

    fun selectAll() {
        selected = scan?.items.orEmpty().map { it.docId }.toSet()
    }

    fun selectNone() {
        selected = emptySet()
    }

    fun selectionBytes(): Long = scan?.items.orEmpty()
        .filter { it.docId in selected }
        .sumOf { it.sizeBytes }

    /**
     * Ergebnis eines Scans. Die Auswahl von vorher bleibt erhalten, soweit
     * es die Dateien noch gibt: nach einem Neuladen (Drehung, Neu scannen)
     * waere "alles wieder an" eine stille Ruecknahme der Auswahl.
     */
    fun settleScan(result: ScanResult) {
        scanning = false
        scanLive = ""
        scan = result
        val all = result.items.map { it.docId }.toSet()
        val kept = selected.intersect(all)
        selected = if (kept.isEmpty()) all else kept
        afterBytes.clear()
    }

    fun failScan(message: String) {
        scanning = false
        scan = null
        scanLive = ""
        scanError = message
        selected = emptySet()
        afterBytes.clear()
    }

    companion object {
        /**
         * Ordner, Auswahl, Filter, Suche und die aufgeklappte Preset-Leiste
         * in rememberSaveable - Preset selbst liegt in den Preferences.
         */
        fun saver(
            initial: CompressSettings,
            persist: (CompressSettings) -> Unit
        ) = listSaver<HomeState, Any>(
            save = { state ->
                listOf(
                    state.folder?.toString().orEmpty(),
                    state.query,
                    state.filter.name,
                    ArrayList(state.selected),
                    state.presetOpen
                )
            },
            restore = { values ->
                HomeState(initial, persist).apply {
                    folder = (values[0] as String).takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                    query = values[1] as String
                    // Ein gesicherter Zustand darf die App nicht zum Absturz
                    // bringen, wenn sich ein Name spaeter aendert.
                    filter = runCatching { MediaFilter.valueOf(values[2] as String) }
                        .getOrDefault(MediaFilter.ALL)
                    selected = (values[3] as? List<*>)?.filterIsInstance<String>()?.toSet()
                        ?: emptySet()
                    presetOpen = values[4] as Boolean
                }
            }
        )
    }
}

/** Hinweise eines Scans, die eine kuerzere Liste erklaeren. Leer = nichts zu sagen. */
internal fun scanNotes(scan: ScanResult): String = buildList {
    if (scan.truncated) add("GRENZE ERREICHT")
    if (scan.unreadableFolders > 0) add("${scan.unreadableFolders} ORDNER UNLESBAR")
    if (scan.skippedTagged > 0) add("${scan.skippedTagged} BEREITS KOMPRIMIERT")
}.joinToString(" · ")

/** Marker, deren Zustand sich nicht lesen liess - mit dem Grund, einmal statt je Datei. */
internal fun scanMarkerNote(scan: ScanResult): String =
    "${scan.markersUnreadable} MARKER NICHT LESBAR" +
        if (scan.markerReason.isNotBlank()) " · ${scan.markerReason}" else ""

/** Zustand des Laufs in der Aktionsleiste; ein gescheiterter Lauf heisst nicht "fertig". */
internal fun runStatusLabel(
    running: Boolean,
    cancelled: Boolean,
    done: Int,
    total: Int,
    failed: Int
): String = when {
    running -> "LÄUFT · $done / $total"
    cancelled -> "ABGEBROCHEN · $done / $total"
    done > 0 && failed == done -> "FEHLGESCHLAGEN · $done / $total"
    else -> "FERTIG · $done / $total"
}

/** Beschriftung des einen Knopfes: Zustand vor Absicht. */
internal fun startControlLabel(
    running: Boolean,
    pending: Boolean,
    cancelling: Boolean,
    ready: Boolean
): String = when {
    running && cancelling -> "Bricht ab …"
    running -> "Stopp"
    pending -> "Startet …"
    ready -> "Start"
    else -> "Keine Auswahl"
}
