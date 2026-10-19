package com.trektravel.voicenav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/** One thing to say: the recorded clip name, and the same words for the TTS engine. */
data class Phrase(val clip: String, val text: String)

object Phrases {
    fun turn(tier: Tier, m: Maneuver, units: Units): Phrase {
        val u = if (units == Units.METRIC) "m" else "i"
        return when (tier) {
            Tier.NOW -> Phrase("cue_now_${m.key}", nowText(m))
            Tier.NEAR -> Phrase("cue_near_${u}_${m.key}", "${nearText(units)} ${m.phrase}.")
            Tier.FAR -> Phrase("cue_far_${u}_${m.key}", "${farText(units)} ${m.phrase}.")
        }
    }

    private fun farText(u: Units) = if (u == Units.METRIC) "In 400 meters," else "In a quarter mile,"
    private fun nearText(u: Units) = if (u == Units.METRIC) "In 100 meters," else "In 300 feet,"
    private fun nowText(m: Maneuver) = when (m) {
        Maneuver.LEFT -> "Turn left now."
        Maneuver.RIGHT -> "Turn right now."
        Maneuver.SLIGHT_LEFT -> "Bear left now."
        Maneuver.SLIGHT_RIGHT -> "Bear right now."
        Maneuver.SHARP_LEFT -> "Sharp left now."
        Maneuver.SHARP_RIGHT -> "Sharp right now."
        Maneuver.KEEP_LEFT -> "Keep left now."
        Maneuver.KEEP_RIGHT -> "Keep right now."
        Maneuver.UTURN -> "Make a U-turn now."
        Maneuver.STRAIGHT -> "Continue straight."
        Maneuver.UNKNOWN -> "Turn now."
    }

    val OFF_ROUTE = Phrase("s_off_route", "Off route.")
    val ON_ROUTE = Phrase("s_on_route", "Back on route.")
    val READY = Phrase("s_ready", "Voice directions on.")
    val TEST = Phrase("s_test", "Voice directions are working. Turn left now.")
}

/**
 * Plays phrases one after another. Two voices:
 *  - RECORDED: clips bundled in the app. Always works, no voice engine needed.
 *  - SYSTEM: Android text-to-speech, if the Karoo has an engine installed.
 * If SYSTEM is chosen but no engine exists, it quietly falls back to RECORDED.
 */
class Speaker(private val context: Context) {
    companion object {
        const val TAG = "VoiceNav"
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    private val queue = ArrayDeque<Phrase>()
    private var player: MediaPlayer? = null
    private var busy = false

    private var tts: TextToSpeech? = null
    @Volatile var ttsReady = false
        private set
    @Volatile var ttsStatus = "not started"
        private set

    var useSystemTts = false
        set(value) {
            field = value
            if (value) main.post { ensureTts() }
        }

    /** Queue a phrase. Safe from any thread. Urgent phrases jump the queue. */
    fun say(p: Phrase, urgent: Boolean = false) = main.post {
        if (urgent) queue.clear()
        while (queue.size >= 2) queue.pollFirst() // never fall far behind reality
        queue.addLast(p)
        if (!busy) playNext()
    }

    private fun playNext() {
        val p = queue.pollFirst()
        if (p == null) {
            busy = false
            audio.abandonAudioFocusRequest(focusRequest)
            return
        }
        busy = true
        audio.requestAudioFocus(focusRequest)
        if (useSystemTts && ttsReady) speakTts(p) else playClip(p)
    }

    private fun playClip(p: Phrase) {
        val resId = context.resources.getIdentifier(p.clip, "raw", context.packageName)
        if (resId == 0) {
            Log.w(TAG, "Missing clip ${p.clip}")
            if (ttsReady) speakTts(p) else playNext()
            return
        }
        try {
            player?.release()
            val afd = context.resources.openRawResourceFd(resId)
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; playNext() }
            mp.setOnErrorListener { m, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra on ${p.clip}")
                m.release(); if (player === m) player = null; playNext(); true
            }
            mp.prepare()
            player = mp
            mp.start()
            Log.i(TAG, "Saying (clip): ${p.text}")
        } catch (e: Exception) {
            Log.e(TAG, "Clip playback failed", e)
            playNext()
        }
    }

    private fun speakTts(p: Phrase) {
        val engine = tts ?: return playClip(p)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        val r = engine.speak(p.text, TextToSpeech.QUEUE_ADD, params, "vn-${System.nanoTime()}")
        Log.i(TAG, "Saying (tts): ${p.text}")
        if (r != TextToSpeech.SUCCESS) playClip(p)
    }

    private fun ensureTts() {
        if (tts != null) return
        ttsStatus = "starting"
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS || engine.engines.isEmpty()) {
                    ttsStatus = "no voice engine on this device - using recorded voice"
                    ttsReady = false
                    return@post
                }
                engine.setAudioAttributes(attrs)
                val lang = engine.setLanguage(Locale.US)
                ttsReady = lang >= TextToSpeech.LANG_AVAILABLE
                ttsStatus = if (ttsReady) "system voice ready (${engine.defaultEngine})"
                else "system voice has no English - using recorded voice"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { playNext() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { playNext() } }
                })
            }
        }
    }

    fun shutdown() = main.post {
        queue.clear()
        player?.release(); player = null
        tts?.shutdown(); tts = null
        ttsReady = false
        audio.abandonAudioFocusRequest(focusRequest)
    }
}
