package com.steimerbyte.bulkcompressor.scan

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.steimerbyte.bulkcompressor.engine.Naming
import com.steimerbyte.bulkcompressor.engine.tag.MarkerProbe
import com.steimerbyte.bulkcompressor.engine.tag.MarkerReading
import com.steimerbyte.bulkcompressor.engine.tag.MarkerState
import com.steimerbyte.bulkcompressor.engine.tag.TagStore
import com.steimerbyte.bulkcompressor.log.Log
import com.steimerbyte.bulkcompressor.model.MediaItem
import com.steimerbyte.bulkcompressor.model.MediaKind
import com.steimerbyte.bulkcompressor.model.ScanResult
import java.util.Locale

/**
 * Durchsucht den vom Benutzer gewaehlten SAF-Ordner.
 *
 * Pro Ordner genau eine Cursor-Abfrage, nicht eine pro Datei - auf
 * langsamen Geraeten mit tausenden Bildern ist das der Unterschied
 * zwischen Sekunden und Minuten. Es wird nichts dekodiert und kein
 * Vorschaubild geladen, der Speicherbedarf bleibt bei wenigen kB.
 *
 * "Schon komprimiert" beantwortet einzig [probe] - derselbe Leser, den der
 * Job vor jeder Datei fragt. Der Scan zieht daraus genau eine Folgerung:
 * eine Datei mit Marker kommt nicht auf die Liste. Alles andere ist eine
 * Anzeigeentscheidung, keine Einordnung. Ein unlesbarer Marker heisst
 * "unbekannt": die Datei bleibt sichtbar, weil ein Lauf sie nur
 * ueberspringen wuerde, wenn der Marker lesbar waere - sie stillschweigend
 * zu verstecken, waere die Behauptung "schon erledigt", die niemand belegen
 * kann.
 */
