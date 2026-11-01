# Changelog

Alle nennenswerten Änderungen an diesem Projekt. Format nach
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung
nach [SemVer](https://semver.org/lang/de/).

## [1.7.0] – 2026-11-01

### Neu

- **Marker in den Metadaten jeder bearbeiteten Datei.** Ein zweiter Lauf
  über denselben Ordner komprimiert nichts mehr doppelt: die Datei trägt
  `nothing-bulk:1 runs=… out=…` (EXIF `UserComment` bei Bildern, eigene
  `uuid`-Box am Dateiende bei MP4), wird in der Liste als **SCHON
  KOMPRIMIERT** angezeigt und übersprungen. Der Marker überlebt
  Neuinstallation und fremde Dateimanager, weil er in der Datei steht.
  Fremder Kommentartext bleibt erhalten.
- **Schalter „Bereits komprimierte erneut bearbeiten"** für den Fall,
  dass der erste Lauf zu schwach war; `runs` zählt dann hoch.
- **Prüfung jeder Ausgabe vor dem Schreiben** (`Verifier`): Dateikopf
  gegen das zugesagte Format, Box-Tabelle bei MP4, Dekodierbarkeit der
  Bilddaten, Marker. Eine beanstandete Datei wird verworfen, das Original
  bleibt unangetastet.
- **Prüfung nach dem Schreiben** an der abgelegten Datei (Container,
  Größe, Marker, Video-Spur über `MediaExtractor`). Bei „Original
  ersetzen" hängt sie in der Rückfallebene: ein Befund schreibt das
  Original zurück. Bei „Original behalten" wird die kaputte neue Datei
  entfernt.

### Behoben

- `CompressionJob` meldete für neue Dateien Erfolg, ohne den Inhalt der
  geschriebenen Datei je gelesen zu haben — Größe allein hätte einen
  Container mit richtiger Länge und kaputtem Rest durchgelassen.

## [1.6.1] – 2026-10-31

### Geändert

- **Fortschritt aus dem Worker wird auf dem Haupt-Thread gezeichnet.**
  `CompressionService.onState` läuft auf dem Thread, der meldet; der Dienst
  schaltet jetzt auf den Haupt-Thread um, bevor er die Benachrichtigung
  baut. Vorher schrieben Worker und Haupt-Thread dieselben Felder
  (Meldungskadenz, Gerätespeicher, Zähler) ungeschützt nebeneinander.
- **Die Statistikseite liest ihre Datei im Hintergrund.** Sie las sie in
  `onCreate` auf dem Haupt-Thread. Lesen und Zeichnen sind jetzt getrennt
  (`HistoryReader`, Thread `run-history`), dieselbe Regel wie im
  Hauptbildschirm — eine eigene Klasse statt zwei fast gleicher
  Executors.
- **Die Laufhistorie hat ein Schloss je Datei, nicht je Objekt.** Dienst
  und Oberfläche halten verschiedene Instanzen auf verschiedenen Threads;
  mit einem Schloss je Instanz konnte ein Lesen-Schreiben-Paar des
  Dienstes den Eintrag überschreiben (im Test mit sechs gleichzeitigen
  Schreibern: 20 von 60 Läufen überlebten).

### Behoben

- Der Dienst meldete beim Start einen Fortschritt aus einem zurück­
  gebliebenen Bus-Zustand, wenn derselbe Zustand noch im Prozess stand.
  Betraf nur den Zustandsüberlauf zwischen zwei Läufen; die Tests treten
  jetzt mit zurückgesetztem Bus an.

## [1.6.0] – 2026-10-31

### Hinzugefügt

- **Statistikseite** (Diagramm-Symbol oben rechts): Summen über alle
  Läufe — gesparte Bytes, Läufe, Dateien, davon die kleiner gewordenen,
  Durchschnitt je Lauf — darunter eine Zeile je Lauf mit Ordner,
  Zeitpunkt und Ergebnis, Balken relativ zum größten Lauf. Die Werte
  bleiben über App-Neustarts erhalten.
- **Summenzeile auf dem Hauptbildschirm**: „Insgesamt gespart: 4,50 GB
  in 4 Läufen" in der Speichertafel.
- Die Statistik wird vom **Dienst** geschrieben (`filesDir/run-history.json`),
  nicht von der Oberfläche. Ein Lauf zählt damit auch dann, wenn beim
  Abschluss keine Activity existiert — genau der Fall, in dem die
  bisherige Freigabe-Zahl verloren ging, weil sie nur im
  Activity-Zustand stand.

### Geändert

- Die Speichertafel liest den letzten Lauf aus der Historie statt aus
  eigenen `SharedPreferences`. Vorher gab es zwei Stände derselben Zahl;
  Tafel und Statistikseite konnten verschiedene Werte zeigen.
- Datei bleibt klein: je Lauf fünf Felder, begrenzt auf die letzten 100
  Läufe. Ältere fallen aus der Liste, zählen aber weiter in der Summe.

### Behoben

- Die Fusszeile der Statistikseite zeigte `%1$d` statt der Zahl. Die
  Fusszeile erscheint jetzt nur noch, wenn es eine Liste gibt, und ein
  Test prüft, dass kein Platzhalter im Text stehen bleibt.

## [1.5.0] – 2026-10-31

### Geändert

- **Der Bildschirm ist nach der Arbeitsreihenfolge sortiert.** Oben Ordner
  und Dateiauswahl, dann Video, Bilder, Ausgabe und Optionen, darunter
  Speicherstand und eine Zusammenfassung der Einstellungen. Vorher stand
  die Auswahl nach den Presets, und die Zusammenfassung lag über dem
  Startknopf, wo sie je nach Dateizahl den halben Bildschirm füllte.
- **Der Startknopf ist immer erreichbar.** Er sitzt fest am unteren Rand;
  Inhalt und Protokoll scrollen bzw. schieben sich darüber. Bei einem
  Ordner mit hunderten Dateien war er vorher erst nach langem Wischen zu
  sehen.
- **Das Protokoll schiebt den Inhalt, statt ihn zu verdecken.** Die
  feste Reserve unter dem Inhalt (`paddingBottom`) entfällt; ein
  aufgeklapptes Terminal verdeckt keine Bedienelemente mehr.
- **Chip-Reihen brechen um, statt abzuschneiden** (`WrappingRowLayout`).
  Auf 360 dp Breite lagen „720p" und „480p" außerhalb des Bildschirms —
  eine Auswahl, die es gibt, aber nicht antippbar war. Die sechs
  `HorizontalScrollView`-Zeilen sind ersetzt; gewischt wird nicht mehr.
- **Statuslabels nennen nur noch die Ausnahme.** Ein Abschnitt, der
  wirkt, trägt kein „● AKTIV" mehr; sichtbar ist „INAKTIV" bzw.
  „NICHTS AUSGEWÄHLT". Bis zu fünf gleichzeitige „AKTIV"-Labels hatten
  genau dem Wort den Wert genommen, auf das es ankommt.
- **Ein gesperrter Startknopf sieht gesperrt aus** — schwache Kante,
  graue Schrift statt weißer Fläche mit schwarzer Schrift.
- **Die Zusammenfassung ist eine Spalte.** `VIDEO`, `BILDER`, `AUSGABE`
  und `OPTIONEN` stehen untereinander mit gleicher Einrückung; die
  Ausgabezeile mit Zeitstempel und EXIF ist auf zwei Zeilen verteilt,
  damit sie auf schmalen Geräten nicht mitten in der Spalte umbricht.
- Die Mengenzeile zeigt nur noch, **was** ausgewählt ist (Anzahl und
  Größe). Auflösung und Qualität standen dort ein zweites Mal und stehen
  jetzt nur im Konfigurationsblock.

### Behoben

- Die Zusammenfassungszeile unter dem Ordnerknopf blieb unsichtbar, wenn
  die Auswahl ohne `startScan()` ankam (wiederhergestellter Zustand) —
  sie hängt nicht mehr an der Reihenfolge der Aufrufe.
- Bei leerer Auswahl blieb in der Mengenzeile ein einzelner Bindestrich
  stehen; die Zeile wird jetzt ausgeblendet.

## [1.4.1] – 2026-10-31

### Behoben

- **Die Klickgeräusche waren nie zu hören — auch nicht beim ersten Mal.**
  Die Sperre gegen zu schnelle Wiederholungen verglich den Zeitstempel
  gegen `Long.MIN_VALUE`; `jetzt - Long.MIN_VALUE` läuft im `Long`-Bereich
  über und wird negativ, die Bedingung war also immer wahr. Weil der
  Zeitstempel erst *nach* der Prüfung gesetzt wurde, blieb es nicht beim
  ersten Klick: jeder einzelne wurde verschluckt. Die Prüfung läuft jetzt
  über den gespeicherten Wert (`lastPlayedAt[click]?.let { … }`).
- **Zweite Ursache derselben Stille: die App hing an der
  Systemeinstellung „Berührungsklänge".** Die ist auf vielen Geräten ab
  Werk aus; der Schalter in der App stand sichtbar auf an und niemand
  konnte sehen, warum kein Ton kam. Der Schalter in der App entscheidet
  jetzt allein, dazu kommen der Lautlos-/Vibrationsmodus und
  Systemlautstärke 0 — die Regeln stehen als reine Funktion in
  `SoundPolicy` und sind ohne Gerät geprüft.
- Lautstärkefaktor von 0,55 auf 1,0: die Klickdateien sind bereits auf
  −1 dBFS normalisiert, die Reduktion war nur leiser ohne Zweck.
- Der erste Tipp nach einem Kaltstart ging verloren, wenn die Klickdatei
  noch dekodiert wurde; er wird jetzt vorgemerkt und nach dem Laden
  nachgeholt. Fehlschläge beim Dekodieren stehen im Protokoll.

### Hinzugefügt

- **Das Protokoll sagt, warum es still bleibt.** Beim Aufbau eine Zeile
  mit App-Schalter, Systemton-Modus, System- und Medienlautstärke sowie
  dem Wert der Systemeinstellung; danach genau eine Zeile je Wechsel des
  Grundes (`Klick wird gespielt`, `Telefon ist lautlos oder auf
  Vibration`, `Systemlautstaerke ist 0`). Vorher war „kein Ton" nicht von
  einem Fehler zu unterscheiden.

## [1.4.0] – 2026-10-31

### Hinzugefügt

- **Arbeitsspeicher in der Fortschrittsbenachrichtigung.** Dort steht jetzt
  `4 / 10 · RAM 412/768 MB · Gerät 2,10 GB frei` — der Fortschritt, der
  eigene Heap und der freie Arbeitsspeicher des Geräts in einer Zeile, die
  auch aufgeklappt erhalten bleibt. Bei Speicherdruck kommt das Wort des
  Wächters dazu (`knapp`, `sehr knapp`) und erklärt, warum der Balken
  gerade steht.
  Der Geraetespeicher wird hoechstens alle zwei Sekunden erfragt, der
  eigene Heap bei jeder Meldung; die Kadenz der Benachrichtigung bleibt
  bei 400 ms oder 5 % Fortschritt.
- Die Startmeldung nennt dieselbe Zeile, damit der Speicherstand nicht
  erst beim ersten Prozentwechsel auftaucht.

## [1.3.1] – 2026-10-31

Zwei Beobachtungen aus dem ersten echten Lauf.

### Behoben

- **Der Startknopf blieb tot, wenn ein Lauf im Hintergrund endete.** Der
  Knopf wurde in `launchJob` gesperrt und erst durch eine Zustandsmeldung
  wieder freigegeben. Kam diese Meldung nicht an (App im Hintergrund), sah
  man "Start", konnte aber nicht druecken — auch nach Wahl eines neuen
  Ordners nicht. Die Anzeige kommt jetzt bei jedem Zustand aus `JobBus`;
  ein neuer Ordner mit Dateien macht den Knopf sofort wieder bedienbar.
- **Auftraege wurden vom Dienst wortlos verworfen** (kein Ordner, keine
  Dateien, kein Vordergrunddienst, voriger Lauf noch im Abschluss). Jetzt
  nennt das Protokoll den Grund, die Oberflaeche bekommt einen
  Abschlusszustand, und ein Start waehrend des Abschlusses wird vorgemerkt
  und danach ausgefuehrt statt fallen gelassen.
- **Der Dienst meldet den angenommenen Auftrag sofort** ("laeuft"), nicht
  erst nach dem erneuten Einlesen des Ordners. Ein zweiter Griff auf Start
  startet dadurch nicht mehr versehentlich doppelt.
- **Startwaechter in der Oberflaeche:** hat der Dienst den Auftrag nach
  4 s nicht angenommen, wird einmal nachgestartet und danach gemeldet
  ("Start kam nicht an") — nie wieder ein Knopf, der nichts tut.

### Geaendert

- **Start und Stopp in einem Knopf**, ganz unten statt in der Mitte, mit
  Rueckmeldung `Startet ...` bis der Dienst uebernimmt und `STOPP`
  waehrend des Laufs. Der getrennte Abbrechen-Knopf entfaellt.
- Ohne Ordner oder ohne ausgewaehlte Datei zeigt der Knopf `Keine Auswahl`
  statt eines Klicks, der nur einen Hinweis-Toast ausloest.

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
