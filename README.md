# Voice Nav for Hammerhead Karoo

Spoken turn-by-turn directions while you follow a route on a Karoo.

> "In a quarter mile, turn left." … "In 300 feet, turn left." … "Turn left now."

It also says **"Off route"** (after 8 seconds off course) and **"Back on route."**

## How it works (the short version)

The Karoo tells extensions *how far* away the next turn is, but not *which way* it goes.
So the app reads the route line itself, finds where that turn is on it, and measures the
change in direction there: small bend = "bear left", ~90° = "turn left", hairpin = "sharp left".

The voice is pre-recorded and built into the app, so it works even though the Karoo has no
text-to-speech engine. (If yours does, you can switch to it in settings.)

## Install on a Karoo 3 (no computer needed)

1. On your **phone**, open this repo's **Releases** page (right side of the repo home page).
2. Under the newest release, **long-press `voice-nav.apk`** and choose **Share**.
3. Pick the **Hammerhead** companion app. The phone says *Transferring*.
4. On the **Karoo**, tap **Install**.
5. Karoo must be on Wi-Fi, and Karoo/companion app must be up to date.

The direct link (works once the first build finishes):
`https://github.com/<your-github-name>/karoo-voice-nav/releases/latest/download/voice-nav.apk`

**Karoo 2:** the phone-share method above isn't supported. Install with a USB cable and
`adb install voice-nav.apk` instead.

## Set it up

1. Pair Bluetooth headphones or a speaker: **Karoo Settings → Bluetooth Audio**.
2. Open **Voice Nav** from the Karoo app list. Tap **Test voice**.
3. Pick which cues you want and units (defaults to your Karoo profile).
4. Start a route. You'll hear **"Voice directions on."** when navigation starts.

Optional: the extension adds **Repeat next turn** and **Voice on/off** actions that can be
assigned wherever the Karoo lets you map extension actions.

## Making changes

You never need to install anything on your computer. Edit a file on GitHub, commit to `main`,
and GitHub Actions builds a new APK and publishes it as a Release (about 3–5 minutes —
watch the **Actions** tab).

To publish an update the Karoo will offer to install, bump both numbers in
`app/build.gradle.kts`:

```kotlin
val appVersionCode = 2      // must always go up
val appVersionName = "1.0.1"
```

| File | What it does |
|---|---|
| `VoiceNavExtension.kt` | Listens to the Karoo, decides what to say |
| `CueEngine.kt` | When to speak (distances, off-route timing) |
| `TurnClassifier.kt` | Which way the turn goes, from the route geometry |
| `Speaker.kt` | Plays the voice |
| `MainActivity.kt` | Settings screen |
| `tools/make_voice_clips.py` | Records the voice clips during each build (edit the wording here) |

## Known limits

- Cue distances are fixed (¼ mi / 300 ft or 400 m / 100 m) because the voice is pre-recorded.
- Direction comes from route geometry, so a messy route file (GPS-traced, zig-zaggy) can give
  odd "bear left" calls. Routes drawn on roads (Ride with GPS, Komoot, Strava) are clean.
- Two turns very close together (under ~30 m apart) may get one combined call.
- Turn directions pause while you're off route.

## About the signing key in `signing/`

Android only installs an update if it's signed with the same key as the installed app. The key
in `signing/` keeps every build consistent. It's checked in for convenience, which is fine for a
tool used on your own Karoos. If this ever goes public-facing, add your own key as repo secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and they take priority.
