#!/usr/bin/env python3
"""Erzeugt die Klickgeraeusche in app/src/main/res/raw/.

Warum synthetisch: die App hat kein INTERNET und darf keine fremden
Assets mitliefern. Ein Klick ist ein kurzer Einschwingvorgang eines
Koerpers -- genau das laesst sich in ein paar Zeilen erzeugen, und die
Dateien bleiben mit je ~4 KB winzig.

Klangbild: kein Sinus-Piep, sondern ein trockener Holz-Klick wie die
Menue-Klicks in Minecraft -- sehr kurzer Rausch-Transient plus zwei
schnell abklingende Partialtoene mit leichtem Tonhoehen-Fall (das macht
den "Koerper"-Eindruck statt "Beep").

Dazu ein Dauerton fuer die Dauer des Scans: kein Klick, sondern ein
leises Summen, das laeuft, solange gelesen wird. Er ist so gebaut, dass
die Schleife nahtlos ist (siehe [scan_hum]).

Aufruf: python3 tools/generate_click_sounds.py
"""
import math
import os
import random
import struct
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res", "raw")


def one_pole_lowpass(samples, cutoff_hz):
    """Einfacher Ein-Pol-Tiefpass gegen das harte Weiss-Rauschen."""
    alpha = 1.0 - math.exp(-2.0 * math.pi * cutoff_hz / RATE)
    out, y = [], 0.0
    for x in samples:
        y += alpha * (x - y)
        out.append(y)
    return out


def click(duration, partials, noise_amp, noise_ms, bend=0.06):
    """Ein Klick.

    partials: Liste von (frequenz_hz, amplitude, abklingzeit_s)
    noise_amp: Amplitude des Rausch-Transienten
    noise_ms: Laenge des Transienten
    bend: Tonhoehen-Fall ueber die Dauer (Anteil), gibt den Holz-Klang
    """
    total = int(RATE * duration)
    random.seed(20261031)
    noise_len = int(RATE * noise_ms / 1000.0)
    noise = one_pole_lowpass([random.uniform(-1.0, 1.0) for _ in range(noise_len)], 7000.0)
    out = []
    for i in range(total):
        t = i / RATE
        v = 0.0
        # Tonhoehe faellt leicht ab: erst der harte Aufschlag, dann Holz
        for freq, amp, tau in partials:
            phase = 2.0 * math.pi * freq * t * (1.0 - bend * min(1.0, t / duration))
            v += amp * math.exp(-t / tau) * math.sin(phase)
        if i < noise_len:
            v += noise_amp * noise[i] * math.exp(-(i / RATE) / 0.0025)
        out.append(v)
    return out


def overlapping(parts, total_s):
    """Mehrere Anschlaege in eine Spur legen.

    parts: Liste von (offset_ms, samples). Die Spur ist total_s lang; was
    ueber das Ende hinausragt, faellt weg. Damit laesst sich ein
    Zweifach-Anschlag bauen - derselbe Holzklick, nur zwei Mal kurz
    hintereinander, statt eines neuen Klangs.
    """
    out = [0.0] * int(RATE * total_s)
    for offset_ms, samples in parts:
        start = int(RATE * offset_ms / 1000.0)
        for i, x in enumerate(samples):
            target = start + i
            if target >= len(out):
                break
            out[target] += x
    return out


def normalize(samples, peak=0.89, fade_ms=0.4):
    """Ein- und Ausblenden, dann auf die Spitze normieren.

    Reihenfolge ist wichtig: erst blenden, dann normieren -- sonst
    frisst die Einblendung die Spitze des Rausch-Transienten und die
    drei Dateien landen auf verschiedenen Pegeln.
    """
    fade = int(RATE * fade_ms / 1000.0)
    faded = []
    for i, x in enumerate(samples):
        v = x
        if i < fade:
            v *= i / fade
        tail = len(samples) - 1 - i
        if tail < fade:
            v *= tail / fade
        faded.append(v)
    hi = max(1e-9, max(abs(x) for x in faded))
    return [x / hi * peak for x in faded]


