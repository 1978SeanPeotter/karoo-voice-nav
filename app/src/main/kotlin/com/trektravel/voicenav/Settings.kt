package com.trektravel.voicenav

import android.content.Context
import android.content.SharedPreferences

/** User settings, stored on the Karoo. Read fresh each time so changes apply mid-ride. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("voicenav", Context.MODE_PRIVATE)

    var enabled by bool("enabled", true)
    var farCue by bool("far_cue", true)
    var nearCue by bool("near_cue", true)
    var nowCue by bool("now_cue", true)
    var offRouteCue by bool("off_route_cue", true)
    var onlyWhileRecording by bool("only_recording", false)
    var useSystemTts by bool("system_tts", false)

    /** "auto" follows the Karoo profile; or "metric" / "imperial". */
    var units: String
        get() = prefs.getString("units", "auto") ?: "auto"
        set(v) = prefs.edit().putString("units", v).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    private fun bool(key: String, default: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
                prefs.getBoolean(key, default)

            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
                prefs.edit().putBoolean(key, value).apply()
        }
}
