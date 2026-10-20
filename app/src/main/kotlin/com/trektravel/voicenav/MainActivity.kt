package com.trektravel.voicenav

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Settings screen. Opened from the Karoo app drawer. No library UI, just plain Android views. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var voiceStatus: TextView
    private lateinit var liveStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val green = Color.rgb(46, 125, 50)
    private val red = Color.rgb(198, 40, 40)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        speaker = Speaker(this)
        speaker.useSystemTts = settings.useSystemTts

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        col.addView(text("Voice Nav", 22f, bold = true))
        col.addView(text("Spoken turn-by-turn cues for routes. Version ${BuildConfig.VERSION_NAME}", 13f, Color.LTGRAY))

        col.addView(header("Cues"))
        col.addView(toggle("Voice directions", settings.enabled) { settings.enabled = it })
        col.addView(toggle("Early warning (¼ mile / 400 m)", settings.farCue) { settings.farCue = it })
        col.addView(toggle("Approach (300 ft / 100 m)", settings.nearCue) { settings.nearCue = it })
        col.addView(toggle("Turn now", settings.nowCue) { settings.nowCue = it })
        col.addView(toggle("Off route / back on route", settings.offRouteCue) { settings.offRouteCue = it })
        col.addView(toggle("Show cue text on ride screen", settings.showOnScreen) { settings.showOnScreen = it })
        col.addView(toggle("Only while recording a ride", settings.onlyWhileRecording) { settings.onlyWhileRecording = it })

        col.addView(header("Units"))
        col.addView(unitsPicker())

        col.addView(header("Voice"))
        col.addView(toggle("Use device text-to-speech (if installed)", settings.useSystemTts) {
            settings.useSystemTts = it
            speaker.useSystemTts = it
        })
        voiceStatus = text("", 13f, Color.rgb(140, 200, 255))
        col.addView(voiceStatus)
        col.addView(Button(this).apply {
            text = "Test voice"
            setOnClickListener { speaker.say(Phrases.TEST, urgent = true) }
        })

        col.addView(header("What the app is doing"))
        liveStatus = text("", 13f, Color.WHITE)
        col.addView(liveStatus)

        col.addView(text(
            "Voice needs Bluetooth headphones or a speaker: Karoo Settings → Bluetooth Audio. " +
                "Volume is the Bluetooth audio slider in the Karoo control center.\n\n" +
                "The beeps at turns come from the Karoo itself, not this app.",
            13f, Color.LTGRAY,
        ).apply { setPadding(0, dp(12), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
        })
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    /** Updates the status lines once a second while this screen is open. */
    private fun refresh() {
        voiceStatus.text = if (settings.useSystemTts) "Device TTS: ${speaker.ttsStatus}" else "Using built-in recorded voice"
        liveStatus.text = listOf(
            "Audio out: " + Status.audioOutput(this),
            "Service: " + Status.get(this, "service"),
            "Navigation: " + Status.get(this, "nav"),
            "Turn data: " + Status.get(this, "turn"),
            "Last cue: " + Status.get(this, "cue"),
            "Last sound: " + Status.get(this, "audio"),
        ).joinToString("\n\n")
        handler.postDelayed({ refresh() }, 1_000)
    }

    private fun unitsPicker(): View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf("auto" to "Match Karoo profile", "imperial" to "Imperial (feet / miles)", "metric" to "Metric (meters)")
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                isChecked = settings.units == value
            })
        }
        group.setOnCheckedChangeListener { _, checkedId -> settings.units = options[checkedId - 1000].first }
        return group
    }

    /** A setting row: label on the left, a big green ON / red OFF button on the right. Tap anywhere. */
    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        var state = value
        val pill = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        fun paint() {
            pill.text = if (state) "ON" else "OFF"
            pill.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (state) green else red)
            }
        }
        paint()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill)
            setOnClickListener {
                state = !state
                paint()
                onChange(state)
            }
        }
    }

    private fun header(s: String) = text(s, 15f, Color.rgb(255, 196, 0), bold = true).apply {
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