def hum(partials, pulse_hz, duration_s=2.0, cutoff_hz=380.0):
    """Gemeinsamer Baukasten der beiden Dauertoene.

    Beide sind sinusfoermig aufgebaut, haben keinen Glanz (Tiefpass bei
    380 Hz) und sind nahtlos schleifenfaehig: alle Teiltoene und die
    Pulsfrequenz muessen ganzzahlige Vielfache von 1/duration sein, also
    von 0,5 Hz.
    """
    total = int(RATE * duration_s)
    out = []
    for i in range(total):
        t = i / RATE
        v = 0.0
        for freq, amp in partials:
            v += amp * math.sin(2.0 * math.pi * freq * t)
        # Puls: flacher als eine Huellkurve, damit der Ton steht statt zu
        # atmen. Eine Periode je Pulszyklus, ganzzahlig in der Schleife.
        v *= 0.85 + 0.15 * math.cos(2.0 * math.pi * pulse_hz * t)
        out.append(v)
    return lowpass_circular(out, cutoff_hz)


def scan_hum(duration_s=2.0):
    """Dumpfer Dauerton fuer die Dauer des **Scans**.

    Grundton D2 (73,5 Hz) mit 0,5-Hz-Schwebung, darueber die Oktave und
    die Quinte; nichts ueber 300 Hz. Der langsame Puls (0,5 Hz = eine
    Periode je Schleife) liest sich als ruhiges Arbeiten.
    """
    return hum(
        partials=[
            (73.5, 0.55),    # Grundton D2
            (74.0, 0.35),    # 0,5 Hz daneben -> Schwebung
            (147.0, 0.50),   # Oktave (traegt auf kleinen Lautsprechern)
            (220.5, 0.38),   # Quinte + Oktave
            (294.0, 0.14),   # kaum noch - Kante, nicht Glanz
        ],
        pulse_hz=0.5,
        duration_s=duration_s,
    )


def job_hum(duration_s=2.0):
    """Dumpfer Dauerton fuer einen **laufenden Auftrag**.

    Gleiche Bauart wie [scan_hum] - dieselben Verhaeltnisse, derselbe
    Tiefpass, derselbe Pegel - aber eine Quarte hoeher (G2, 98 Hz) und
    mit doppelt so schnellem Puls. Damit ist er im Vorbeigehoeren von
    der Scan-Tonspur zu unterscheiden, ohne aus der Familie zu fallen:
    zwei Toene derselben Maschine, einer liest, einer arbeitet.
    """
    return hum(
        partials=[
            (98.0, 0.55),    # Grundton G2
            (98.5, 0.35),    # 0,5 Hz daneben -> Schwebung
            (196.0, 0.50),   # Oktave
            (294.0, 0.30),   # Quinte
        ],
        pulse_hz=1.0,
        duration_s=duration_s,
    )


def lowpass_circular(samples, cutoff_hz):
    """Einpoliger Tiefpass, zirkulaer angewandt.

    Ein Filter ueber einen Puffer, der eine Schleife werden soll, muss
    selbst schleifenfest sein: an der Naht haette er sonst seinen
    Einschwingvorgang, und der klickt. Deshalb laeuft er einmal ueber den
    Puffer (Ergebnis verworfen - der Zustand ist danach auf dem
    stationaeren Wert) und erst der zweite Durchlauf wird behalten.
    """
    a = math.exp(-2.0 * math.pi * cutoff_hz / RATE)
    y = 0.0
    for x in samples:
        y = (1.0 - a) * x + a * y
    out = []
    for x in samples:
        y = (1.0 - a) * x + a * y
        out.append(y)
    return out


def normalize_loop(samples, rms=0.035):
    """Nur skalieren, nicht blenden - der Ton ist eine Schleife.

    Pegel ueber den Effektivwert statt ueber die Spitze: bei einem Ton,
    der eine Minute lang laeuft, bestimmt der Effektivwert die
    wahrgenommene Lautstaerke. Die Klicks sind auf ihre Spitze normiert
    (0,85-0,89); ein Dauerton mit demselben Effektivwert waere um
    Groessenordnungen lauter, weil er nicht nach 50 ms aufhoert.
    """
    current = math.sqrt(sum(x * x for x in samples) / max(1, len(samples)))
    return [x / max(1e-9, current) * rms for x in samples]


