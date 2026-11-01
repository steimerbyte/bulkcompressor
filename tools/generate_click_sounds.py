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


def scan_hum(duration_s=2.0):
    """Dauerton fuer die Dauer des Scans - absichtlich nahtlos.

    Damit eine Schleife nicht klickt, muss die letzte Probe exakt an die
    erste anschliessen. Das geht hier ohne jede Blende, weil **alle**
    Teiltoene ganzzahlige Vielfache von 1/duration sind: bei 2 s also
    Vielfache von 0,5 Hz. 110 Hz sind 220 Perioden, 110,5 Hz sind 221 -
    die Schwebung von 0,5 Hz ist damit Teil der Schleife und nicht ihr
    Ende. Auch die Huellkurven (0,5 Hz und 3 Hz) gehen ganzzahlig auf.

    Ein Sinus-Piep waere billig, klingt aber nach Messtechnik; das
    Klangbild hier ist ein leises Summen mit langsamem Atmen und einem
    feinen Schimmer darueber - eine Maschine, die arbeitet.

    Kein Ein- und Ausblenden: [normalize] blendet an beiden Enden, und
    genau das wuerde die Naht zerstueckeln. Deshalb skaliert
    [normalize_loop] nur.
    """
    total = int(RATE * duration_s)
    partials = [
        (110.0, 0.50),   # Grundton A2
        (110.5, 0.30),   # 0,5 Hz daneben -> Schwebung
        (165.0, 0.18),   # Quinte
        (220.0, 0.12),   # Oktave
        (330.0, 0.06),
    ]
    out = []
    for i in range(total):
        t = i / RATE
        v = 0.0
        for freq, amp in partials:
            v += amp * math.sin(2.0 * math.pi * freq * t)
        # Atmen: eine Periode je Schleife.
        v *= 0.75 + 0.25 * math.cos(2.0 * math.pi * 0.5 * t)
        # Schimmer, damit es nicht nach Netzbrummen klingt.
        v += 0.030 * math.sin(2.0 * math.pi * 1320.0 * t) * (
            0.5 + 0.5 * math.sin(2.0 * math.pi * 3.0 * t))
        out.append(v)
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
    # Lesevorgang. Effektivwert 0,035 (-29 dBFS) - hoerbar, aber deutlich
    # unter den Klicks; er soll Zustand melden, nicht beschallen.
    hum = normalize_loop(scan_hum(2.0), rms=0.035)
    write_wav(os.path.join(OUT, "scan_hum.wav"), hum)
    peak = max(abs(x) for x in hum)
    seam = abs(hum[0] - hum[-1])
    step = max(abs(hum[i + 1] - hum[i]) for i in range(len(hum) - 1))
    print(f"scan_hum: Spitze {peak:.3f} · Naht {seam:.5f} "
          f"(groesster Sprung im Ton {step:.5f})")


if __name__ == "__main__":
    main()
