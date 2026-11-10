package app.roadleaf.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Android 8–11 call protection for headphones-with-car-music mode, so older phones keep the feature.
 * Only the call state and audio mode are read: never numbers, contacts, call history or microphone audio.
 * Android before 12 has no audio-mode callback, so calling apps' communication mode is checked every
 * two seconds, and only while this mode is on.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission") // Checked before registration and on every Play; failures close the mode.
class LegacyCallPauseMonitor(private val context: Context, private val changed: (CallProtection) -> Unit) : CallProtectionMonitor {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    // Phones without a mobile radio (Wi-Fi tablets, Chromebooks) can still have app calls via the audio mode.
    private var state: Int? = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) null else TelephonyManager.CALL_STATE_IDLE
    private var started = false
    private var closed = false
    private var listener: PhoneStateListener? = null
    private var failure = ""
    private var lastMode = AudioManager.MODE_NORMAL
    private val modeCheck = object : Runnable {
        override fun run() {
            if (closed) return
            val mode = runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL)
            if (mode != lastMode) { lastMode = mode; publish() }
            main.postDelayed(this, 2_000)
        }
    }
    override fun start() {
        if (started || closed) return
        started = true
        try {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            if (state == null) {
                // Created here, on the service's main thread, which the listener's callbacks use.
                val callStates = object : PhoneStateListener() {
                    override fun onCallStateChanged(value: Int, phoneNumber: String?) { if (!closed) { state = value; publish() } }
                }
                listener = callStates
                telephony.listen(callStates, PhoneStateListener.LISTEN_CALL_STATE)
            }
            lastMode = audio.mode
            main.postDelayed(modeCheck, 2_000)
        } catch (_: Exception) { failure = "Call monitoring could not start. Mode disabled; ordinary audio controls restored." }
        publish()
    }
    override fun current(): CallProtection {
        if (closed || !started) return CallProtection(error = "Call monitoring is not running.")
        if (failure.isNotBlank()) return CallProtection(error = failure)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return CallProtection(error = "Phone permission was removed. Mode disabled; ordinary audio controls restored.")
        return try {
            val call = state
            CallProtection(ready = call != null, active = (call != null && call != TelephonyManager.CALL_STATE_IDLE) ||
                audio.mode != AudioManager.MODE_NORMAL || telecom.isInCall)
        } catch (_: Exception) { CallProtection(error = "Call state could not be checked. Mode disabled; ordinary audio controls restored.") }
    }
    private fun publish() { if (!closed) changed(current()) }
    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(modeCheck)
        listener?.let { runCatching { telephony.listen(it, PhoneStateListener.LISTEN_NONE) } }
        listener = null
    }
}
