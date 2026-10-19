"""Pre-record the spoken cues bundled in the app (app/src/main/res/raw).

Why pre-record instead of using Android's built-in text-to-speech?
Because the Karoo isn't a phone, and a voice engine may not be installed.
A sound file always plays. The app can still use system TTS if you pick it.

Usage: python tools/make_voice_clips.py <kokoro.onnx> <voices.bin> <out_dir>
Needs: pip install kokoro-onnx soundfile ; ffmpeg on PATH
"""
import os, subprocess, sys, tempfile
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
        with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
            sf.write(tmp.name, samples, rate)
            # Loud and clear for wind noise; mono Vorbis keeps the APK small.
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name,
                            "-af", "silenceremove=start_periods=1:start_threshold=-45dB,"
                                   "areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,"
                                   "loudnorm=I=-14:TP=-1.0:LRA=7,apad=pad_dur=0.15",
                            "-ac", "1", "-ar", "24000", "-c:a", "libvorbis", "-q:a", "4",
                            os.path.join(out, name + ".ogg")], check=True)
        print(name, "->", text)

if __name__ == "__main__":
    main(*sys.argv[1:4])
