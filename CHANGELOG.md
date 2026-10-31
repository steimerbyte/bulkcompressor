# Changelog

Alle nennenswerten Änderungen an diesem Projekt. Format nach
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung
nach [SemVer](https://semver.org/lang/de/).

## [1.3.0] – 2026-10-31

Ein Lauf ueber hunderte Videos dauert Stunden. Diese Fassung beschaeftigt
sich mit dem, was in dieser Zeit schiefgehen kann: kein RAM, kein Platz,
ein beendeter Prozess, ein Encoder, der einen Plan nicht mag. Der Grundsatz
dabei: **langsamer ist ein Erfolg, abgeschossen nicht.**

### Hinzugefügt
- **Speichertafel ganz oben**: App-Heap (belegt / Obergrenze) mit
  Segmentbalken, der bei Speicherdruck grau und rot wird, dazu der freie
  Arbeitsspeicher des **Geraets** und — nach einem erfolgreichen Lauf —
  der freigewordene Plattenplatz in GB (gespeichert, also auch beim
  naechsten Start sichtbar). Der Takt ist eine Sekunde, nur im Vordergrund.
- **RAM-Waechter**: zwei Signale, beide zusammen. Der eigene Heap ab 72 %
  Belegung drosselt, ab 85 % deutlich; die Systemmeldung `onTrimMemory`
  ueberstimmt die Heapschaetzung. Gedrosselt wird durch Warten vor der
  naechsten Datei **und** alle 24 Samples im Video-Transcode; der
  Bilddecode rechnet unter Druck mit einem kleineren Budget
  (`maxMemory/10` statt `/6`, unter starker Last `/16`). Nach spaetestens
  30 s Wartezeit geht es weiter — dann steht im Protokoll, dass gewartet
  wurde und warum. `TRIM_MEMORY_UI_HIDDEN` gilt bewusst **nicht** als
  Druck, sonst bremste jeder Wechsel in den Hintergrund den Lauf.
- **Lauf-Journal und Wiederaufnahme**: jede Datei wird nach ihrem Ergebnis
  sofort in `filesDir` vermerkt. Wird der Prozess beendet (Speicherdruck,
  Update, Hersteller-Akkuschoner), setzt der naechste Start beim Rest
  fort, statt von vorn zu beginnen. Der Dienst nutzt dafuer
  `START_REDELIVER_INTENT`, die Oberflaeche pruft beim Oeffnen ebenfalls.
  Ein Journal aelter als 24 Stunden oder fuer einen anderen Ordner wird
  verworfen; bei bewusstem Abbruch verschwindet es.
- **Rueckfallebene beim In-Place-Ersetzen**: SAF kuerzt die Zieldatei beim
  Oeffnen des Schreibkanals. Bisher haette ein Abbruch dazwischen das
  Original halb geschrieben hinterlassen. Jetzt wird es vorher in den
  Cache kopiert, die Zielgroesse wird nach dem Schreiben geprueft, und bei
  jedem Fehlschlag wird die Kopie zurueckgeschrieben. Reicht der Platz
  nicht, steht das **vorher** im Protokoll.
- **Platzpruefung vor jeder Datei**: genug freier Cache fuer Datei plus
  128 MB Reserve, sonst haelt der Lauf mit klarer Meldung an — und das
  Journal bleibt stehen, damit er spaeter weiterlaeuft.
- **Wachhund-Erneuerung**: das Zeitlimit von 6 Stunden war das Ende jedes
  laengeren Laufs. Alle 5 Minuten wird die Sperre neu gesetzt, der
  Wechsel steht im Protokoll.
- **H.264-Rueckfall**: meldet der Encoder bei H.265 einen
  `CodecException`, wird dieselbe Datei einmal mit H.264 erneut versucht.

### Verbessert
- **Start des Vordergrunddienstes abgesichert**: verweigert Android ihn
  (ab Android 12 aus dem Hintergrund moeglich), wird der Auftrag gemeldet
  und beendet, statt eine Ausnahme aus `onStartCommand` fliegen zu lassen.
- **Ein erschoepfter Heap trifft nur eine Datei**: `OutOfMemoryError` wird
  je Datei gefangen, protokolliert (mit Speicherstand) und als Fehler
  gezaehlt; der Lauf macht weiter.

### Tests
- 78 JVM-Tests (vorher 47): Journal (Abbruch mitten im Schreiben,
  Wiederaufnahme, kaputte letzte Zeile, Sonderzeichen), Rueckfallebene
  (Kanal faellt mitten im Schreiben aus, falsche Zielgroesse, fehlender
  Platz), Speichereinordnung (Schwellen, Systemmeldung gegen Heapschaetzung,
  `UI_HIDDEN`), Zahlen der Tafel, dazu vier Robolectric-Pruefungen der
  Tafel selbst.

## [1.2.0] – 2026-10-31

### Hinzugefügt
- **Klickgeräusche** im Minecraft-Stil: kurzer Holz-Klick bei Chips,
  Listenzeilen, Schaltern, Werkzeugleiste und Knoepfen, ein groesserer
  beim Start. Slider klicken pro geaendertem Schritt, gedrosselt auf 25
  pro Sekunde. Drei synthetisch erzeugte WAV-Dateien (4–9 KB,
  `tools/generate_click_sounds.py`), abgespielt über `SoundPool`.
  Abschaltbar über die neue Zeile **KLICKGERÄUSCHE**; der Ton folgt
  zusaetzlich der Systemeinstellung "Beruehrungsklaenge".
- **Logdatei im Download-Ordner**: jede Sitzung schreibt nach
  `Download/BulkCompressor/bulkcompressor-JJJJMMDD-HHMMSS.log`, ab
  Android 10 über `MediaStore` ohne Berechtigung, auf Android 7–9 mit
  `WRITE_EXTERNAL_STORAGE` (auf `maxSdkVersion=28` begrenzt). Kein Kabel,
  kein adb: Datei im Download-Ordner öffnen und hochladen.
- **Fortsetzung statt Ende**: ab 8 MB beginnt `-teil2.log` derselben
  Sitzung; die letzten 12 Dateien bzw. 21 Tage bleiben liegen, ältere
  werden beim Start gelöscht.
- **Sitzungskopf** in der Datei: Version, Gerät, Android-Version, ABI,
  Arbeitsspeicher, freier Cache, Pfad der internen Datei.
- **Absturzbericht auch als Download**: `bulkcompressor-crash-*.log` mit
  Thread, PID, Speicher, ABI, Logpfad, **2000** Zeilen Vorlauf und
  vollständigem Stacktrace. Der Bericht der Vorsitzung wird beim nächsten
  Start zusätzlich in die neue Sitzungsdatei gehängt.
- **Deutlich ausführlicheres Protokoll** in Engine, Lauf und Dienst:
  Spurübersicht und Encoder-Auswahl (Name, Hardware/Software,
  `maxInstances`), Encoder-Format mit Profil/Level, Zähler für Samples,
  Frames, Bytes und Stall-Runden, Fortschrittsmeilensteine mit
  Geschwindigkeit gegenüber Echtzeit und Restzeit-Schätzung, Tonpfad
  (durchgereicht oder neu kodiert) mit Format und Zählerständen,
  Bildschritte (EXIF-Orientierung, `inSampleSize`, Skalierungsfaktor,
  Ersparnis), Metadaten-Kopie mit Feldnamen und Ergebnis, je Datei
  Aktion und Grund bei Überspringen, alle 10 Dateien eine
  Verdichtungszeile mit Durchsatz und Restzeit.
- **Terminal** meldet den Pfad der Download-Datei und warnt einmal, wenn
  dort nicht geschrieben werden kann.

### Behoben
- **Absturz beim ersten Lauf**: `WAKE_LOCK` fehlte im Manifest. Der
  Dienst holte beim Start einen `PARTIAL_WAKE_LOCK`, bekam eine
  `SecurityException` und starb – in 1.0.0 und 1.1.0. Die Berechtigung
  ist jetzt deklariert.
- **Fehlender Wachhund kostet den Lauf nicht mehr**: `acquire()` fängt
  jeden Fehler ab und protokolliert ihn. Vorher flog die Ausnahme aus
  `onStartCommand` heraus und nahm den Prozess mit; jetzt läuft der
  Auftrag ohne Zeitgeber weiter.
- `ManifestPermissionsTest` prüft am zusammengeführten Manifest, dass
  `WAKE_LOCK` und `FOREGROUND_SERVICE_DATA_SYNC` deklariert sind und der
  Dienst `foregroundServiceType="dataSync"` trägt.

### Geändert
- `versionCode` 4, `versionName` 1.2.0. 1.1.1 wurde nie einzeln
  veröffentlicht — was hier steht, ist der Sprung von 1.1.0 auf 1.2.0.

## [1.1.0] – 2026-10-31

### Hinzugefügt
- **Logdatei**: Jede Zeile, die die App ausgibt, wird zusätzlich in
  `filesDir/logs/app.log` geschrieben (Ringpuffer 2000 Zeilen in der App,
  Datei rotiert bei 512 kB mit drei Generationen) und ins Logcat
  gespiegelt.
- **Absturzprotokoll**: `CrashGuard` hängt sich an den
  Standard-Exception-Handler und schreibt vor dem Prozessende
  Geräte-/Versionskopf, die letzten 200 Logzeilen und den vollständigen
  Stacktrace nach `logs/crash.log`. Die Datei überlebt den Absturz und
  wird über den Teilen-Knopf des Terminals verschickt.
- **Live-Terminal**: Ausklappbares Panel am unteren Rand, das die
  Logzeilen farbcodiert streamt. Ziehen am Griff ändert die Höhe
  (zugeklappt / 264dp / 75 %), Auto-Scroll pausiert beim Hochscrollen,
  Knöpfe für Kopieren, Teilen, Leeren und Schließen.
- **Aktive Konfiguration**: Karte über dem Startknopf, die alle
  wirksamen Einstellungen in drei Zeilen zusammenfasst (Video, Bilder,
  Ausgabe).
- **Statusmarken**: Rechts neben `VIDEO`, `BILDER`, `BITRATE` und
  `AUFLÖSUNG` steht, ob der Abschnitt mit der aktuellen Auswahl und dem
  aktuellen Modus wirkt (`● AKTIV` / `— INAKTIV` /
  `— NICHTS AUSGEWÄHLT`). Nicht wirksame Bedienelemente werden
  zurückgenommen und sind nicht bedienbar; die Auswahl bleibt dabei
  gespeichert.

### Geändert
- Der kleine Protokolltext im Fortschrittsblock ist entfallen – dieselben
  Meldungen laufen jetzt durch das Terminal und die Logdatei.
- Chips sind auch ohne Farbwahrnehmung unterscheidbar (gewählt: fette
  Monospace-Schrift mit weiterer Laufweite), inaktive Zeilen sind
  ausgegraut und nicht klickbar.
- `versionCode` 2, `versionName` 1.1.0.

## [1.0.0] – 2026-10-31

Erste Fassung.

### Funktionsumfang
- Ordnerwahl über das Storage Access Framework, rekursives Einlesen von
  Videos und Bildern, persistente Berechtigung.
- Videos: Transkodierung Gerät → Surface → Encoder über MediaCodec,
  Tone durchgereicht (AAC) oder nach AAC gewandelt. Presets für Bitrate
  (100/75/50/35/25 %), kurze Kante (Original, 4K, 2K, 1080p, 720p,
  480p, Auto-halb) und Codec (Auto, H.264, H.265) in beliebiger
  Kombination.
- Bilder: JPEG/WebP mit Qualitätsstufe 40–100, `inSampleSize` gegen
  Speicherlast bei großen Fotos.
- Ausgabe: Original ersetzen (gleicher Name, gleicher Ort, gleicher
  Galerie-Eintrag) oder Original behalten (`_compressed` daneben).
- Zeitstempel: EXIF-Felder inkl. GPS in die Zieldatei kopiert; der
  Änderungszeitstempel wird bei erteiltem Vollzugriff gesetzt und
  nachgemessen.
- Vordergrunddienst mit Fortschrittsbenachrichtigung, Abbrechen-Knopf,
  Wake-Lock; Abbruch mitten im Video hinterlässt keine halbe Datei.
- Übersprungen wird, wenn das Ergebnis nicht kleiner wäre oder die
  Einstellungen nichts ändern.

### Behoben vor der ersten Veröffentlichung
- `configure()` muss **vor** `createInputSurface()` laufen — sonst
  scheitert jeder Transcode mit `IllegalStateException`.
- Drehung: fehlte `KEY_ROTATION` im Track-Format, wurde das Video ohne
  Orientierung gemuxt und lag im Hochformat quer. Die Drehung kommt
  jetzt aus den Metadaten des Aufrufers und wird auf 0/90/180/270
  normalisiert.
- `startForegroundService` ist ab API 26 vorhanden — jetzt über
  `ContextCompat`, damit Android 7 nicht abstürzt.
- Abbrechen bei nicht laufendem Dienst hinterließ eine Dienstinstanz
  ohne Benachrichtigung; der Dienst beendet sich jetzt selbst.
- Muxer-Trackzähler wurde bei wiederholter Formatmeldung doppelt
  dekrementiert und hätte den Muxer vor der Tonspur gestartet.
- Hardware-Encoder werden vorgezogen; vorher konnte ein
  Software-Encoder gewinnen.
- Auflösungsskalierung rechnet auf die kurze Kante statt auf die lange:
  „1080p" bei 3840×2160 ergibt 1920×1080, vorher 3840×2160/2.
- Eingebettete ScrollView im Protokoll entfernt (verschachteltes
  Scrollen), tote Ressourcen gelöscht, Größenformatierung mit fester
  Locale.
