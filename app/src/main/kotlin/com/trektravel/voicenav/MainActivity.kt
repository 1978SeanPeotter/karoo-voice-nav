package com.trektravel.voicenav

import android.app.Activity
import android.graphics.Color
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
import android.widget.Switch
import android.widget.TextView

/** Settings screen. Opened from the Karoo app drawer. No library UI, just plain Android views. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

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
        col.addView(text("Spoken turn-by-turn cues for routes on this Karoo. Version ${BuildConfig.VERSION_NAME}", 13f, Color.LTGRAY))

        status = text("", 13f, Color.rgb(140, 200, 255))
        col.addView(status)

        col.addView(header("Cues"))
        col.addView(toggle("Voice directions", settings.enabled) { settings.enabled = it })
        col.addView(toggle("Early warning (¼ mile / 400 m)", settings.farCue) { settings.farCue = it })
        col.addView(toggle("Approach (300 ft / 100 m)", settings.nearCue) { settings.nearCue = it })
        col.addView(toggle("Turn now", settings.nowCue) { settings.nowCue = it })
        col.addView(toggle("Off route / back on route", settings.offRouteCue) { settings.offRouteCue = it })
        col.addView(toggle("Only while recording a ride", settings.onlyWhileRecording) { settings.onlyWhileRecording = it })

        col.addView(header("Units"))
        col.addView(unitsPicker())

        col.addView(header("Voice"))
        col.addView(toggle("Use device text-to-speech (if installed)", settings.useSystemTts) {
            settings.useSystemTts = it
            speaker.useSystemTts = it
            refreshStatus()
        })
        col.addView(Button(this).apply {
            text = "Test voice"
            setOnClickListener { speaker.say(Phrases.TEST, urgent = true) }
        })
        col.addView(text(
            "Tip: pair Bluetooth headphones or a speaker in Karoo Settings → Bluetooth Audio. " +
                "Volume is set from the Karoo control center.\n\n" +
                "Optional: \"Repeat next turn\" and \"Voice on/off\" show up wherever the Karoo lets you assign extension actions.",
            13f, Color.LTGRAY,
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
        })
        refreshStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val voice = if (settings.useSystemTts) "Device TTS: ${speaker.ttsStatus}" else "Using built-in recorded voice"
        status.text = voice
        // TTS engines start asynchronously; check back for the result.
        if (settings.useSystemTts && speaker.ttsStatus == "starting") handler.postDelayed({ refreshStatus() }, 500)
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

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun header(s: String) = text(s, 15f, Color.rgb(255, 196, 0), bold = true).apply {
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.START
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