def write_wav(path, samples):
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", max(-32768, min(32767, int(round(x * 32767)))))
                               for x in samples))
    print(f"{os.path.basename(path)}: {len(samples)} Samples, "
          f"{os.path.getsize(path)} Bytes")


def main():
    os.makedirs(OUT, exist_ok=True)

    # Chip / kleiner Knopf: kurz, hell, trocken.
    write_wav(os.path.join(OUT, "click_tick.wav"), normalize(
        click(0.050,
              partials=[(1900.0, 1.0, 0.008), (3050.0, 0.45, 0.005), (980.0, 0.35, 0.014)],
              noise_amp=0.55, noise_ms=1.2)))

    # Schalter: etwas tiefer, laengerer Koerper.
    write_wav(os.path.join(OUT, "click_toggle.wav"), normalize(
        click(0.075,
              partials=[(1150.0, 1.0, 0.012), (2300.0, 0.40, 0.007), (620.0, 0.30, 0.020)],
              noise_amp=0.45, noise_ms=1.4)))

    # Hauptknopf (START): der groesste Koerper, mehr Tiefe.
    write_wav(os.path.join(OUT, "click_thunk.wav"), normalize(
        click(0.105,
              partials=[(720.0, 1.0, 0.016), (1440.0, 0.45, 0.009),
                        (300.0, 0.35, 0.030), (2100.0, 0.20, 0.005)],
              noise_amp=0.40, noise_ms=1.6)))

    # Scan beginnt: zwei Anschlaege derselben Familie, der zweite eine
    # Quinte hoeher und gleich laut. Der Anstieg ist die Aussage "jetzt
    # geht es los" - kein neuer Klang, nur eine Richtung.
    anlauf_a = click(0.070,
                     partials=[(880.0, 1.0, 0.011), (1760.0, 0.38, 0.006),
                               (440.0, 0.28, 0.018)],
                     noise_amp=0.45, noise_ms=1.3)
    anlauf_b = click(0.070,
                     partials=[(1320.0, 1.0, 0.010), (2640.0, 0.34, 0.006),
                               (660.0, 0.26, 0.016)],
                     noise_amp=0.40, noise_ms=1.2)
    write_wav(os.path.join(OUT, "scan_start.wav"),
              normalize(overlapping(
                  [(0.0, [x * 1.15 for x in anlauf_a]), (72.0, anlauf_b)],
                  0.18),
                        peak=0.85))

    # Scan fertig: ein einzelner Anschlag, tiefer als der Start und ohne
    # zweiten Schlag - das Ohr hoert "zu Ende", nicht "neu".
    write_wav(os.path.join(OUT, "scan_done.wav"), normalize(
        click(0.150,
              partials=[(560.0, 1.0, 0.022), (1120.0, 0.36, 0.011),
                        (840.0, 0.22, 0.014)],
              noise_amp=0.34, noise_ms=1.4),
        peak=0.85))

    # Dauer des Scans: laeuft, solange gelesen wird, und endet mit dem
    # Lesevorgang. Effektivwert 0,12 (-18 dBFS) - rund 10 dB lauter als
    # die erste Fassung. Der Spitzenwert bleibt bei etwa 0,35, damit der
    # Start-Klick (Spitze 0,85) beim gleichzeitigen Anlauf nicht in die
    # Begrenzung laeuft; App-seitig blendet der Ton ohnehin ein.
    for name, samples in (("scan_hum", scan_hum(2.0)), ("job_hum", job_hum(2.0))):
        loop = normalize_loop(samples, rms=0.12)
        write_wav(os.path.join(OUT, name + ".wav"), loop)
        peak = max(abs(x) for x in loop)
        seam = abs(loop[0] - loop[-1])
        step = max(abs(loop[i + 1] - loop[i]) for i in range(len(loop) - 1))
        crossings = sum(1 for i in range(1, len(loop)) if (loop[i - 1] < 0) != (loop[i] < 0))
        print(f"{name}: Spitze {peak:.3f} · Naht {seam:.5f} "
              f"(groesster Sprung {step:.5f}) · Nulldurchgaenge {crossings}")


if __name__ == "__main__":
    main()
