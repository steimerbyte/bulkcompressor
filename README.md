[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/steimerbyte)
> ⭐ If you find this useful, consider [supporting me on Ko-fi](https://ko-fi.com/steimerbyte)!

# Bulk Compressor

<p align="center">
  <a href="https://github.com/steimerbyte/bulkcompressor/releases/latest"><img src="https://img.shields.io/github/v/release/steimerbyte/bulkcompressor?style=flat&colorA=222222&colorB=58A6FF" alt="Release"></a>
  <a href="https://github.com/steimerbyte/bulkcompressor/blob/master/LICENSE"><img src="https://img.shields.io/github/license/steimerbyte/bulkcompressor?style=flat&colorA=222222&colorB=58A6FF" alt="License"></a>
  <a href="https://github.com/steimerbyte/bulkcompressor/releases"><img src="https://img.shields.io/github/downloads/steimerbyte/bulkcompressor/total?style=flat&colorA=222222&colorB=58A6FF" alt="Downloads"></a>
</p>

Nothing-OS-inspirierte Android-App, die einen **Ordner voller Videos und
Bilder** komprimiert — ohne die Galerie durcheinanderzubringen.

Der Kern der App ist nicht das Komprimieren, sondern das **Ersetzen**:
die Datei bleibt, wo sie war, behält ihren Namen und ihren Zeitstempel
und wird nur kleiner. Kein neuer Ordner, keine Dubletten, keine
umgekippte Chronologie.

Kein Konto. Kein Netzwerk. Keine Analytics. Kein `INTERNET`-Recht.

---

## Was die App tut

1. **Ordner wählen** über das Android-Systemdialog (Storage Access
   Framework). Die App bekommt dauerhaften Zugriff auf genau diesen
   Ordner und liest rekursiv Videos und Bilder heraus.
2. **Preset einstellen** (siehe unten).
3. **Auswählen**, was bearbeitet wird — alles oder einzelne Dateien.
4. **Start.** Alles Weitere läuft als Vordergrunddienst mit
   Fortschrittsbenachrichtigung, auch bei gesperrtem Bildschirm.

Das Protokoll zeigt pro Datei eine Zeile:

```
ERSETZT IMG_20240611_184210.jpg · 4,12 MB → 1,03 MB (25 %) · 4032x3024 · JPEG · EXIF
NEU      VID_20240603_101244.mkv · 812,44 MB → 168,10 MB (20 %) · 1920x1080 · H.264 (Encoder-Limit) · 2842 kbit/s
SKIP     IMG_20240501_090000.jpg: Ergebnis waere 2,10 MB statt 1,80 MB
```

`ERSETZT` = die Originaldatei wurde überschrieben, `NEU` = eine
zusätzliche Datei daneben.

**Nie überschrieben wird**, wenn das Ergebnis größer wäre als das
Original — dann steht dort `SKIP`. Ebenso, wenn die Einstellungen für
die Datei nichts ändern (z. B. „1080p" bei einem 720p-Video).

## Presets

**Video — Bitrate:** 100 %, 75 %, 50 %, 35 %, 25 % der Quellbitrate.

**Video — Auflösung** (bezogen auf die *kurze* Kante, damit Hochformat
nicht als Querformat behandelt wird):

| Preset | kurze Kante | typisch |
|---|---|---|
| Original | — | unverändert |
| 4K | 2160 | 3840×2160 |
| 2K | 1440 | 2560×1440 |
| 1080p | 1080 | 1920×1080 |
| 720p | 720 | 1280×720 |
| 480p | 480 | 854×480 |
| Auto (halbe) | — | halbiert die kurze Kante |

Hochskaliert wird nie: ein 720p-Video bleibt bei „4K" einfach 720p.
Ungerade Kanten werden auf gerade Werte abgerundet, weil Encoder sonst
scheitern.

**Video — Codec:** Automatisch (Quellcodec beibehalten), H.264 oder
H.265. H.265 ist bei gleicher Bitrate besser, aber nicht jedes Gerät
kann es; fehlt der Encoder, fällt die App auf H.264 zurück.

**Bilder — Format:** Behalten (JPEG bleibt JPEG, WebP bleibt WebP),
JPEG oder WebP. **Qualität:** 40–100 in 5er-Schritten.

## In-place oder Original behalten

| Modus | Ergebnis |
|---|---|
| **Original ersetzen** | Die Datei wird an derselben Stelle überschrieben. Name, Galerie-Eintrag und Pfad bleiben identisch. |
| **Original behalten** | Neue Datei im selben Ordner mit Suffix `_compressed` (bzw. `_compressed_1`, `_compressed_2` … wenn es den Namen schon gibt). |

Manchmal ist „Original ersetzen" technisch nicht möglich, dann legt die
App **immer** eine neue Datei daneben, auch im Ersetzen-Modus:

* Video aus **MKV, WebM oder AVI** — der Encoder schreibt MP4, ein
  Überschreiben würde die Datei unbrauchbar machen. Aus `film.mkv` wird
  `film_compressed.mp4`.
* **HEIC/PNG → JPEG** oder **JPEG → WebP** — Endung und Inhalt müssten
  sich gemeinsam ändern, das kann SAF nicht.

Für MP4, M4V und MOV ist das Ersetzen unkritisch: das sind alles
MP4-Container.

## Zeitstempel und Chronologie

Das ist der Teil, der unter Android wirklich schwierig ist, deshalb
hier offen:

* **Bilder:** Die Aufnahmezeit steckt in den EXIF-Daten (`DateTimeOriginal`).
  Die App kopiert sie samt GPS, Kamera und Belichtungswerten in die neue
  Datei. Google Photos und die meisten Galerien sortieren danach — die
  Chronologie bleibt also auch dann richtig, wenn der Dateizeitstempel
  neu ist.
* **Videos:** Haben keine Aufnahmezeit in den Metadaten, die Android
  verlässlich ausliest. Hier zählt der Dateizeitstempel — und den darf
  eine App über SAF **nicht** setzen (die Spalte `COLUMN_LAST_MODIFIED`
  ist schreibgeschützt).
* **Ausnahme:** Mit dem Berechtigungsschalter **„Zugriff auf alle
  Dateien"** (optional, per Knopf in der App) löst die App den Pfad auf
  und setzt den Änderungszeitstempel zurück. Die App prüft danach nach,
  ob der Wert wirklich an der Datei steht, und meldet es im Protokoll,
  wenn nicht.

**Praktische Empfehlung:** Ohne „Zugriff auf alle Dateien" im Modus
**Original behalten** arbeiten — dann bleibt das Original mit seinem
Zeitstempel liegen, und die neue Datei erbt über EXIF die Aufnahmezeit.
Wer in-place ersetzen will und die Chronologie braucht, schaltet den
Zugriff ein.

## Berechtigungen

| Berechtigung | Wofür |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Komprimierung läuft weiter, wenn die App im Hintergrund ist |
| `WAKE_LOCK` | Der Lauf wird bei ausgeschaltetem Bildschirm nicht eingefroren |
| `POST_NOTIFICATIONS` | Fortschrittsanzeige und Abbrechen-Knopf |
| `WRITE_EXTERNAL_STORAGE` (**nur Android 7–9**, `maxSdkVersion=28`) | Logdatei im öffentlichen Download-Ordner; ab Android 10 erledigt das `MediaStore` ohne Berechtigung |
| `MANAGE_EXTERNAL_STORAGE` | **optional** — nur für das Zurücksetzen des Dateizeitstempels; die App läuft vollständig ohne |

Kein `INTERNET`. Die App kann nichts nach außen senden, auch nicht
wollen.

## Logdatei

Jeder Lauf schreibt eine eigene Datei nach
**`Download/BulkCompressor/bulkcompressor-JJJJMMDD-HHMMSS.log`** — über
`MediaStore`, also ohne Berechtigung ab Android 10. Vor dem Start gibt es
dort nichts; die Datei entsteht mit der ersten Zeile.

* **Was drin steht:** Kopfzeile mit Version, Gerät, Android-Version, ABI,
  Arbeitsspeicher und freiem Cache; jede Einstellung mit Zeitstempel;
  pro Datei Auflösung, Codec (Hardware/Software), Bitrate, Dauer und
  Einsparung; Meilensteine bei 10/25/50/75/100 % je Video inklusive
  Geschwindigkeit gegenüber Echtzeit und Restzeit-Schätzung; Zähler für
  Frames, Samples und Bytes; jeder übersprungene Eintrag **mit Grund**.
* **Abstürze:** `CrashGuard` schreibt den Stacktrace nach
  `Download/BulkCompressor/bulkcompressor-crash-JJJJMMDD-HHMMSS.log`
  und zusätzlich in den app-privaten Speicher. Der Bericht enthält den
  Vorlauf der letzten 2000 Logzeilen, Thread, PID, Speicher und den Pfad
  der laufenden Logdatei.
* **Aufbewahrung:** die letzten 12 Dateien bzw. 21 Tage; ältere werden
  beim Start gelöscht. Eine Datei wird bei 8 MB fortgesetzt
  (`-teil2.log`), damit keine Zeile verloren geht.
* **Ohne Kabel:** dieselben Zeilen liegen im ausklappbaren Terminal der
  App; **TEILEN** schickt sie als Text (inklusive Absturzbericht) an jede
  Ziel-App.
* **App-privat als Rückfallebene:** `files/logs/app.log` (512 KiB,
  rotierend) — hieraus holt die App beim Start den Vorlauf, wenn der
  Download-Ordner mal nicht beschreibbar ist.

## Technik

* **Video:** Decoder → Surface → Encoder (MediaCodec), Audio wird
  unverändert durchgereicht, wenn es AAC ist, sonst nach AAC
  umkodiert; Muxing mit MediaMuxer. Es wird **kein Frame** durch den
  App-Speicher kopiert — die Puffer bleiben in den Codecs, der
  Speicherbedarf ist unabhängig von der Auflösung.
* **Bild:** `BitmapFactory` mit `inSampleSize`, damit auch ein
  100-Megapixel-Foto nicht als Vollbild im Heap landet.
* **Dateizugriff:** ausschließlich SAF (`ACTION_OPEN_DOCUMENT_TREE`,
  `takePersistableUriPermission`), kein `java.io.File` auf
  Nutzerdaten — die App fragt keine Ordner ab, die sie nicht gezeigt
  bekommt.
* **Abbruch** wirkt zwischen zwei Dateien und mitten im Video (der
  laufende Transcode wird beendet, die Zieldatei ist eine
  Cache-Temp-Datei und wird verworfen — das Original bleibt intakt).
* **Klickgeräusche:** `SoundPool` mit drei kurzen WAV-Dateien (4–9 KB),
  erzeugt von `tools/generate_click_sounds.py` — heller Klick für Chips,
  Liste und Werkzeugleiste, tieferer für Schalter, größerer für
  START. Beim Ziehen eines Sliders klickt jeder geänderte Schritt,
  gedrosselt auf 25 pro Sekunde (`MIN_GAP_MS`), damit es nicht knattert.
  Der Ton folgt zusätzlich der Systemeinstellung „Berührungsklänge":
  ist die aus, bleibt die App still, egal was der Schalter in der App
  sagt. Abschaltbar über die Zeile **KLICKGERÄUSCHE**.
* Kein `ffmpeg`: die App ist ~2 MB groß statt ~60 MB, und die
  Hardware-Encoder des Geräts sind ohnehin schneller als libx264.

## Installation

**Download:** [neuestes Release](https://github.com/steimerbyte/bulkcompressor/releases/latest)
— die APK liegt dort als `app-release.apk`.

1. APK aufs Gerät übertragen, antippen, „App aus unbekannter Quelle"
   bestätigen, installieren
2. „Ordner wählen" → im Systemdialog den Ordner freigeben (z. B. `DCIM`)
3. Preset einstellen, Dateien auswählen, **Start**

Gebaut gegen Android 15 (`compileSdk`/`targetSdk` 35), `minSdk` ist 24.
Auf einem echten Gerät wurde diese Fassung noch nicht ausgeführt — siehe
**Grenzen**.

## Grenzen (ehrlich)

* **Ohne Gerät gebaut.** Encoder-/Decoderpfade (MediaCodec) lassen sich
  ohne Android-Hardware nicht ausführen; die Logik drumherum (Presets,
  Namensgebung, Größenvergleich, Dateinamen, Zeitstempel-Toleranz) ist
  durch Unit-Tests abgedeckt, die Codec-Aufrufe nicht. Der erste Start
  auf einem echten Gerät ist damit der eigentliche Test.
* **HDR-Videos** werden auf 8 Bit gebracht; die HDR-Metadaten
  (`colr`/`mdcv`) überleben das Umkodieren nicht. Aus HDR10 wird SDR
  mit derselben Auflösung.
* **Kein Bild-Auflösungs-Limit**, nur Qualität — ein 48-MP-Foto bleibt
  48 MP groß, nur mit weniger Byte pro Pixel.
* **EXIF-Kopieren deckt die üblichen Felder ab**, keine vollständige
  Kopie: die Android-Bibliothek kann vorhandene Felder nicht
  aufzählen. Vorschaubilder im EXIF werden nicht neu erzeugt.

## Lizenz

Apache License 2.0 — siehe [LICENSE](LICENSE).
