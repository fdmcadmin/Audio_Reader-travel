package app.roadleaf.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Only call states and audio mode, never numbers, contacts, call history or microphone audio. */
@RequiresApi(31)
@SuppressLint("MissingPermission") // Checked before registration and every Play; exceptions fail closed.
class CallPauseMonitor(private val context: Context, private val changed: (CallProtection) -> Unit) : CallProtectionMonitor {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val callbacks = mutableMapOf<Int, Pair<TelephonyManager, TelephonyCallback>>()
    private val states = mutableMapOf<Int, Int?>()
    private var started = false
    private var closed = false
    private var failure = ""
    private var modeRegistered = false
    private var subscriptionsRegistered = false
    private val modeListener = AudioManager.OnModeChangedListener { publish() }
    private val subscriptionListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() { if (!closed) rebuild() }
    }
    override fun start() {
        if (started || closed) return
        started = true
        try {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            audio.addOnModeChangedListener(context.mainExecutor, modeListener); modeRegistered = true
            subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionListener); subscriptionsRegistered = true
            rebuild()
        } catch (_: Exception) { failure = "Call monitoring could not start. Mode disabled; ordinary audio controls restored."; publish() }
    }
    private fun rebuild() {
        if (closed) return
        try {
            val ids = subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet()
            for (id in callbacks.keys.toSet() - ids) {
                callbacks.remove(id)?.let { (manager, callback) -> manager.unregisterTelephonyCallback(callback) }; states.remove(id)
            }
            for (id in ids - callbacks.keys) {
                val manager = telephony.createForSubscriptionId(id)
                states[id] = null
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (!closed && callbacks[id]?.second === this) { states[id] = state; publish() }
                    }
                }
                callbacks[id] = manager to callback
                manager.registerTelephonyCallback(context.mainExecutor, callback)
            }
        } catch (_: Exception) { failure = "Call monitoring is unavailable. Mode disabled; ordinary audio controls restored." }
        publish()
    }
    override fun current(): CallProtection {
        if (closed || !started) return CallProtection(error = "Call monitoring is not running.")
        if (failure.isNotBlank()) return CallProtection(error = failure)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return CallProtection(error = "Phone permission was removed. Mode disabled; ordinary audio controls restored.")
        return try {
            CallProtection(ready = states.values.none { it == null }, active = telecom.isInCall ||
                states.values.any { it != null && it != TelephonyManager.CALL_STATE_IDLE } || audio.mode != AudioManager.MODE_NORMAL)
        } catch (_: Exception) { CallProtection(error = "Call state could not be checked. Mode disabled; ordinary audio controls restored.") }
    }
    private fun publish() { if (!closed) changed(current()) }
    override fun close() {
        if (closed) return
        closed = true
        if (modeRegistered) audio.removeOnModeChangedListener(modeListener)
        if (subscriptionsRegistered) subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener)
        callbacks.values.forEach { (manager, callback) -> runCatching { manager.unregisterTelephonyCallback(callback) } }
        callbacks.clear(); states.clear()
    }
}
