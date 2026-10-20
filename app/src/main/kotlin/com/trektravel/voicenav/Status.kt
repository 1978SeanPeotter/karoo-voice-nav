package com.trektravel.voicenav

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small "what is the background service doing?" log, shown live on the settings screen.
 * The service writes it; the settings screen reads it. Answers "is it even running?"
 * without needing a USB cable and adb.
 */
object Status {
    private const val FILE = "voicenav_status"
    private val clock = SimpleDateFormat("h:mm:ss a", Locale.US)

    fun set(context: Context, key: String, value: String) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(key, "$value  (${clock.format(Date())})").apply()
    }

    fun get(context: Context, key: String): String =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(key, null) ?: "nothing yet"

    /** Where sound would go right now, in plain words. */
    fun audioOutput(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == 26 /* TYPE_BLE_HEADSET */ || it.type == 27 /* TYPE_BLE_SPEAKER */
        }
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (bt.isNotEmpty()) {
            "Bluetooth: ${bt.first().productName}  •  volume $vol/$max"
        } else {
            val speaker = outs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            "NO Bluetooth audio connected" + (if (speaker) " (built-in speaker only)" else "") +
                "  •  volume $vol/$max"
        }
    }
}
