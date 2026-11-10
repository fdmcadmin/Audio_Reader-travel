package app.roadleaf.playback

import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/** Only Roadleaf's player is routed/observed. No Bluetooth scan, SCO, global route or other-app access. */
@UnstableApi
@SuppressLint("InlinedApi") // Integral device-type comparisons are safe on older Android releases.
class AudioOutputRouting(context: Context, private val preferences: AudioOutputPreferences,
    private val state: MutableStateFlow<OutputState>, private val blocked: (String) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var track = WeakReference<AudioTrack>(null)
    private var closed = false
    private var appliedId: Int? = null
    private var appliedKey: String? = null
    private var verify: Runnable? = null
    private var devices = emptyList<Pair<AudioDeviceInfo, OutputDevice>>()
    private var reason = ""
    private val routingListener = AudioRouting.OnRoutingChangedListener { source ->
        if (!closed && source === track.get()) inspect()
    }
    private val deviceListener = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh() }
    }
    fun start(value: ExoPlayer) {
        player = value
        manager.registerAudioDeviceCallback(deviceListener, handler)
        refresh()
    }
    private fun describe(device: AudioDeviceInfo): OutputDevice {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headphones"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            else -> "Audio output"
        }
        val name = if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Phone speaker" else device.productName.toString().take(120).ifBlank { kind }
        val address = if (Build.VERSION.SDK_INT >= 28) device.address else ""
        return OutputDevice(AudioOutputPolicy.key(device.type, address, name), name, kind, device.id)
    }
    fun refresh() {
        if (closed) return
        devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink && it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }.map { it to describe(it) }
        val selected = preferences.selection.value
        val target = AudioOutputPolicy.resolve(selected, devices.map { it.second })
        if (appliedKey != selected.key || appliedId != target?.id) {
            appliedKey = selected.key; appliedId = target?.id
            if (selected.key.isBlank()) player?.setPreferredAudioDevice(null)
            else if (target != null) player?.setPreferredAudioDevice(devices.firstOrNull { it.second.id == target.id }?.first)
        }
        if (!AudioOutputPolicy.canStart(selected, devices.map { it.second })) {
            reason = "Reconnect ${selected.name.ifBlank { "the selected output" }}, or choose System default."
            preferences.observe("Selected output unavailable: ${selected.name}.")
            if (player?.playWhenReady == true) blocked(reason)
        } else if (reason.startsWith("Reconnect")) reason = ""
        publish()
    }
    fun select(key: String) {
        refresh()
        val selected = if (key.isBlank()) OutputSelection() else {
            val device = devices.map { it.second }.filter { it.key == key }.singleOrNull()
                ?: error("That output is no longer available. Connect your headphones and refresh.")
            OutputSelection(device.key, device.name)
        }
        blocked("Audio output changed • press Play or test the selected output.")
        preferences.save(selected); reason = ""; refresh()
    }
    fun allowPlayback(): Boolean {
        refresh()
        if (!AudioOutputPolicy.canStart(preferences.selection.value, devices.map { it.second })) {
            blocked(reason); return false
        }
        reason = ""; return true
    }
    /** Called by Media3's standard AudioTrack provider, not by inspecting another app's playback. */
    fun observe(value: AudioTrack) {
        handler.post {
            if (!closed) {
                track.get()?.let { runCatching { it.removeOnRoutingChangedListener(routingListener) } }
                track = WeakReference(value)
                value.addOnRoutingChangedListener(routingListener, handler)
                inspect()
            }
        }
    }
    fun playingChanged(playing: Boolean) {
        verify?.let(handler::removeCallbacks); verify = null
        if (playing) {
            inspect()
            val check = Runnable { inspect(expired = true) }
            verify = check; handler.postDelayed(check, 1500)
        } else publish()
    }
    private fun inspect(expired: Boolean = false) {
        if (closed) return
        val selected = preferences.selection.value
        val routed = runCatching { track.get()?.routedDevice }.getOrNull()
        if (selected.key.isNotBlank() && player?.isPlaying == true) {
            if (routed != null && !AudioOutputPolicy.matches(selected, devices.map { it.second }, routed.id)) {
                preferences.observe("Android reported ${describe(routed).name}; requested ${selected.name}; matched=false.")
                reason = "Android routed narration elsewhere. Paused • reconnect or choose System default."
                blocked(reason)
            } else if (routed == null && expired) {
                preferences.observe("Android did not confirm the requested ${selected.name} output.")
                reason = "Android did not confirm the selected output. Paused • retry while parked."
                blocked(reason)
            }
        }
        publish()
    }
    private fun publish() {
        val selected = preferences.selection.value
        val routed = if (player?.isPlaying == true) runCatching { track.get()?.routedDevice }.getOrNull() else null
        val confirmed = AudioOutputPolicy.matches(selected, devices.map { it.second }, routed?.id)
        val reported = routed?.let { describe(it).name }.orEmpty()
        if (reported.isNotBlank()) preferences.observe("Android reported $reported; " +
            if (selected.key.isBlank()) "system default selected." else "requested ${selected.name}; matched=$confirmed.")
        state.value = OutputState(selected, devices.map { it.second }.distinctBy { it.key },
            reason.ifBlank { if (selected.key.isBlank()) "System default / Android Auto" else if (confirmed) "Android reports the selected output." else "Selected output requested • use Test output while parked." },
            reported, confirmed, preferences.lastResult.value)
    }
    fun close() {
        closed = true; verify?.let(handler::removeCallbacks)
        manager.unregisterAudioDeviceCallback(deviceListener)
        track.get()?.let { runCatching { it.removeOnRoutingChangedListener(routingListener) } }; track.clear(); player = null
    }
}
