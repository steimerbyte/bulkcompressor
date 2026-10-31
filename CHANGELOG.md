# Changelog

Alle nennenswerten Änderungen an diesem Projekt. Format nach
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung
nach [SemVer](https://semver.org/lang/de/).

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
