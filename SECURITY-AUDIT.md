# Sicherheits-Audit — Bulk Compressor 1.8.0

Stand: 2026-11-01 · Repo `steimerbyte/bulkcompressor` @ `4c2e6c3` · Umfang: `app/src/main/AndroidManifest.xml`, alle 45 Kotlin-Dateien unter `app/src/main/java/**` (10.956 Zeilen), `app/build.gradle.kts`, `app/proguard-rules.pro`, `res/xml/*.xml`, `tools/`.

## Executive Summary

Die Angriffsfläche ist klein und bewusst so gebaut: keine `INTERNET`-Berechtigung
(`AndroidManifest.xml:6` „Kein Netzwerkzugriff“), keine WebView, kein `ProcessBuilder`/`exec`,
kein Dynamik-Code (`DexClassLoader`, `Class.forName`, `loadLibrary` — je 0 Treffer), keine
`ContentProvider`-eigene Authority, kein Zip-/Tar-Entpacken, keine Secrets im Repo
(`keystore.properties` ist ignoriert, `.gitignore:11`).

Der Klassenfehler liegt nicht in fehlender Härtung, sondern in der **Datensenke**: die App
protokolliert auf Debug-Niveau jede Datei mit vollem Pfad und jedes EXIF-Feld mit
**GPS-Koordinaten** — und schreibt dieses Protokoll automatisch, ohne Schalter, in den
**öffentlichen** Ordner `Download/BulkCompressor/` (`MEDIUM`-freie MediaStore-Senke,
`log/DownloadsLog.kt:243`). Dazu kommt `MANAGE_EXTERNAL_STORAGE`
(`AndroidManifest.xml:43`), das für eine einzige Funktion (`setLastModified`) die
SAF-Kapselung aufhebt; zusammen mit dem ungeprüften DocID→Pfad-Aufbau in
`engine/MetaRestore.kt:141-155` entsteht ein Schreibpfad am Dateisystem vorbei an der
Grant-Prüfung.

**Bilanz:** 2 × HIGH, 5 × MEDIUM, 5 × LOW, 5 × INFO (davon 4 bestätigte Negativ-Befunde).

## Befundtabelle

| Nr. | Kategorie | Schweregrad | Datei:Zeile | Befund (eine Zeile) |
|---|---|---|---|---|
| B1 | Daten-Exposition / Logging | **HIGH** | `engine/MetaRestore.kt:224` | GPS-Koordinaten und Aufnahmezeitpunkt jedes Bildes stehen im Klartext im Protokoll. |
| B2 | Daten-Exposition / Senke | **HIGH** | `log/DownloadsLog.kt:243` | Das Protokoll liegt in `Download/BulkCompressor/`, nicht app-privat; jede App mit Speicherberechtigung (Android ≤ 12) kann es lesen. |
| B3 | Daten-Exposition / impliziter Intent | MEDIUM | `MainActivity.kt:383` | „Log teilen“ schickt `Log.exportText()` — GPS, Geräte- und alle Dateipfade — als `ACTION_SEND` an *jede* installierte App. |
| B4 | Over-Privilege / Berechtigungen | MEDIUM | `AndroidManifest.xml:43` | `MANAGE_EXTERNAL_STORAGE` (All-Files-Zugriff) für eine Funktion, die nur `setLastModified` braucht. |
| B5 | Dateizugriff außerhalb SAF | MEDIUM | `engine/MetaRestore.kt:106` | `java.io.File(path)` + `setLastModified` schreibt auf einen Pfad, für den der SAF-Grant nicht geprüft wird. |
| B6 | Path Traversal | MEDIUM | `engine/MetaRestore.kt:154` | `"$root/$relative"` ohne `..`-Filter und ohne `canonicalPath`-Vergleich gegen das Volume. |
| B7 | Dateinamen-Injektion | MEDIUM | `job/CompressionJob.kt:765` | Der rohe Display-Name aus dem SAF-Cursor fließt unsanitisiert in `DocumentsContract.createDocument`. |
| B8 | Path Traversal (latent) | LOW | `engine/MetaRestore.kt:142` | Der `file://`-Zweig gibt `uri.path` ohne jede Prüfung zurück; jeder künftige Aufrufer bekommt einen beliebigen Pfad. |
| B9 | Fehlerbehandlung / runCatching | LOW | `MainActivity.kt:189` | Ein gescheitertes `takePersistableUriPermission` wird verschluckt, die URI aber trotzdem persistiert. |
| B10 | Pfad-Aufbau (Legacy) | LOW | `job/CompressionJob.kt:907` | Ein Pfad wird aus der DocID gebaut und an den MediaScanner gegeben, ohne Validierung (nur SDK < 29). |
| B11 | Aufräumlogik | LOW | `log/DownloadsLog.kt:310` | Die Aufräum-Abfrage löscht nach Pfad-Präfix/Namens-Präfix, ohne Eigentümer zu prüfen. |
| B12 | Komponenten-Exposition | INFO | `AndroidManifest.xml:63` | *Negativ:* nur `MainActivity` ist exported, und nur MAIN/LAUNCHER. |
| B13 | PendingIntent | INFO | `job/CompressionService.kt:526` | *Negativ:* alle PendingIntents `FLAG_IMMUTABLE` und explizit adressiert. |
| B14 | Secrets | INFO | `.gitignore:11` | *Negativ:* kein Keystore, kein `keystore.properties`, kein Token im Repo. |
| B15 | Zip-Slip / Archive | INFO | — | *Negativ:* der Code entpackt kein Archiv (kein `ZipFile`/`ZipInputStream`/`TarFile`). |
| B16 | Backup / Extraktion | INFO | `AndroidManifest.xml:48` | `allowBackup="true"`, aber die Include-Liste beschränkt sich auf die Settings-XML. |
| B17 | Zwischenablage | LOW | `ui/TerminalPanel.kt:388` | „Kopieren“ legt den kompletten Log — inklusive GPS — in die System-Zwischenablage. |