class SafScanner(
    private val resolver: ContentResolver,
    /**
     * Einzige Quelle fuer "schon bearbeitet". Zwei Leser waeren zwei
     * Wahrheiten: der Job ueberspringt, was der Scan anzeigt, und der
     * Benutzer sieht die Differenz als Fehler. Als Schnittstelle
     * hereingereicht, weil der Marker in der Datei steht - ein Test
     * beantwortet ihn so, ohne einen SAF-Anbieter mit lesbarem Deskriptor
     * nachzubauen.
     */
    private val probe: MarkerProbe = TagStore(resolver)
) {

    /** Kind-Abrufe, die dieser Lauf fuer die Ordnererkennung ausgegeben hat. */
    private var probes = 0

    /**
     * Fortschritt fuer die Oberflaeche.
     *
     * [live] ist **eine** Zeile: Zaehler vorn, aktuelles Ereignis hinten.
     * Sie ist der einzige Ort, an dem der Benutzer waehrend des Laufs
     * sieht, dass Dateien wegen eines Markers fehlen oder dass ein Marker
     * nicht lesbar war - Zaehler ohne Text waeren eine Zahl ohne Bedeutung.
     */
    data class Progress(val folders: Int, val files: Int, val live: String = "")

    /**
     * @param respectTag Bereits komprimierte Dateien (Marker in den
     *                   Metadaten) gar nicht erst auf die Liste setzen.
     *                   Das ist der Normalfall: wer denselben Ordner ein
     *                   zweites Mal waehlt, will die neuen Bilder, nicht
     *                   noch einmal alles. Kostet je Datei einen Blick in
     *                   den Kopf (und bei EXIF zusaetzlich einen
     *                   Deskriptor) - deshalb abschaltbar, und deshalb
     *                   steht die Zahl im Protokoll.
     * @param cancelled Wird vor jedem Kindeintrag und vor jeder
     *                  Marker-Abfrage gefragt. Waehlt der Benutzer
     *                  waehrend eines laufenden Scans einen anderen Ordner,
     *                  muss der alte Lauf aufhoeren - sonst ueberschreibt
     *                  sein Ergebnis die Anzeige des neuen.
     * @param onProgress Fortschritt, auch mitten im Ordner: ein DCIM-
     *                  Ordner mit tausenden Bildern liefert sonst minutenlang
     *                  gar keine Meldung und die Oberflaeche steht still.
     */
    fun scan(
        treeUri: Uri,
        respectTag: Boolean = true,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): ScanResult {
        val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
        val rootId = root.getOrNull()
        if (rootId == null) {
            Log.w("Scan", "Wurzelordner nicht lesbar: $treeUri", root.exceptionOrNull())
            return ScanResult(
                items = emptyList(),
                folderCount = 0,
                truncated = false,
                skipped = 0,
                skippedTagged = 0,
                unreadableFolders = 0,
                markersUnreadable = 0,
                markerReason = "",
                videoCount = 0,
                imageCount = 0
            )
        }
        Log.i(
            "Scan",
            "Start: $treeUri" + if (respectTag) "" else " · Marker werden nicht beachtet, alle Dateien auf die Liste"
        )

        val items = ArrayList<MediaItem>(256)
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        val visited = HashSet<String>()
        val folderNames = HashMap<String, String>()
        folderNames[rootId] = readableFolderName(rootId)
        val startedAt = System.currentTimeMillis()
        var folders = 0
        var skipped = 0
        var skippedNoType = 0
        var skippedTagged = 0
        // Teilmenge von skippedTagged: Marker steht in der Kopie, nicht in
        // der Datei. Kein eigenes Feld im ScanResult - der Benutzer sieht
        // "bereits erledigt" - aber Log und Live-Zeile nennen den Grund.
        var copiesSkipped = 0
        var markersUnreadable = 0
        var skippedZeroBytes = 0
        var entriesWithoutMime = 0
        var unreadableFolders = 0
        var foldersOverLimit = 0
        var probedFolders = 0
        var firstUnreadableName: String? = null
        var firstUnreadableReason = ""
        // Je verschiedenem Grund genau eine Logzeile. Bei einem Anbieter
        // ohne seekbaren Deskriptor scheitert jede Datei aus demselben
        // Grund - tausend identische Zeilen waeren kein Informationsgewinn,
        // sondern der Grund, warum das Log beim naechsten Mal ignoriert
        // wird. Gedeckelt, weil ein Anbieter auch je Datei einen eigenen
        // Text liefern kann.
        val seenReasons = HashSet<String>()
        var suppressedReasons = 0
        probes = 0
        var truncated = false
        var cancelledRun = false
        var sinceProgress = 0
        var lastProgressAt = startedAt
        // Gefundene Dateien, nicht gelistete: waehrend Ordner gelesen und
        // Marker geprueft werden, steht noch nichts auf der Liste. Eine
        // Oberflaeche, die dort "0 Dateien" zeigt, sieht aus, als taete die
        // App nichts - genau das war der Fehler.
        var seenFiles = 0
        // Die zuletzt gemeldete Phase. Ein Wechsel ("liest" -> "prüft
        // 213/517") wird immer sofort geschickt: sonst schluckt die
        // Drosselung genau den Moment, in dem der Benutzer merkt, dass
        // der Scan weitergeht.
        var lastStage = ""

        /**
         * Meldet das eben behandelte Ereignis - aber gedrosselt.
         *
         * Ein DCIM-Ordner mit 5000 Bildern wuerde sonst 5000 Meldungen auf
         * den Main-Thread schicken; die Liste flackert und der UI-Thread
         * kommt nicht mehr zum Zeichnen. Deshalb erst nach
         * [PROGRESS_FILES] Dateien **oder** [PROGRESS_INTERVAL_MS]
         * Laufzeit: 8 Dateien sind bei einem Bild pro ~10 ms unter der
         * Wahrnehmungsschwelle, und die Zeitgrenze faengt den langsamen
         * Anbieter ab, bei dem eine Marker-Abfrage allein 20 ms kostet und
         * die Dateigrenze erst nach Sekunden erreicht waere.
         */
        fun report(
            folderLabel: String,
            name: String,
            note: String,
            stage: String = "",
            phase: String = stage,
            force: Boolean = false
        ) {
            sinceProgress++
            val now = System.currentTimeMillis()
            // Die Stufe, nicht der Text: "prüft 213/517" aendert sich mit
            // jeder Datei und wuerde die Drosselung aushebeln. Ein Wechsel
            // der Stufe ("liest" -> "prüft") dagegen wird sofort
            // geschickt - sonst schluckt die Drosselung genau den Moment,
            // in dem der Benutzer merkt, dass der Scan weitergeht.
            val stageChanged = stage != lastStage
            if (!force && !stageChanged && sinceProgress < PROGRESS_FILES && now - lastProgressAt < PROGRESS_INTERVAL_MS) return
            sinceProgress = 0
            lastProgressAt = now
            lastStage = stage
            onProgress(
                Progress(
                    folders = folders,
                    files = seenFiles,
                    live = liveLine(
                        files = seenFiles,
                        tagged = skippedTagged,
                        copies = copiesSkipped,
                        unreadable = markersUnreadable,
                        phase = phase,
                        event = tail(folderLabel, name, note)
                    )
                )
            )
        }

        // Sofort eine Zeile, nicht erst nach dem ersten Ordner: der Start
        // ist der Moment, in dem der Benutzer sonst auf einen toten
        // Bildschirm schaut.
        report("", "", "", stage = "start", phase = "liest Ordner", force = true)

        while (queue.isNotEmpty()) {
            if (folders >= MAX_FOLDERS || items.size >= MAX_FILES) {
                truncated = true
                break
            }
            if (cancelled()) {
                cancelledRun = true
                break
            }
            val folderId = queue.removeFirst()
            if (!visited.add(folderId)) continue
            folders++
            val folderLabel = folderNames[folderId] ?: folderId
            val folderStartedAt = System.currentTimeMillis()
            var folderVideos = 0
            var folderImages = 0
            var folderBytes = 0L
            var folderNoType = 0
            var folderNoMime = 0
            var folderTagged = 0
            var folderCopies = 0
            var folderUnreadable = 0
            var folderZeroBytes = 0

            val children = queryChildren(treeUri, folderId) { unreadableFolders++ }
            // Eine Zeile je Ordner, sobald seine Eintraege da sind. Ohne
            // sie schweigt das Protokoll bei einem Ordner mit tausenden
            // Bildern, bis die Marker aller Dateien gelesen sind.
            Log.i("Scan", "Ordner $folders: $folderLabel · ${children.size} Eintraege")
            report(folderLabel, "", "", stage = "liest", force = true)

            // Durchgang 1: Ordner einreihen, Dateien sammeln. Getrennt vom
            // Marker-Lesen, weil der Geschwister-Abgleich die Einordnung
            // des ganzen Ordners braucht - die Kopie kann im Cursor hinter
            // ihrem Original stehen.
            val candidates = ArrayList<Candidate>(children.size)
            for (child in children) {
                val directory = child.isDirectory ||
                    isUnmarkedDirectory(treeUri, child).also { if (it) probedFolders++ }
                if (directory) {
                    folderNames[child.documentId] = child.name
                    if (queue.size + visited.size < MAX_FOLDERS) {
                        queue.add(child.documentId)
                    } else {
                        foldersOverLimit++
                    }
                } else {
                    if (child.mime.isBlank()) folderNoMime++
                    val extension = Naming.extensionOf(child.name)
                    val kind = when {
                        Naming.isVideo(extension) -> MediaKind.VIDEO
                        Naming.isImage(extension) -> MediaKind.IMAGE
                        else -> null
                    }
                    if (kind == null) {
                        skipped++
                        skippedNoType++
                        folderNoType++
                        continue
                    }
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                    candidates.add(Candidate(child, documentUri, kind))
                    seenFiles++
                    // Der Name gehoert in die Zeile: eine Zahl, die
                    // hochzaehlt, zeigt, dass etwas passiert; ein Name
                    // zeigt, was.
                    report(folderLabel, child.name, "", stage = "liest")
                }
            }

            // Durchgang 2: den Marker jeder Datei lesen. Vor dem Listen,
            // weil "Original behalten" eine Aussage ueber den ganzen Ordner
            // ist, und einmal je Datei, weil jede Antwort gebraucht wird.
            val readings: HashMap<String, MarkerReading>? =
                if (respectTag) HashMap(candidates.size * 2) else null
            if (readings != null) {
                var read = 0
                val total = candidates.size
                for (candidate in candidates) {
                    if (cancelled()) {
                        cancelledRun = true
                        break
                    }
                    readings[candidate.child.documentId] = readMarker(candidate.uri)
                    read++
                    // Das ist der langsame Teil: je Datei ein Blick in den
                    // Kopf, bei EXIF zusaetzlich ein Deskriptor. Ohne
                    // Meldung sah die App hier Minuten lang aus, als taete
                    // sie nichts.
                    report(
                        folderLabel,
                        candidate.child.name,
                        "",
                        stage = "prüft",
                        phase = "prüft $read/$total"
                    )
                    if (read % MARKER_LOG_STEP == 0) {
                        Log.i("Scan", "Marker $read/$total in $folderLabel")
                    }
                }
            }
            if (cancelledRun) break

            // Gegenprobe fuer "Original behalten": das Original wird nie
            // angefasst und traegt deshalb nie einen Marker - erledigt ist es
            // trotzdem, sobald die Kopie daneben liegt und MARKED ist.
            //
            // Zwei Karten, beide aus diesem einen Cursor gebaut, also ohne
            // Zusatz-Abfrage - und beide braucht der Abgleich, weil der Job
            // den Namen an zwei Stellen anders bildet als `compressedName`
            // es vorhersagt:
            //  - Formatwechsel: `deliver(..., extension, ...)` uebergibt die
            //    neue Endung, aus `IMG_1.heic` wird `IMG_1_compressed.jpg`,
            //  - Namenskollision: `uniqueName` haengt `_2` an.
            // [copiesByKey] sammelt die Kopien je Quellstamm und Medienart,
            // [candidatesByStem] zaehlt die Dateien je Stamm, die als Quelle
            // in Frage kommen. Die
            // Medienart gehoert in beide Schluessel, damit ein Vorschaubild
            // nicht das Video gleichen Stamms als erledigt abraeumt.
            val copiesByKey: Map<CopyKey, List<String>> = readings?.let { states ->
                val map = HashMap<CopyKey, MutableList<String>>()
                for (candidate in candidates) {
                    if (states[candidate.child.documentId]?.state != MarkerState.MARKED) continue
                    val stem = Naming.copySourceStem(candidate.child.name) ?: continue
                    map.getOrPut(CopyKey(candidate.kind, stem)) { ArrayList(1) }
                        .add(candidate.child.name)
                }
                map
            } ?: emptyMap()
            // Alles, was **nicht** als erledigt gilt, kommt als Quelle in
            // Frage - auch ein unlesbarer Marker. Die Karte entscheidet nur
            // darueber, ob ein Stamm eindeutig ist; zaehlte sie UNREADABLE
            // nicht mit, wuerde ein unbekannter Nachbar stillschweigend
            // zum Beweis, dass die Kopie von der lesbaren Datei stammt.
            val candidatesByStem: Map<CopyKey, Int> = readings?.let { states ->
                val map = HashMap<CopyKey, Int>()
                for (candidate in candidates) {
                    if (states[candidate.child.documentId]?.state == MarkerState.MARKED) continue
                    val key = CopyKey(candidate.kind, Naming.baseNameOf(candidate.child.name))
                    map[key] = (map[key] ?: 0) + 1
                }
                map
            } ?: emptyMap()

            // Durchgang 3: einordnen, zaehlen, listen.
            for (candidate in candidates) {
                val child = candidate.child
                val reading = readings?.get(child.documentId)
                var note = ""
                when (reading?.state) {
                    MarkerState.MARKED -> {
                        skippedTagged++
                        folderTagged++
                        report(folderLabel, child.name, TAGGED_NOTE)
                        continue
                    }
                    MarkerState.UNREADABLE -> {
                        // Unbekannt ist nicht "komprimiert" und auch nicht
                        // "frei": die Datei bleibt auf der Liste, damit der
                        // Benutzer entscheiden kann. Der Grund wird gezaehlt
                        // und mit der ersten solchen Datei gewarnt - eine
                        // Zeile je Datei waere bei einem Anbieter ohne
                        // seekbaren Deskriptor ein Logsturm ueber tausende
                        // Bilder mit immer demselben Text.
                        val reason = reading?.reason.orEmpty()
                        markersUnreadable++
                        folderUnreadable++
                        if (firstUnreadableName == null) {
                            firstUnreadableName = child.name
                            firstUnreadableReason = reason
                        }
                        if (seenReasons.add(reason)) {
                            if (seenReasons.size <= MAX_REASON_LOGS) {
                                Log.w(
                                    "Scan",
                                    "Marker nicht lesbar: ${child.name} · ${oneLine(reason)}" +
                                        " - Datei bleibt auf der Liste (Zustand unbekannt, nicht 'komprimiert')"
                                )
                            } else {
                                // Neue Gruende nach dem Deckel: nur noch
                                // zaehlen. Das Log nennt am Ende die Summe,
                                // also geht nichts verloren - aber ein
                                // Anbieter mit einem eigenen Text je Datei
                                // flutet das Log nicht.
                                suppressedReasons++
                            }
                        }
                        note = "$UNREADABLE_NOTE: ${oneLine(reason).take(MAX_REASON_CHARS)}"
                    }
                    MarkerState.CLEAR, null -> {
                        // "Original behalten": das Original ist unangetastet
                        // und traegt nie einen Marker. Erledigt ist es erst,
                        // wenn im **selben** Ordner eine Kopie liegt, deren
                        // Name dem Schreibnamen des Jobs entspricht (Stamm
                        // gleich, Endung gleich oder - bei beweisbarem
                        // Formatwechsel - verschieden), und die **einen
                        // Marker hat**. Die Marker-Bedingung ist der
                        // Unterschied zwischen "fertig" und "abgebrochen":
                        // ein Lauf legt die Kopie erst nach dem Marker an,
                        // eine markerlose Kopie ist also ein Rest und kein
                        // Beweis.
                        //
                        // Ohne Marker-Lesen (`readings == null`) gibt es
                        // weder Kopien noch Originale zu vergleichen; der
                        // Abgleich wird gar nicht erst begonnen.
                        if (readings != null) {
                            val key = CopyKey(candidate.kind, Naming.baseNameOf(child.name))
                            val copies = copiesByKey[key]
                            // Zwei Wege, und der erste ist der Vertragsweg:
                            //
                            // 1. Genau der Soll-Name. Das ist der Beweis,
                            //    den `compressedName` selbst liefert - er
                            //    kann nur zu dieser einen Quelldatei
                            //    gehoeren.
                            // 2. Sonst: der Stamm ist unter den freien
                            //    Originalen dieser Medienart eindeutig.
                            //    Dann kann jede Kopie mit diesem Stamm nur
                            //    von dieser Datei stammen, auch wenn
                            //    `uniqueName` ein `_2` angehaengt oder der
                            //    Job die Endung gewechselt hat
                            //    (HEIC -> JPEG).
                            //
                            // Der zweite Weg ist bewusst **nicht** nur auf
                            // gleiche Endung beschraenkt: liegen `a.jpg`
                            // und `a.png` nebeneinander, ist
                            // `a_compressed_2.jpg` nicht zuzuordnen - dann
                            // bleibt die PNG stehen. Ein falsches
                            // "erledigt" versteckt eine Datei fuer immer;
                            // ein falsches "offen" zeigt sie einmal zu
                            // viel.
                            val exact = copies?.contains(Naming.compressedName(child.name)) == true
                            val uniqueStem = copies != null && candidatesByStem[key] == 1
                            if (exact || uniqueStem) {
                                // Zaehlt in skippedTagged: fuer den Benutzer
                                // ist die Datei erledigt, egal wo der Marker
                                // steht.
                                skippedTagged++
                                copiesSkipped++
                                folderCopies++
                                report(folderLabel, child.name, COPY_NOTE)
                                continue
                            }
                        }
                    }
                }
                // Eine fehlende Groesse im Kind-Cursor ist kein Grund,
                // die Datei zu verschweigen: manche Provider fuellen
                // COLUMN_SIZE nicht, und dann waere ein ganzer Ordner
                // unsichtbar. Sie zaehlt als "Groesse unbekannt" (0) und
                // wird komprimiert wie jede andere.
                if (child.size <= 0) {
                    skippedZeroBytes++
                    folderZeroBytes++
                }
                if (items.size >= MAX_FILES) {
                    truncated = true
                    break
                }
                items.add(
                    MediaItem(
                        uri = candidate.uri,
                        docId = child.documentId,
                        parentId = folderId,
                        displayName = child.name,
                        mime = child.mime,
                        sizeBytes = child.size.coerceAtLeast(0L),
                        lastModified = child.lastModified,
                        kind = candidate.kind
                    )
                )
                if (candidate.kind == MediaKind.VIDEO) folderVideos++ else folderImages++
                folderBytes += child.size
                report(folderLabel, child.name, note)
            }

            entriesWithoutMime += folderNoMime
            Log.i(
                "Scan",
                "Ordner $folders: $folderLabel · $folderVideos Videos" +
                    " · $folderImages Bilder · ${folderBytes} B (${mb(folderBytes)} MB)" +
                    " · $folderNoType uebersprungen ohne Medien-Typ" +
                    (if (folderTagged > 0) " · $folderTagged bereits komprimiert" else "") +
                    (if (folderCopies > 0) " · $folderCopies mit vorhandener Kopie" else "") +
                    (if (folderUnreadable > 0) " · $folderUnreadable Marker nicht lesbar (gelistet)" else "") +
                    " · $folderZeroBytes ohne Groessenangabe" +
                    " · $folderNoMime Eintraege ohne MIME" +
                    " · ${System.currentTimeMillis() - folderStartedAt} ms"
            )
            // Ordnergrenze ist die natuerliche Meldung: sie kommt auch,
            // wenn in diesem Ordner nur uebersprungene Dateien lagen - sonst
            // stuende die Zeile bei einem Ordner voller Marker still.
            sinceProgress = 0
            lastProgressAt = System.currentTimeMillis()
            onProgress(
                Progress(
                    folders = folders,
                    files = seenFiles,
                    live = liveLine(
                        files = seenFiles,
                        tagged = skippedTagged,
                        copies = copiesSkipped,
                        unreadable = markersUnreadable,
                        event = tail(folderLabel, "", "")
                    )
                )
            )
        }

        val elapsed = System.currentTimeMillis() - startedAt
        items.sortWith(compareBy({ it.kind }, { it.displayName.lowercase() }))
        val videos = items.count { it.kind == MediaKind.VIDEO }
        val bytes = items.sumOf { it.sizeBytes }
        val truncNote = if (truncated) " · durch Grenze abgeschnitten" else ""
        val cancelNote = if (cancelledRun) " · abgebrochen" else ""
        Log.i(
            "Scan",
            "Fertig: $folders Ordner · $videos Videos · ${items.size - videos} Bilder · $bytes B" +
                " (${mb(bytes)} MB)" +
                " · $skipped uebersprungen" +
                " (davon $skippedNoType ohne Medien-Typ)," +
                " $skippedTagged bereits komprimiert (Marker oder vorhandene Kopie," +
                " davon $copiesSkipped Kopie)," +
                " $markersUnreadable Marker nicht lesbar (bleiben auf der Liste)," +
                " $skippedZeroBytes ohne Groessenangabe (trotzdem gelistet)," +
                " $entriesWithoutMime ohne MIME)" +
                " · $unreadableFolders Ordner nicht lesbar" +
                (if (suppressedReasons > 0) " · $suppressedReasons weitere Lesefehler-Gruende im Log unterdrueckt" else "") +
                (if (probedFolders > 0) " · $probedFolders Ordner ohne Verzeichnis-Typ am Kind-Abruf erkannt" else "") +
                " · $foldersOverLimit Unterordner wegen der Ordnergrenze nicht gelesen" +
                " · $elapsed ms Laufzeit" +
                truncNote +
                cancelNote
        )
        if (truncated) {
            Log.w(
                "Scan",
                "Grenze erreicht: MAX_FILES=$MAX_FILES / MAX_FOLDERS=$MAX_FOLDERS - Rest nicht gelesen"
            )
        }
        // Kein einziger lesbarer Marker, aber Dateien auf der Liste: der
        // Marker-Leser funktioniert fuer diesen Anbieter nicht. Das muss
        // dastehen, sonst sieht "wieder alles da" wie ein kaputter Filter
        // aus - und der naechste Lauf zeigt dieselben Dateien noch einmal.
        if (respectTag && items.isNotEmpty() && markersUnreadable == items.size) {
            val beispiel = firstUnreadableName?.let {
                " (${it}: ${oneLine(firstUnreadableReason).take(MAX_REASON_CHARS)})"
            } ?: ""
            Log.w(
                "Scan",
                "Marker fuer diesen Anbieter nicht lesbar: alle ${items.size} gelisteten Dateien" +
                    " liefern UNREADABLE$beispiel" +
                    " - die Liste enthaelt moeglicherweise bereits komprimierte Dateien"
            )
        }
        return ScanResult(
            items = items,
            folderCount = folders,
            truncated = truncated,
            skipped = skipped,
            skippedTagged = skippedTagged,
            unreadableFolders = unreadableFolders,
            markersUnreadable = markersUnreadable,
            markerReason = firstUnreadableReason,
            videoCount = videos,
            imageCount = items.size - videos
        )
    }

    /**
     * Fragt den Marker ueber den Vertrag. Ein werfender Leser ist genau der
     * Fall, den [MarkerProbe] ausschliesst - aber ein Scan darf daran nicht
     * sterben, und "unbekannt mit Grund" ist die einzige ehrliche Antwort,
     * die dann bleibt.
     */
    private fun readMarker(uri: Uri): MarkerReading {
        val reading = runCatching { probe.probe(uri) }.getOrElse {
            MarkerReading(
                state = MarkerState.UNREADABLE,
                reason = it.message ?: it.javaClass.simpleName
            )
        }
        return if (reading.state == MarkerState.UNREADABLE && reading.reason.isBlank()) {
            reading.copy(reason = UNKNOWN_REASON)
        } else {
            reading
        }
    }

    /**
     * Eine Zeile fuer die Oberflaeche: Zaehler vorn, aktuelles Ereignis
     * hinten, hart auf [MAX_LIVE_CHARS] begrenzt.
     *
     * Gekuerzt wird nur hinten. Die Zaehler sind die Aussage - an ihnen
     * erkennt der Benutzer, dass Dateien wegen eines Markers fehlen -, das
     * Ereignis ist der Beleg, dass gerade etwas passiert.
     */
    private fun liveLine(
        files: Int,
        tagged: Int,
        copies: Int,
        unreadable: Int,
        phase: String = "",
        event: String
    ): String {
        val head = StringBuilder()
        head.append(files).append(if (files == 1) " Datei" else " Dateien")
        // Die Phase steht direkt hinter der Zahl und vor den Zaehlern: sie
        // sagt, *was* gerade laeuft ("liest", "prüft 213/517"), die
        // Zaehler sagen, was dabei herauskam.
        if (phase.isNotBlank()) {
            val text = oneLine(phase)
            if (head.length + SEPARATOR.length + text.length <= MAX_LIVE_CHARS) {
                head.append(SEPARATOR).append(text)
            }
        }
        // Reihenfolge nach Aussage, nicht nach Zufall: die Trefferzahl
        // steht vorn (wie in der Beispielzeile des Vertrags), danach die
        // Lesefehler - Invariante 2 verlangt sie sichtbar - und zuletzt die
        // Kopien. Die sind eine Teilmenge der Treffer und damit die einzige
        // Zahl, die eine Kuerzung kosten darf.
        appendCounter(head, tagged, "bereits komprimiert")
        appendCounter(head, unreadable, "Marker unlesbar")
        appendCounter(head, copies, "Kopie vorhanden", "Kopien vorhanden")
        val text = oneLine(event)
        if (text.isBlank()) return head.toString()
        val room = MAX_LIVE_CHARS - head.length - SEPARATOR.length
        if (room < MIN_TAIL_CHARS) return head.toString()
        head.append(SEPARATOR)
        if (text.length <= room) {
            head.append(text)
        } else {
            head.append(text.take(room - 1).trimEnd()).append(TAIL_CUT)
        }
        return head.toString()
    }

    /**
     * Haengt einen Zaehler an, solange er ganz in die Zeile passt.
     *
     * Ein abgeschnittener Zaehler waere schlimmer als ein fehlender: "3 be"
     * liest niemand als Aussage. Die Zahlen sind so gewaehlt, dass im
     * Normalfall alle drei passen - bei [MAX_FILES] Eintraegen bleibt die
     * Zeile trotzdem unter [MAX_LIVE_CHARS].
     */
    private fun appendCounter(
        line: StringBuilder,
        value: Int,
        one: String,
        many: String = one
    ) {
        if (value <= 0) return
        val text = SEPARATOR + value + ' ' + if (value == 1) one else many
        if (line.length + text.length > MAX_LIVE_CHARS) return
        line.append(text)
    }

    /** "Camera/IMG_0421.jpg · Kopie vorhanden" - Ordner, Datei, Ereignis. */
    private fun tail(folder: String, name: String, note: String): String {
        val path = when {
            name.isBlank() -> folder
            folder.isBlank() -> name
            else -> "$folder/$name"
        }
        return if (note.isBlank()) path else "$path$SEPARATOR$note"
    }

    /**
     * Alles auf eine Zeile.
     *
     * Der Grund eines Lesefehlers kommt aus einer Fremdbibliothek und kann
     * Zeilenumbrueche enthalten. Die Live-Zeile steht in einer einzigen
     * Textzeile der App - ein Umbruch darin wuerde das Layout zerlegen,
     * und genau das ist die Zusage `live` ist **eine** Zeile.
     */
    private fun oneLine(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    /** Bytes als MB-Text mit einer Nachkommastelle, deutsch geschrieben. */
    private fun mb(bytes: Long): String =
        String.format(Locale.GERMANY, "%.1f", bytes / 1024.0 / 1024.0)

    /**
     * Lesbarer Name des Wurzelordners. Der Dokument-ID fehlt der Anzeigename,
     * deshalb bleibt es beim Pfadanteil hinter dem Doppelpunkt.
     */
    private fun readableFolderName(documentId: String): String {
        val path = documentId.substringAfter(':', documentId)
        return path.ifBlank { documentId }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val lastModified: Long,
        val isDirectory: Boolean
    )

    /** Eine Datei des Ordners samt fertig gebauter URI - einmal je Eintrag. */
    private class Candidate(
        val child: Child,
        val uri: Uri,
        val kind: MediaKind
    )

    /**
     * Eine vorhandene Kopie, nach Quelle und Medienart.
     *
     * Die Medienart gehoert in den Schluessel, nicht nur der Stamm: eine
     * Kamera legt zu jedem Video ein Vorschaubild gleichen Stamms ab
     * (`VID_1.mp4` neben `VID_1.jpg`). Ohne sie wuerde das Vorschaubild
     * als "Kopie von VID_1.mp4 vorhanden" gelten - und das Video
     * verschwaende von der Liste, obwohl nur sein Standbild erledigt ist.
     */
    private data class CopyKey(val kind: MediaKind, val stem: String)

    /**
     * Ordner, die der Anbieter nicht als Ordner kennzeichnet.
     *
     * Die Welt ist nicht nur der AOSP-Speicheranbieter: manche Dateimanager
     * melden Ordner als `inode/directory`, `resource/folder` oder ohne
     * jeden Typ. Wurde so ein Eintrag als Datei behandelt, fiel er durch die
     * Endungspruefung, galt als "ohne Medien-Typ" - und der Scan blieb auf
     * der obersten Ebene stehen, obwohl der Baum tiefer reicht.
     *
     * Entscheidend ist deshalb der Kind-Abruf: liefert der Anbieter fuer
     * diese Dokument-ID Eintraege, ist es ein Ordner. Das kostet eine
     * Abfrage - aber nur fuer Eintraege, die weder einen echten Typ noch
     * eine Groesse haben, also weder als Datei noch als Ordner erkennbar
     * sind. Danach greift eine Ersatzregel, damit ein Anbieter, der auf
     * jeden Abruf antwortet, den Lauf nicht ausbremst.
     */
    private fun isUnmarkedDirectory(treeUri: Uri, child: Child): Boolean {
        // Ein Typ, der weder Verzeichnis noch "unbekannt" ist, spricht fuer
        // eine Datei; eine gemeldete Groesse ebenfalls.
        if (child.size > 0) return false
        val unknown = child.mime.isBlank() ||
            child.mime == UNKNOWN_MIME ||
            child.mime == BINARY_MIME
        if (!unknown) return false
        if (probes >= MAX_PROBES) {
            // Notbremse: Ordnernamen tragen in der Regel keine Endung.
            return Naming.extensionOf(child.name).isEmpty()
        }
        probes++
        return hasChildren(treeUri, child.documentId)
    }

    /** Fragt einen Kind-Cursor ab, ohne Fehler zu protokollieren. */
    private fun hasChildren(treeUri: Uri, documentId: String): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            cursor != null && cursor.count > 0
        } catch (t: Throwable) {
            false
        } finally {
            cursor?.close()
        }
    }

    private fun queryChildren(
        treeUri: Uri,
        folderId: String,
        onUnreadable: () -> Unit = {}
    ): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = ArrayList<Child>(32)

        var cursor: Cursor? = null
        try {
            val query = resolver.query(childrenUri, projection, null, null, null)
            if (query == null) {
                onUnreadable()
                Log.w("Scan", "Ordner nicht lesbar (kein Cursor): $folderId")
                return result
            }
            cursor = query
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIndex < 0 || nameIndex < 0) {
                onUnreadable()
                return result
            }

            var rowSkipped = 0
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                if (id == null || name == null) {
                    rowSkipped++
                    continue
                }
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) ?: "" else ""
                result.add(
                    Child(
                        documentId = id,
                        name = name,
                        mime = mime,
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                        lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            cursor.getLong(modifiedIndex)
                        } else 0L,
                        isDirectory = isDirectoryMime(mime)
                    )
                )
            }
            if (rowSkipped > 0) {
                Log.w(
                    "Scan",
                    "Ordner $folderId: $rowSkipped Eintraege ohne Dokument-ID oder Namen uebersprungen"
                )
            }
        } catch (t: Throwable) {
            // Ein nicht lesbarer Unterordner darf den ganzen Lauf nicht
            // verhindern (z. B. geloeschter Ordner oder fehlende Rechte).
            onUnreadable()
            Log.w("Scan", "Ordner nicht lesbar: $folderId", t)
        } finally {
            cursor?.close()
        }
        return result
    }

    companion object {
        /**
         * Obergrenzen. Sie sind keine Schutzmauer gegen Fehlbedienung,
         * sondern gegen einen Provider, der nie endet; ein Kamerafolger mit
         * fuenftausend Bildern muss vollstaendig erscheinen. Bei 20 000
         * Eintraegen liegt die Liste bei rund 7 MB - das ist die Grenze, ab
         * der ein Telefon mit 4 GB knapp wird.
         */
        const val MAX_FILES = 20_000
        const val MAX_FOLDERS = 2_000

        /**
         * So viele Dateien zwischen zwei Fortschrittsmeldungen, wenn die
         * Zeitgrenze nicht vorher greift.
         */
        private const val PROGRESS_FILES = 8

        /**
         * Und so lange hoechstens ohne Meldung. Die Dateigrenze allein
         * reicht nicht: ein Anbieter, bei dem eine Marker-Abfrage 20 ms
         * kostet, braucht fuer 8 Dateien 160 ms - bei tausenden Bildern
         * stuende die Zeile dann sekundenlang still, obwohl der Scan laeuft.
         */
        private const val PROGRESS_INTERVAL_MS = 150L

        /** Eine Zeile, mehr passen nicht in die Kopfzeile der Liste. */
        private const val MAX_LIVE_CHARS = 90

        /** Kuerzer als das ist kein Ereignis mehr, sondern nur ein Stummel. */
        private const val MIN_TAIL_CHARS = 12

        /** Zeichen fuer "hier wurde gekuerzt" - nur am Ende der Zeile. */
        private const val TAIL_CUT = '…'

        /** Jede Folge von Weissraum wird zu einem Leerzeichen. */
        private val WHITESPACE = Regex("\\s+")

        /** Trenner der Zeile; als Konstante, weil die Laenge ins Budget zaehlt. */
        private const val SEPARATOR = " · "

        /** Ein Grund ist ein Satz, kein Stacktrace - der steht im Log. */
        private const val MAX_REASON_CHARS = 60

        /**
         * So viele verschiedene Lesefehler-Gruende kommen ins Log. Der
         * Normalfall ist einer ("kein seekbarer Deskriptor", "EXIF nicht
         * lesbar"); mehr als ein paar Dutzend hiesse, der Anbieter liefert
         * je Datei einen eigenen Text - das ist ein Bericht in einer Zahl,
         * kein Log.
         */
        private const val MAX_REASON_LOGS = 8

        private const val TAGGED_NOTE = "bereits komprimiert"
        private const val COPY_NOTE = "Kopie vorhanden"
        private const val UNREADABLE_NOTE = "Marker unlesbar"
        private const val UNKNOWN_REASON = "Grund unbekannt"

        /**
         * MIME-Typen, mit denen Anbieter einen Ordner melden. Der
         * AOSP-Speicheranbieter nimmt `vnd.android.document/directory`;
         * fremde Anbieter (Dateimanager, Cloud) nehmen eigene Typen, und
         * ohne diese Liste fiel der Unterordner stillschweigend weg.
         */
        private val DIRECTORY_MIMES = setOf(
            DocumentsContract.Document.MIME_TYPE_DIR,
            "inode/directory",
            "resource/folder",
            "application/vnd.google-apps.folder",
            "application/x-directory",
            "text/directory"
        )

        internal fun isDirectoryMime(mime: String): Boolean {
            if (mime.isBlank()) return false
            return mime in DIRECTORY_MIMES ||
                mime.endsWith("/directory") ||
                mime.endsWith("/folder") ||
                mime.startsWith("application/vnd.google-apps.folder")
        }

        /**
         * So oft darf ein Scan einen Ordner per Kind-Abruf pruefen. Danach
         * entscheidet die Ersatzregel - ein Anbieter, der auf jede Anfrage
         * mit einem leeren Cursor antwortet, wuerde den Lauf sonst mit einer
         * Abfrage je Datei verlangsamen.
         */
        private const val MAX_PROBES = 2_000

        /** Protokollzeile je so vielen gelesenen Markern in einem Ordner. */
        private const val MARKER_LOG_STEP = 250

        private const val UNKNOWN_MIME = "application/unknown"
        private const val BINARY_MIME = "application/octet-stream"
    }
}
