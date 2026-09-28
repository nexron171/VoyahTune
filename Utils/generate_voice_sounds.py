#!/usr/bin/env python3
"""Generate original, short ambient UI cues; standard library only, deterministic PCM."""
import math
from pathlib import Path
import struct
import wave

RATE = 44100
OUT = Path(__file__).resolve().parents[1] / "RestoreMode/app/src/main/res/raw"


def render(name, duration, notes):
    samples = [0.0] * round(duration * RATE)
    for start, frequency, length, gain in notes:
        for n in range(round(length * RATE)):
            pos = round(start * RATE) + n
            if pos >= len(samples):
                break
            t = n / RATE
            attack = 1 - math.exp(-t / .025)
            release = max(0, 1 - t / length) ** 2
            phase = 2 * math.pi * frequency * t
            # Soft detuned sine body with a faint, decaying glass overtone.
            tone = (.70 * math.sin(phase + .08 * math.sin(phase * 2.01) * math.exp(-t * 9))
                    + .18 * math.sin(phase * 1.003)
                    + .10 * math.sin(phase * .997)
                    + .025 * math.sin(phase * 3.01) * math.exp(-t * 12))
            samples[pos] += gain * attack * release * tone
    dry = samples[:]
    for delay, gain in ((.073, .13), (.137, .08), (.211, .045)):
        offset = round(delay * RATE)
        for i in range(offset, len(samples)):
            samples[i] += dry[i - offset] * gain
    peak = max(abs(v) for v in samples) or 1
    for i in range(len(samples)):
        tail = min(1, (len(samples) - 1 - i) / (RATE * .10))
        samples[i] = samples[i] / peak * .36 * max(0, tail)
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / f"voice_{name}.wav"), "wb") as out:
        out.setparams((1, 2, RATE, len(samples), "NONE", "not compressed"))
        out.writeframes(b"".join(struct.pack("<h", round(v * 32767)) for v in samples))
    print(f"voice_{name}.wav: {duration:.2f}s, mono PCM16, peak -8.9 dBFS")


if __name__ == "__main__":
    render("activation", .70, [(0, 739.99, .44, .8), (.12, 1108.73, .42, .48)])
    render("success", .95, [(0, 739.99, .64, .65), (.10, 932.33, .58, .45),
                            (.19, 1108.73, .56, .38)])
    render("error", .85, [(0, 622.25, .48, .65), (.17, 554.37, .48, .55)])