## Top 5

1. **B1 + B2** — Ortsdaten aller komprimierten Fotos im öffentlichen Download-Ordner. Zusammen der einzige Befund mit realistischer Tragweite (Wohnadresse).
2. **B3** — derselbe Inhalt geht per Chooser an eine fremde App, ohne dass etwas auf Vertraulichkeit geprüft wird.
3. **B4 + B5 + B6** — All-Files-Permission plus ungeprüfter DocID→Pfad-Schreibpfad: eine Berechtigungsstufe über dem, was SAF zusichert.
4. **B7** — Vertrauensgrenze: der App-Code nimmt an, der DocumentsProvider hätte den Namen schon bereinigt.
5. **B9** — stille Fehlerbehandlung genau an der Stelle, an der eine Sicherheitsentscheidung (persistente Freigabe) fällt.

---

## Begründungen

### B1 — GPS im Klartext-Protokoll (HIGH)

`engine/MetaRestore.kt:73-79` wählt für die Protokollzeile ausdrücklich
`ExifInterface.TAG_GPS_LATITUDE` / `TAG_GPS_LONGITUDE`; `:224` schreibt sie wörtlich:
`"EXIF Kernwerte: ${target.name} · " + keyValues.entries.joinToString(" · ") { … "$tag=" + value … }`.
`Log.d` hat keinen Level-Filter (`log/Log.kt:287-289` → `emit`), und `emit` spiegelt **jede**
Zeile in die Außensenke (`log/Log.kt:433` „`appendExternal(entry)`“). „Nur Debug“ ist also
hier keine Milderung — die Zeile landet in der Datei. Was in der Datei steht, ist die
präzise Position (Grad/Minuten/Sekunden-Anteile) jedes Fotos, verknüpft mit dem Dateinamen
und damit der Adresse des Nutzers. Das ist der stärkste Einzelbefund des Audits.

### B2 — Senke im öffentlichen Download-Ordner (HIGH)

`log/DownloadsLog.kt:243`: `put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")`.
Der Kommentar `:20-22` nennt das Ziel offen: „damit sie ohne Kabel und ohne adb auf den
Rechner geholt werden kann“. `IS_PENDING` wird nicht gesetzt, die Datei wird per
`openOutputStream(uri, "w")`/`"wa"` inkrementell beschrieben (`:248-251`) — sie ist also auch
halb geschrieben sichtbar. Auf Android ≤ 9 genügt einer anderen App
`WRITE_EXTERNAL_STORAGE` (impliziert Lesen), auf Android 10–12 `READ_EXTERNAL_STORAGE`,
um `Download/BulkCompressor/*.log` zu lesen; Aufbewahrung 12 Dateien / 21 Tage
(`:53`, `:56`). Auch der Absturzbericht geht in dieselbe Senke
(`log/CrashGuard.kt:96` „`DownloadsLog.writeCrash(context, report)`“).

### B3 — Log-Weitergabe per implizitem ACTION_SEND (MEDIUM)

`MainActivity.kt:383-387`: `val text = Log.exportText()` … `putExtra(Intent.EXTRA_TEXT, text.take(400_000))`.
`exportText` enthält laut `log/Log.kt:322-336` Version, Gerät, Android-Version, den Pfad der
Logdatei und alle Ringpuffer-Zeilen — also B1 plus sämtliche Dateipfade des Nutzers. Der
Intent ist implizit (`Intent(Intent.ACTION_SEND)`, `type = "text/plain"`, `:384-385`), das
Ziel bestimmt also der Chooser, nicht die App. Das ist gewollte Funktion, aber die
Datenmenge ist nicht abgestuft: es gibt keinen Weg, „nur den letzten Fehler“ zu teilen.

