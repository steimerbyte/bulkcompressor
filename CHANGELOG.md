# Changelog

Alle nennenswerten Änderungen an diesem Projekt. Format nach
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung
nach [SemVer](https://semver.org/lang/de/).

## [1.10.0] – 2026-11-05

### Geändert

- **Die Oberfläche läuft im NothingOS-Layoutsystem.** Der bisherige
  XML-Bildschirm (1290 Zeilen, eigene Views für Chips, Regler, Punktmatrix,
  Stand-Tafel und Terminal) ist durch Compose ersetzt: Kicker und Titel,
  Live-Panel mit CPU und RAM als Histogramme, Kennzahlen-Panel (Dateien,
  eingesparte Bytes, Dauer), Fortschrittsbalken in der festen unteren
  Leiste, Liste mit Vorher- → Nachher-Größe und Ersparnis je Datei,
  About mit dem Leitspruch `Function over form.` allein in Akzentfarbe.
  Die Bausteine liegen in `ui/Nothing.kt`, der Bildschirmzustand in
  `ui/HomeState.kt`; eine zweite Stelle mit Farben oder Schriftgrößen gibt
  es nicht mehr.
- **Preset eingeklappt.** Video, Bilder, Ausgabe und Optionen stehen
  hinter einer Zeile, die die aktuelle Wahl zusammenfasst, und öffnen sich
  mit einem Tipp. Vorher nahmen sie mehr Höhe ein als der Bildschirm.
- **Querformat.** Live-Panel und Kennzahlen stehen nebeneinander, der
  Fußsatz entfällt — vorher blieb unter den Panels kein Platz für die
  Liste.
- **Protokoll als Overlay** hinter dem Knopf `LOG`, mit Kopieren, Teilen,
  Leeren. Es nimmt der Liste keinen Platz mehr weg.
- **Statistikseite** ebenfalls auf Compose umgebaut, gleiche Bausteine,
  gleiche Reihenfolge (Summen, Balken relativ zum größten Lauf).
- **CPU-Zeile zeigt den eigenen Prozess.** `/proc/stat` ist für Apps auf
  Android 17 gesperrt (am Gerät gemessen); `/proc/self/stat` ist lesbar.
  Die Zeile heißt deshalb `CPU · PROZESS` — die Kompression läuft in
  diesem Prozess, der Wert ist damit der richtige für „was kostet der
  Lauf".
- **Zustand in `rememberSaveable`:** Ordner, Auswahl, Filter, Suche und
  die aufgeklappte Preset-Zeile überleben Drehung und Prozessende; ein
  neuer Scan behält die Auswahl der noch vorhandenen Dateien, statt still
  wieder alles anzuhaken.

### Behoben

- **Jede Bilddatei wurde nach dem Schreiben als Fehler verworfen und das
  Original zurückgeschrieben.** Der Kompressionsmarker wird nach dem
  Messen der Ergebnisgröße in die Temp-Datei geschrieben und macht sie um
  seine eigene Länge größer (bei JPEG 69 B); die Rückfallebene verglich
  danach die geschriebenen Bytes mit der Größe **vor** dem Marker und
  brach jedes Ersetzen als „falsche Größe" ab. Am Gerät mit fünf Bildern
  reproduziert (5 von 5 Fehlern, 0 B gespart). Die erwartete Größe wird
  jetzt nach dem Marker gelesen.
- **Die untere Leiste nennt einen gescheiterten Lauf nicht mehr
  `FERTIG`:** Sind alle Dateien fehlgeschlagen, steht dort
  `FEHLGESCHLAGEN`, und das Ergebnis-Overlay sagt dasselbe.
- **Späte Scan-Meldungen** nach dem Ende oder nach einem abgelösten Scan
  werden verworfen; die Live-Zeile blieb sonst nach dem Scan stehen.

### Entfernt

- `ScanBeacon`, `NothingSlider`, `ChipRow`, `SegmentBar`, `StatsBoard`,
  `TerminalPanel`, `WrappingRowLayout`, `MediaAdapter` und die zugehörigen
  XML-Layouts; `AppCompatActivity`, `appcompat` und `recyclerview` als
  Abhängigkeiten. Vier Fünftel der Strings in `strings.xml` waren nur noch
  für die gelöschten Ansichten da und sind mitgegangen.

### Tests

- **288 JVM-Tests.** Die alten View-Tests (fehlende View-IDs,
  Sichtbarkeit, Platzhalter) prüfen jetzt den Bildschirmzustand
  (`HomeState`) und die Startbeschriftung; der Scan-Test über den
  Testanbieter treibt die Fortschrittszeile Schritt für Schritt.
  Neu: Auswahl bleibt beim Neuladen erhalten, Suche und Filter ändern die
  Auswahl nicht, Einstellungen werden gespeichert, gescheiterte Läufe
  tragen die richtige Beschriftung.

## [1.9.6] – 2026-11-01

### Behoben

- **Der Scan sah aus, als täte er nichts.** Bei einem Ordner mit vielen
  Dateien stand in der Ordner-Karte minutenlang nur „Ordner wird gelesen …"
  und darunter der Anfangszustand; im Protokoll ebenfalls Stille. Ursache:
  Fortschritt entstand nur beim Einordnen in die Liste (Durchgang 3),
  während der langsame Teil — je Datei ein Blick in den Dateikopf für den
  Marker — gar nichts meldete. Bei 500 Fotos waren das 500 Lesezugriffe
  ohne eine einzige Zeile.
  - Die Live-Zeile nennt jetzt in jedem Durchgang, was läuft:
    „517 Dateien · liest · Camera/IMG_0421.jpg", dann
    „517 Dateien · prüft 213/517 · Camera/IMG_0421.jpg". Die erste Meldung
    kommt vor dem ersten Ordner, ein Phasenwechsel wird nie gedrosselt.
  - Gezählt wird, was **gefunden** ist, nicht was schon auf der Liste
    steht — vorher blieb der Zähler bei „0 Dateien", bis der ganze Ordner
    durch war.
  - Die Zählerzeile zeigt „Ordner wird gelesen …", solange noch kein Ordner
    fertig ist, statt „0 Ordner · 0 Dateien".
  - Das Protokoll bekommt eine Zeile je Ordner (`Ordner 3: DCIM/Camera ·
    517 Eintraege`) und bei großen Ordnern alle 250 Marker eine
    Fortschrittszeile.

### Tests

- `SafScannerFilterTest`: die erste Meldung trägt schon „liest", der
  Marker-Durchgang nennt den Bruch („prüft 1/3"), der Zähler folgt dem
  Fund, und jeder gelesene Ordner hinterlässt eine Protokollzeile.

## [1.9.5] – 2026-11-01

### Geändert

- **Der Statistik-Einstieg sitzt jetzt unter der Stand-Tafel**, als
  beschrifteter Knopf über die volle Breite: gleiche Bauform wie „Zugriff
  erteilen", Mono-Schrift, 48 dp. Vorher war er eine 44-dp-Ikone oben
  rechts neben dem Kopfpunkt — ohne Beschriftung, mit negativen Rändern an
  den Rand geschoben, und ihr Ziel erfuhr man erst nach dem Tippen.
  - Der Ort ist der Inhalt: die Tafel darüber zeigt den letzten Lauf
    (APP-HEAP, freigegeben), die Seite dahinter die Summe über alle Läufe.
    Zwei Ansichten derselben Frage gehören nebeneinander, nicht in den Kopf
    über einen Ordnerauswähler.
  - Die Ikone `ic_stats` entfällt — nach dem Verschieben hatte sie keinen
    Aufrufer mehr.
  - `MainActivitySmokeTest` prüft jetzt beides: der Knopf steht im selben
    Elternknoten **nach** `stats_board`, und ein Klick startet
    `StatsActivity`.

## [1.9.4] – 2026-11-01

### Hinzugefügt

- **Mitlaufende Zeile im Ordner-Bereich.** Unter „1 Ordner · 17 Dateien"
  stand während des Scans nichts, obwohl der Scanner bei jedem Takt eine
  fertige Zeile liefert. Sie steht jetzt genau dort: Zähler vorn („40
  Dateien · 40 Marker unlesbar"), das eben angefaßte Ereignis hinten
  („Camera/IMG_0421.jpg"). Eine Zeile, monospace, gedimmt — die Zähler
  sind die Aussage, das Ereignis der Beleg, dass gerade etwas passiert.
  Nach dem Scan verschwindet sie: die Zusammenfassung mit dem Grund
  („Kopf nicht lesbar: FileNotFoundException …") übernimmt an derselben
  Stelle. Eine stehen bleibende Live-Zeile würde einen vergangenen Moment
  als Zustand behaupten.
  - `ScanLiveLineTest` fährt einen echten Scan über einen Anbieter, der
    keine Datei öffnet: die Zeile ist beim Start sichtbar und leer, trägt
    während des Laufs die Zähler, ist danach weg, und der Grund steht in
    der Zusammenfassung.

## [1.9.3] – 2026-11-01

### Behoben

- **Der Leser verschwieg, warum er scheiterte.** Auf einem Gerät stand im
  Protokoll für rund 500 Dateien dieselbe Zeile: „Anbieter lieferte keinen
  Lesestrom". Das war keine Beobachtung, sondern eine Vermutung: der Leser
  fing jede Ausnahme ab und machte `null` daraus, und `null` wurde als
  „Anbieter liefert nichts" gedeutet. Ein abgelaufener Ordner-Zugriff
  (`SecurityException`) sah damit genauso aus wie eine gelöschte Datei
  (`FileNotFoundException`) — und der einzige Weg zur Wahrheit war ein
  Kabel und `adb logcat`.
  - `openInputStream` wirft jetzt durch, bis es gefangen wird: jeder
    Fehlschlag trägt Klassennamen und Meldung, z. B. „Kopf nicht lesbar:
    SecurityException: Permission Denial: …".
  - Ein Grund wird **einmal** protokolliert, nicht einmal je Datei. 500
    gleiche Zeilen haben die Ursache verdeckt; der Scan schreibt ohnehin
    eine Zusammenfassung.
  - Der Grund steht zusätzlich in der Zusammenfassung auf dem Bildschirm,
    direkt neben der Zahl der betroffenen Dateien: was der Nutzer
    entscheiden muss (Ordner neu wählen oder nicht), entscheidet sich an
    der Ursache, nicht an der Anzahl.

## [1.9.2] – 2026-11-01

### Behoben

- **Ein Lauf, der etwas getan hatte, meldete beim nächsten Scan „nichts
  komprimiert".** Der Marker stand in den Dateien; der *Leser* scheiterte.
  Er sprang über den Dateideskriptor (`Os.pread`) ans Dateiende, und
  Anbieter, die keine seekbare Datei liefern (Cloud, Downloads, manche
  Geräteanbieter), geben dafür eine Pipe. Aus dem Fehlschlag wurde still
  `null` — und `null` hieß für den Filter „nicht komprimiert".
  - Der Lesestrom (`openInputStream`) ist jetzt der Weg, der entscheidet;
    der Deskriptor bleibt Abkürzung für echte Dateien.
  - Jeder Fehlschlag ist **`UNREADABLE` mit Grund**, nicht `null`. Die
    Datei bleibt auf der Liste (sie wird nicht stillschweigend ein
    zweites Mal verkleinert) und die Zusammenfassung nennt sie:
    „· 4 Marker nicht lesbar". Der Grund steht einzeilig im Protokoll.
  - Neun neue Tests, darunter der eigentliche Fehler: ein Anbieter, der
    **ausschließlich** Pipes liefert, muss den Marker trotzdem finden —
    MP4 über das Dateiende und JPEG über EXIF.

### Neu

- **Chronologie für Videos.** Der MP4-Kopf (`mvhd`, `tkhd`, `mdhd`) trägt
  `creation_time`, und `MediaMuxer` schreibt dort die Zeit des Laufs —
  die Galerie sortiert das Video damit ans Ende. Die App berichtigt die
  Felder nach dem Schließen des Muxers (32- und 64-Bit-Kopf, vorzeichenlos,
  1904-Ära) auf den ermittelten Zeitpunkt.
- **Zeitpunkt mit Rückfallebene.** Ist die Änderungszeit der Quelle
  unbrauchbar (0 oder Zukunft), wird das Datum aus dem Dateinamen
  gelesen (`IMG_20250815_142530`, `Screenshot 2025-08-15`,
  `20250815-142530`, `IMG_20250315` am Namensende). Ist auch das nicht
  möglich, wird **kein** Wert gesetzt — ein 0-Wert würde in der Galerie
  auf 1970 landen. Der Zeitpunkt wird einmal je Datei entschieden und
  landet an beiden Stellen (Dateizeit, Container-Kopf), damit Sortierung
  und Anzeige nicht auseinanderlaufen.
- Der Hinweis „Zeitstempel ließ sich nicht zurücksetzen" erscheint nur
  noch, wenn ein Setzen tatsächlich scheiterte — nicht, wenn es schlicht
  kein Datum gab.

### Werkzeug

- **Release-Sperre.** `tools/release.sh` veröffentlicht nur nach voller
  grüner Testsuite: sauberer Arbeitsbaum, erhöhter `versionCode` gegen die
  letzte Marke, Testergebnisse vorher gelöscht und danach ausgezählt
  (`tests > 0`, `failures == 0`), Artefaktprüfung mit `aapt2` und
  `apksigner`, und die veröffentlichte Datei wird zurückgelesen.
  Zusätzlich hängt `assembleRelease` in `app/build.gradle.kts` an
  `testDebugUnitTest`, damit auch ein manueller Bau den Schritt nicht
  überspringen kann, und `.githooks/pre-push` prüft jeden Push einer
  Marke: bei roter Suite verweigert Git den Push. Ein Branch-Push bleibt
  unberührt, damit der Haken nicht beim ersten Zwischenstand abgeschaltet
  wird.
- Gekürzt wird im Skript mit `sed -n`, nicht mit `head`: `aapt2 dump
  badging` schreibt 118 Zeilen, `head -1` schließt die Pipe, und mit
  `set -o pipefail` bricht das Skript dann an SIGPIPE ab (Exit 141) —
  mitten nach dem Upload, mit dem Anschein eines Fehlers. Nachgewiesen:
  `aapt2 … | head -1` → 141, `aapt2 … | sed -n '1p'` → 0.

### Geprüft

- 287 Unit-Tests grün, davon 34 neue (Marker-Leser über nicht-seekbare
  Anbieter, MP4-Kopf-Zeit, Dateinamen-Muster, Prioritätskette,
  Einstellungs-Persistenz, Scan-Zähler in der Zusammenfassung).
- **Nicht** geprüft: das Setzen der Dateizeit und das Lesen über einen
  Anbieter auf einem echten Gerät — hier fehlen Emulator und Gerät. Die
  Container-Berichtigung ist gegen echte MP4-Dateien getestet, nicht gegen
  eine Aufnahme eines Geräte-Codecs.

## [1.9.1] – 2026-11-01

### Geändert

- **Ein zweiter Scan zeigt keine bereits komprimierten Dateien mehr.**
  Bisher stand jede markierte Datei auf der Liste und wurde erst beim
  Start übersprungen — die Auswahl war damit eine Aussage über Dateien,
  die gar nicht drankommen. Jetzt entscheidet der Marker schon beim
  Scan: markierte Dateien kommen nicht auf die Liste, aus dieser Liste
  wird der Auftrag gebildet. Der Marker wird mit demselben Leser gelesen,
  den auch der Job benutzt, also kostet es je Datei einen Blick in den
  Kopf (bei EXIF zusätzlich einen Deskriptor) — für zehntausend Bilder
  spürbar, deshalb:
  - die Zusammenfassung nennt die Zahl („· 12 bereits komprimiert"),
  - das Protokoll nennt sie je Ordner und am Ende die Laufzeit,
  - die Einstellung „Marker ignorieren" schaltet den Filter ab und listet
    wieder alles.
- Der Job prüft den Marker weiterhin unmittelbar vor jeder Datei:
  zwischen Ordnerauswahl und Start kann ein anderer Lauf dieselbe Datei
  erwischt haben.

### Geprüft

- 182 Unit-Tests grün, davon zwei neue: markierte Datei fehlt in der
  Liste und wird gezählt; abgeschalteter Marker listet beide. Gegen den
  alten Stand scheitert der erste (nachgewiesen).
- **Nicht** geprüft: das Lesen des Markers über einen echten SAF-Anbieter
  auf einem Gerät. Robolectric kann es nicht — `Os.pread` (MP4-Ende) und
  `ExifInterface(FileDescriptor)` (JPEG) greifen dort auf fehlende
  Deskriptoren zu. Der Leser selbst ist unverändert der aus 1.7.x und mit
  Dateien direkt getestet.

## [1.9.0] – 2026-11-01

### Behoben

- **Der Dauerton war auf einem Telefonlautsprecher kaum zu hören — drei
  Ursachen, alle gemessen.**

  1. **Der Stream war der falsche.** Gespielt wurde mit
     `USAGE_ASSISTANCE_SONIFICATION`, und das landet auf dem
     *Systemton*, nicht auf Medien. Die Seitentasten regeln die
     Medienlautstärke; wer sein Telefon auf volle Lautstärke stellt,
     stellt sie dort ein. Die Systemton-Lautstärke steht davon getrennt
     und auf vielen Geräten niedrig — der Ton konnte also beliebig laut
     in der Datei sein und blieb trotzdem leise. Jetzt
     `USAGE_MEDIA` + `CONTENT_TYPE_SONIFICATION`: Lautstärke kommt von
     dem Regler, den man tatsächlich in der Hand hat. Die Abschaltregel
     prüft entsprechend die Medienlautstärke (Grund im Protokoll:
     `Medienlautstaerke ist 0`).
  2. **Das Spektrum lag unterhalb des Lautsprechers.** 85 % der Energie
     saßen unter 150 Hz, der Grundton bei 73,5 Hz — eine Membran von
     wenigen Millimetern gibt dort praktisch nichts ab, egal wie laut
     die Datei ist. Jetzt tragen die vierte Harmonische (294 Hz, beim
     Auftragston 392 Hz) und die Oktave; gemessen 68 % (Auftragston
     65 %) zwischen 200 und 500 Hz. Dumpf bleibt es trotzdem: über
     800 Hz liegt **nichts** (Rauheit 0,034), der Tiefpass sitzt jetzt
     bei 800 statt 380 Hz. „Dumpf" heißt keine Höhen, nicht „nur
     Tiefbass".
  3. **Der Pegel war auf den Effektivwert normiert.** 0,12 RMS hießen
     Spitze 0,32 — 10 dB unter Vollaussteuerung. Jetzt ist auf Spitze
     0,78 normiert (−2,2 dBFS), Effektivwert 0,27 (−11,5 dBFS).

  Zusammen mit dem Stream-Wechsel ist das deutlich mehr als die
  geforderten 10×; auf einem Lautsprecher wirkt vor allem Punkt 2, und
  das ist der Grund, warum mehr Pegel allein nicht gereicht hätte.

- **Ein Klick senkt den Dauerton ab.** Mit Spitze 0,78 plus Klick bei
  0,85 wären es 1,63 und damit über der Vollaussteuerung. Für die Dauer
  des Klicks (200 ms) geht der Dauerton auf 0,19 zurück und danach
  wieder hoch — derselbe Trick wie eine Sidechain-Kompression. 0,19 ist
  die Obergrenze (0,85 + 0,78·0,19 = 0,998); laufen zwei Dauertöne,
  teilen sie sich dieses Budget.
- **Ein zweiter Stopp ließ die Schleife weiterlaufen.** `stopHum` blendet
  160 ms aus und schaltet erst im letzten Schritt ab, hatte den Strom
  aber sofort als beendet verbucht — ein zweiter Stopp in diesem
  Fenster löste die laufende Rampe ab, und der Schritt, der abschaltet,
  fiel mit weg. Die Endlosschleife (loop −1) lief danach für die
  Lebensdauer des Prozesses weiter. Ausgelöst wurde das im
  Normalbetrieb: `onStop` **und** `onDestroy` der Activity rufen beide
  `stopJobHum()`. Jetzt bleibt der Strom bis zum Ende der Ausblendung
  verbucht (`stopping`), ein zweiter Stopp ist folgenlos.
- **Ein Neustart während der Ausblendung ließ den alten Strom stehen.**
  Verlässt man die App und kommt sofort zurück, fällt `onResume` in die
  160 ms; der alte Strom lief dann als zweite Schleife derselben Datei
  parallel zum neuen, und die Pegelteilung zählte nur einen. Der alte
  Strom wird jetzt sofort angehalten (hörbar nicht, er steht schon unter
  0,2).
- **Die Ausblendung schnitt bei 20 % ab.** Der letzte Schritt setzte nur
  den Buchhaltungswert auf 0 und schaltete ab, ohne 0 noch an
  `setVolume` zu geben — jeder Scan- und jeder Auftragston endete mit
  einem Sprung von −16 dBFS, also genau dem Knall, den die Ausblendung
  verhindern soll.
- **Zwei gleichzeitige Dauertöne clippten.** Bei 0,7 je Ton waren es
  0,78·0,7·2 = 1,09 Spitze; jetzt 0,6 je Ton (0,94).

### Geändert

- Der Generator normiert auf die **Spitze** statt auf den Effektivwert
  (`normalize_peak` statt `normalize_loop`): bei einem Ton, der eine
  Minute läuft, begrenzt die Spitze, nicht der Mittelwert.
- Die Tests messen „dumpf" nicht mehr über Nulldurchgänge — die hängen
  am höchsten starken Anteil und hätten die neue, für Lautsprecher
  hörbare Fassung fälschlich als „nicht dumpf" verworfen. Geprüft wird
  jetzt die **Rauheit** (Energie der ersten Differenz; für einen
  höchsten Anteil f gilt r ≤ 2·sin(π·f/fs), bei 48 kHz ist 0,10 die
  Grenze für etwa 700 Hz) und der Anteil im Lautsprecherband
  (RBJ-Bandpass bei 400/500 Hz). Dazu ein Test für die Absenkung.

## [1.8.0] – 2026-11-01

### Neu

- **Ein zweiter Dauerton für einen laufenden Auftrag.** Der Scan hat
  seinen (D2, 73,5 Hz) — jetzt hat der Lauf seinen eigenen: gleiche
  Bauart, gleiche Lautstärke, aber eine Quarte höher (G2, 98 Hz) und mit
  doppelt so schnellem Puls (1 Hz statt 0,5 Hz). Gemessen: 510 gegen 382
  Nulldurchgänge in zwei Sekunden, Ähnlichkeit der Spektren 0,04 —
  verwandt genug, um als dasselbe Gerät zu gelten, verschieden genug, um
  im Vorbeihören zu wissen, ob gelesen oder gerechnet wird. Auch hier
  nichts über 400 Hz.

  Er läuft **nur, solange die App sichtbar ist und der Auftrag läuft**:
  der Zustand kommt über `JobBus`, im Hintergrund (`onStop`) schweigt er
  — ein Summen aus der Hosentasche wäre schlimmer als keins —, und beim
  Zurückkommen wird der Zustand neu gelesen, statt sich auf verpasste
  Meldungen zu verlassen. Ende des Laufs, Abbruch und geschlossene
  Activity beenden ihn.

  Laufen Scan und Auftrag gleichzeitig (möglich: ein Ordner lässt sich
  während eines Laufs neu einlesen), teilen sich beide den Pegel auf
  0,7: jeder einzeln hörbar, zusammen nicht lauter als einer plus Klick.

### Geändert

- **Der Dauerton ist dumpf und deutlich lauter.** 1.7.6 summte eine Quinte
  höher (A2, 110 Hz) mit Quinte, Oktave und einem feinen Schimmer bei
  1320 Hz — gemessen war das ein heller, schlanker Ton. Jetzt liegt der
  Grundton bei 73,5 Hz (D2) mit 0,5-Hz-Schwebung, dazu Oktave (147 Hz)
  und Quinte (220,5 Hz); der Schimmer und alles über 300 Hz sind weg,
  ein Tiefpass bei 380 Hz nimmt die letzte Kante. Gemessen: 85 % der
  Energie unter 150 Hz, **nichts** über 400 Hz (vorher 0,1 % im Bereich
  400–2000 Hz, Linien bis 330 Hz). Dumpf heißt: kein Glanz, weicher
  Ansatz — deshalb blendet der Ton jetzt auch über 240 ms ein, statt
  mit einem Stoß zu beginnen.

  **Lauter um ~10 dB:** Effektivwert 0,035 → 0,12 (−29 → −18 dBFS). Die
  Spitze bleibt bei 0,32, also unter dem Start-Klick (0,85); ohne die
  Einblendung wären beide gleichzeitig bei 1,17 und damit über der
  Begrenzung — genau deshalb blendet er ein.

  Der Tiefpass läuft **zirkulär** (ein Durchlauf verwirft nur den
  Einschwingzustand, der zweite wird behalten): ein Filter, der an der
  Naht neu einschwingt, würde die Schleife genau dort knacken lassen.
  Ein Test prüft weiterhin, dass der Sprung an der Naht kleiner ist als
  der größte Sprung im Ton selbst, jetzt zusätzlich, dass der Ton
  dumpf ist (Nulldurchgänge statt Höhen) und dass er die geforderte
  Lautstärke hat.

## [1.7.6] – 2026-11-01

### Neu

- **Der Scan summt, solange er läuft.** Bisher meldeten nur zwei Klicks
  Anfang und Ende eines Ordner-Scans — dazwischen war es still, und bei
  einem großen Baum (DCIM mit Unterordnern) wusste man nicht, ob die App
  noch liest oder schon fertig ist. Jetzt liegt unter den Klicks ein
  leiser Dauerton: A2 mit 110,5 Hz daneben (0,5 Hz Schwebung), Quinte,
  Oktave und ein feiner Schimmer — ein Summen, kein Signalton. Er beginnt
  mit dem Lesevorgang und wird bei seinem Ende über 200 ms ausgeblendet.

  Die Datei ist eine **nahtlose Schleife** und keine abgespielte
  2-Sekunden-Datei mit Blende: alle Teiltöne und Hüllkurven sind
  ganzzahlige Vielfache von 0,5 Hz, deshalb schließt die letzte Probe
  exakt an die erste an. Gemessen an der Datei: der Sprung an der Naht
  (79/32768) ist kleiner als der größte Sprung innerhalb des Tons (87) —
  umgekehrt knackt es bei jedem Umlauf. Ein Test hält das fest, samt
  Prüfung, dass an den Enden nichts ausgeblendet ist.

  Pegel über den Effektivwert (-29 dBFS) statt über die Spitze: ein Ton,
  der eine Minute läuft, wird nach dem Effektivwert beurteilt. Mit dem
  Spitzenpegel der Klicks wäre er unerträglich laut. Er folgt demselben
  Schalter und denselben Regeln wie die Klicks (Lautlos-Modus, Systemlautstärke
  0), läuft nur einmal (ein zweiter Strom derselben Schleife wäre doppelt
  so laut) und endet auf jedem Weg: Erfolg, Fehler, Abbruch, geschlossene
  Activity — ein hängender Dauerton wäre der schlimmste Fehler am Ton.

### Behoben

- **Das Release-Asset heißt wieder einheitlich `BulkCompressor-<Version>.apk`.**
  1.7.5 lag als `app-release-1.7.5.apk` neben `BulkCompressor-1.7.3/4.apk`;
  wer nach Datum greift, landet bei der falschen Datei. Bytes unverändert.

## [1.7.5] – 2026-11-01

### Geändert

- **Der Regler hat eine durchgehende Schiene.** 1.7.4 hatte die
  Punktreihe statt eines Balkens gezeichnet; im Bild gelesen war das eine
  Skala, kein Regler: vierzehn einzelne Punkte über die ganze Breite
  zeigen keine Strecke von Anschlag zu Anschlag, und wo die Spanne endet,
  war nicht erkennbar. Jetzt trägt eine 1,5 dp hohe Linie die Spanne,
  darauf liegen die vierzehn Punkte als Skala (gefüllte weiß), und der
  Griff ist ein runder Knopf mit Ring statt eines Balkens. Drei Merkmale
  für denselben Wert, und keines davon nimmt der Form ihre Erkennbarkeit.
  Vor dem Commit gerendert und am Pixel geprüft: Knopf bleibt ganz
  innerhalb der Reglerzeile (18 dp in 40 dp), die Prozentzahl in der Zeile
  darüber hat 13 dp Abstand und wird nicht überlagert.

## [1.7.4] – 2026-11-01

### Neu

- **Der Regler ist eine Punktreihe statt eines Systembalkens.** Vierzehn
  Punkte, gefüllt bis zum Wert, dazu ein senkrechter Griff auf demselben
  Bruchteil. Der alte Balken war ein dünner Strich mit rundem Knopf — mit
  den Farben des Themes blieb davon ein weißer Strich auf Schwarz übrig,
  also die Formensprache jeder zweiten Android-App. Die Punktreihe ist
  dieselbe Sprache wie die Ladeanzeige und die Abschnittsmarken.
  Gezeichnet wird im Regler selbst (`NothingSlider`), die Tippfläche
  bleibt die des `SeekBar`: ein Tipp setzt den Griff genau dorthin, wo
  der Finger war. Punkte und Griff sind um eine Punktbreite eingerückt —
  ohne das wäre der erste Punkt angeschnitten gewesen und der Griff bei
  0 % zur Hälfte außerhalb.
- **Zwei Töne für den Ordner-Scan.** Beim Beginn ein Doppelschlag mit
  steigender Tonhöhe (860 → 1290 Hz), beim Ende ein einzelner tieferer
  Schlag (550 Hz) — dieselbe Holz-Familie wie die Klickgeräusche, kein
  Piep. Sie kommen aus demselben Generator (`tools/generate_click_sounds.py`)
  wie die drei Klicks und folgen demselben Schalter **KLICKGERÄUSCHE**;
  ein eigenes Konto gibt es nicht.

### Behoben

- **Der gesperrte Bitrate-Regler war nicht mehr ablesbar.** Zwei
  Abschwächungen lagen übereinander (35 % Deckkraft des Abschnitts plus
  eine zweite am Regler selbst) und halbierten den Unterschied zwischen
  gefüllten und leeren Punkten. Jetzt schwächt nur noch der Abschnitt ab.

## [1.7.3] – 2026-11-01

### Neu

- **Knopf „Neu scannen"** neben der Ordnerauswahl: derselbe Ordner wird
  erneut gelesen, ohne den Systemdialog. Gesperrt und sichtbar
  zurückgenommen, solange kein Ordner freigegeben ist oder ein Scan läuft.
- **Punktmatrix neben `ORDNER`**, die während des Lesens läuft (3×3
  Punkte, heller Kopf mit Nachlauf). Sie zeichnet im Ruhezustand nichts
  und behält nur ihren Platz, damit beim Start nichts springt; bei
  abgeschalteten Systemanimationen steht das Muster still.

## [1.7.2] – 2026-11-01

### Behoben

- **Der Ordner-Scan blieb auf der obersten Ebene stehen, wenn der
  Anbieter seine Ordner nicht als `vnd.android.document/directory`
  meldet.** Manche Dateimanager und Cloud-Anbieter nehmen
  `inode/directory`, `resource/folder` oder gar keinen Typ; solche
  Einträge galten als Datei ohne Medien-Typ und wurden verworfen —
  Unterordner wurden nie betreten. Jetzt entscheidet zusätzlich eine
  Liste bekannter Verzeichnis-Typen und, bei Einträgen ohne Typ und ohne
  Größe, ein Kind-Abruf: liefert der Anbieter dafür Einträge, ist es ein
  Ordner. Das Protokoll nennt die so erkannten Ordner.

## [1.7.1] – 2026-11-01

### Behoben

- **Kein Datenverlust mehr, wenn der Cache keine Rückfallebene fasst.**
  Bisher wurde trotzdem geschrieben: SAF kürzt die Datei schon beim
  Öffnen, also stand danach eine halbe Datei da. Jetzt wird der Austausch
  verweigert, bevor das Ziel angefasst wird – das Original bleibt heil,
  und das Protokoll sagt, warum. Fehlt beim Zurückschreiben der Platz,
  bleibt die Sicherung im Cache liegen und der Pfad steht in der Meldung
  (vorher wurde sie gelöscht).
- **Fortsetzen überspringt keine Fehlschläge mehr.** Dateien, die im
  abgebrochenen Lauf scheiterten, galten als erledigt und wurden beim
  Fortsetzen nie wieder angefasst. Zähler und Balken übernehmen jetzt den
  Stand der Vorsitzung, statt bei null zu beginnen; übersprungene Dateien
  bewegen den Balken mit.
- **Audio-Neukodierung konnte endlos laufen.** Ein Encoder mit
  Puffereingang kennt kein `signalEndOfInputStream` – ohne
  EOS-Puffer lieferte er nie ein Ende. Das Ende wird jetzt korrekt
  eingereiht, die Warteschleife auf einen freien Eingabepuffer prüft den
  Abbruch und gibt auf, statt Puffer zu verwerfen.
- **Der Encoder-Abschluss beim Video** pollte ohne Abbruchprüfung und
  ohne Frist; beides ist jetzt gesetzt (15 s), damit ein stiller Encoder
  den Dienst nicht samt Wake-Lock festhält.

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
- **Offene letzte Box** (Größe 0 = „bis Dateiende") wurde beim Anhängen
  des Markers nicht geschlossen: der Marker landete in der `mdat` und war
  danach unsichtbar, die Datei wäre beim nächsten Lauf erneut
  komprimiert worden. `walk` behält die 0 jetzt, `closeOpenEnded`
  schreibt die echte Größe — mit Test am Dateiende.

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
