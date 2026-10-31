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


if __name__ == "__main__":
    main()