### B4 — MANAGE_EXTERNAL_STORAGE (MEDIUM)

`AndroidManifest.xml:42-44` deklariert `android.permission.MANAGE_EXTERNAL_STORAGE` mit
`tools:ignore="ScopedStorage"`; `MainActivity.kt:823-824` öffnet dafür
`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`. Der Grund steht in
`engine/MetaRestore.kt:101-102`: „setLastModified braucht einen Pfad,
COLUMN_LAST_MODIFIED ist schreibgeschuetzt“. Eine All-Files-Berechtigung, die von Google
Play nur für Dateimanager/Virenscanner akzeptiert wird, für eine Metadaten-Korrektur ist
das Missverhältnis, das den Pfad-Befund B5/B6 erst scharf macht. Ohne diese Berechtigung
liefe `setLastModified` in eine `SecurityException` und `runCatching` (`:107`) würde sie
sauber abfangen — die App wäre dann auf `COLUMN_LAST_MODIFIED` bzw. Verzicht beschränkt.

### B5 — `java.io.File` auf Nutzerdaten außerhalb der Grant-Prüfung (MEDIUM)

`engine/MetaRestore.kt:106-107`: `val file = File(path)` … `runCatching { file.setLastModified(millis) }`.
Der Pfad kommt aus `filesystemPath(uri)` (`:96`), also aus der DocID, nicht aus einer
SAF-Operation. Damit entscheidet der Kernel über die Schreibbarkeit (All-Files-Permission
aus B4), nicht der `UriPermission`-Grant. Genau die Prüfung, die SAF als Sicherheitsgrenze
ausmacht, fällt hier weg — und `:122` protokolliert den Treffer als
„Zeitstempel gesetzt: ${file.name} … Pfad $path“.

### B6 — Path Traversal über die DocID (MEDIUM)

`engine/MetaRestore.kt:144-154`: `val relative = documentId.substringAfter(':', "")` …
`return "$root/$relative"` — ohne `..`-Ablehnung, ohne `File(...).canonicalPath.startsWith(root)`.
Der Rest der Funktion prüft immerhin `uri.authority != EXTERNAL_STORAGE_AUTHORITY` (`:143`),
was beliebige Fremd-Provider ausschließt; ein Provider, der sich als
`com.android.externalstorage.documents` ausgibt, oder eine DocID mit `..`-Anteilen liefert
danach einen Pfad beliebig außerhalb des gewählten Ordners — bei gesetzter
All-Files-Permission bis in `/storage/emulated/0/Android/data/<fremd>/…`.
**[ANNAHME]** Der Angreifer müsste diesen Provider selbst installieren und vom Nutzer als
Ordner wählen lassen; die ausnutzbare Wirkung ist auf `setLastModified` beschränkt. Deshalb
MEDIUM und nicht HIGH — die fehlende Prüfung ist aber real, nicht hypothetisch.

### B7 — Display-Name fließt in `createDocument` (MEDIUM)

`job/CompressionJob.kt:765-767`: `val newName = Naming.compressedName(item.displayName, …)` …
`target = createSibling(item.parentId, mime, unique)`, und `:870-872`
`DocumentsContract.createDocument(resolver, parent, mime, name)`. `Naming.baseNameOf`
(`engine/Naming.kt:18-21`) und `compressedName` (`:30-34`) arbeiten rein textlich: kein
`/`-Ersetzen, keine `..`-Ablehnung, keine Zeichen-Allowlist. Der Name stammt aus
`DocumentsContract.Document.COLUMN_DISPLAY_NAME` des Scans und ist damit Fremddaten.
Der App-Code verlässt sich darauf, dass der Provider den Namen bereinigt.
**[ANNAHME]** AOSP tut das; ein Drittanbieter-Provider muss es nicht. Fix: Name vor
`createDocument` auf `[A-Za-z0-9._ -]` reduzieren und `..` verwerfen.

### B8 — `file://`-Zweig ohne Prüfung (LOW)

`engine/MetaRestore.kt:142`: `if (uri.scheme == "file") return uri.path`.
Heute unerreichbar — die Scan-URIs sind `content://` aus `buildDocumentUriUsingTree` —,
aber die Funktion ist public und liefert für ein `file://`-URI jeden Pfad ungeprüft an
`File(path).setLastModified` zurück. Eine Schnittstelle ohne Torwächter.

### B9 — `runCatching` um die Persistenz-Freigabe (LOW)

