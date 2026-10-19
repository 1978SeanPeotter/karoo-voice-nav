"""Pre-record the spoken cues bundled in the app (app/src/main/res/raw).

Why pre-record instead of using Android's built-in text-to-speech?
Because the Karoo isn't a phone, and a voice engine may not be installed.
A sound file always plays. The app can still use system TTS if you pick it.

Usage: python tools/make_voice_clips.py <kokoro.onnx> <voices.bin> <out_dir>
Needs: pip install kokoro-onnx soundfile
"""
import os, sys
import numpy as np
import soundfile as sf
from kokoro_onnx import Kokoro

MANEUVERS = {
    "left": "turn left", "right": "turn right",
    "slight_left": "bear left", "slight_right": "bear right",
    "sharp_left": "make a sharp left", "sharp_right": "make a sharp right",
    "keep_left": "keep left", "keep_right": "keep right",
    "uturn": "make a U-turn", "straight": "continue straight",
    "unknown": "turn ahead",
}
NOW = {
    "left": "Turn left now.", "right": "Turn right now.",
    "slight_left": "Bear left now.", "slight_right": "Bear right now.",
    "sharp_left": "Sharp left now.", "sharp_right": "Sharp right now.",
    "keep_left": "Keep left now.", "keep_right": "Keep right now.",
    "uturn": "Make a U-turn now.", "straight": "Continue straight.",
    "unknown": "Turn now.",
}
DISTANCES = {
    "far_m": "In 400 meters,", "near_m": "In 100 meters,",
    "far_i": "In a quarter mile,", "near_i": "In 300 feet,",
}
STATUS = {
    "s_off_route": "Off route.",
    "s_on_route": "Back on route.",
    "s_ready": "Voice directions on.",
    "s_test": "Voice directions are working. Turn left now.",
}

def phrases():
    for d, dtext in DISTANCES.items():
        for m, mtext in MANEUVERS.items():
            yield f"cue_{d}_{m}", f"{dtext} {mtext}."
    for m, text in NOW.items():
        yield f"cue_now_{m}", text
    yield from STATUS.items()

def main(model, voices, out):
    os.makedirs(out, exist_ok=True)
    k = Kokoro(model, voices)
    for name, text in phrases():
        samples, rate = k.create(text, voice="af_heart", speed=1.05, lang="en-us")
        # Trim leading/trailing silence so the cue starts the instant it's triggered.
        loud = np.flatnonzero(np.abs(samples) > 10 ** (-45 / 20))
        if loud.size:
            samples = samples[loud[0]:loud[-1] + 1]
        # Loud and clear for wind noise: peak at -1 dBFS, then a short tail of silence.
        samples = samples / max(np.max(np.abs(samples)), 1e-6) * 10 ** (-1 / 20)
        samples = np.concatenate([samples, np.zeros(int(rate * 0.15), dtype=samples.dtype)])
        # Mono Ogg Vorbis keeps the APK small and plays on any Android.
        sf.write(os.path.join(out, name + ".ogg"), samples, rate, format="OGG", subtype="VORBIS")
        print(name, "->", text)

if __name__ == "__main__":
    main(*sys.argv[1:4])