`MainActivity.kt:188-190`: `val flags = … or FLAG_GRANT_WRITE_URI_PERMISSION` →
`runCatching { contentResolver.takePersistableUriPermission(uri, flags) }` →
`prefs.edit().putString(KEY_TREE, uri.toString()).apply()`.
Scheitert die Übernahme (z. B. weil der Provider nur Lesen gewährt hat), wird das
verschluckt und die URI trotzdem als dauerhafte Ordnerwahl gespeichert. Der Folgelauf
bricht dann erst beim Schreiben ab. Kein Rechte-Zugewinn, aber eine stille
Zustandsabweichung an genau der Stelle, an der über Rechte entschieden wird.

### B10 — Pfad aus DocID für den MediaScanner (LOW)

`job/CompressionJob.kt:904-908`: `val documentId = DocumentsContract.getDocumentId(uri)` …
`val path = "${Environment.getExternalStorageDirectory().absolutePath}/$relative"` …
`MediaScannerConnection.scanFile(context, arrayOf(path), …)` — ohne `..`-Filter. Nur
SDK < 29 (`:902`). Wirkung: eine falsche Pfad-Meldung an den Scanner, kein Schreiben.

### B11 — Aufräumen ohne Eigentümer-Prüfung (LOW)

`log/DownloadsLog.kt:306-327`: Auswahl über `"${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"`,
`arrayOf("%${FOLDER}%")`, dann `if (!logName.startsWith(PREFIX)) continue` und
`if (index > KEEP_FILES)` → Zeilen werden gelöscht. `OWNER_PACKAGE_NAME` wird weder
gefiltert noch geprüft. Eine fremde Datei in einem Ordner, dessen Pfad „BulkCompressor“
enthält und die „bulkcompressor-“ heißt, fällt unter dieselbe Aufräumregel.
**[ANNAHME]** MediaStore verweigert das Löschen fremder Zeilen — der Befund ist damit
praktisch entschärft, die Regel ist aber unnötig weit gefasst.

### B12–B16 — Bestätigte Negativ-Befunde

* **B12** `AndroidManifest.xml:61-68`: `<activity android:name=".MainActivity" android:exported="true">` mit ausschließlich `MAIN`/`LAUNCHER`. `StatsActivity` `:78` und `CompressionService` `:89` sind `android:exported="false"`; es gibt keinen `BroadcastReceiver` und keinen `IntentFilter` außer dem Launcher-Filter.
* **B13** `job/CompressionService.kt:522-527` und `:549-554`: beide PendingIntents tragen `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE` und zielen explizit auf `MainActivity` bzw. `CompressionService`. Kein `FLAG_MUTABLE`, kein impliziter Intent.
* **B14** `git ls-files` enthält weder `*.keystore`/`*.jks` noch `keystore.properties`; die Datei existiert lokal mit Modus `600` und ist über `.gitignore:11` ignoriert. `keystore.properties.example` enthält nur `CHANGE_ME`.
* **B15** Kein Archiv-Code: weder `ZipFile`/`ZipInputStream`/`TarFile` noch `extractTo`. `SafeReplace` kopiert Byte-Streams (`job/SafeReplace.kt`), `MetaRestore` liest EXIF — Zip-Slip ist strukturell nicht möglich.
* **B16** `AndroidManifest.xml:48-50` setzt `allowBackup="true"`, aber `res/xml/backup_rules.xml` und `res/xml/data_extraction_rules.xml` enthalten je nur `<include domain="sharedpref" path="bulk_compressor_settings.xml" />`. Sobald ein `<include>` existiert, gilt nur diese Liste — Logs, `run-history.json` und die Journal-Dateien werden nicht gesichert.

### B17 — Zwischenablage (LOW)

`ui/TerminalPanel.kt:384-390`: `val content = text.text?.toString().orEmpty()` …
`clipboard.setPrimaryClip(ClipData.newPlainText(…, content))`. Der kopierte Text enthält
dieselben GPS-Zeilen wie B1. Auf Android 10+ darf nur die Vordergrund-App die Zwischenablage
lesen, deshalb LOW und nicht höher — der Inhalt ist aber unnötig sensibel für eine
„Terminal kopieren“-Aktion.

---

## Empfohlene Reihenfolge

1. `MetaRestore.IMPORTANT_TAGS` auf `DATETIME_ORIGINAL`, `MAKE`, `MODEL` reduzieren (B1) — einzeilige Änderung, größte Wirkung.
2. Außensenke standardmäßig aus, nur auf ausdrücklichen Wunsch; Inhalt zusätzlich auf Pfad-Basisname kürzen (B2, B3).
3. `filesystemPath` mit `canonicalPath`-Vergleich gegen `root` härten und `..` verwerfen (B6, B8, B10).
4. `MANAGE_EXTERNAL_STORAGE` entfernen und Zeitstempel über `COLUMN_LAST_MODIFIED` bzw. Verzicht lösen (B4, B5).
5. Display-Namen vor `createDocument` bereinigen (B7).
