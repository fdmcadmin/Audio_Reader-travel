package app.roadleaf.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow

/** Opt-in coexistence. Call-monitor readiness and an explicit connected headphone choice gate every Play. */
@UnstableApi
class HeadphoneMusicMode(private val context: Context, private val player: ExoPlayer,
    private val preferences: AudioOutputPreferences, private val output: () -> OutputState,
    private val state: MutableStateFlow<HeadphoneMusicState>, private val pause: (String) -> Unit,
    private val controlsChanged: () -> Unit,
    private val phonePermission: () -> Boolean = { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED },
    private val monitorFactory: ((CallProtection) -> Unit) -> CallProtectionMonitor = { callback ->
        if (Build.VERSION.SDK_INT >= 31) CallPauseMonitor(context, callback) else LegacyCallPauseMonitor(context, callback)
    }) {
    private var monitor: CallProtectionMonitor? = null
    val enabled get() = state.value.enabled
    fun start() { if (preferences.musicMode.value) setEnabled(true, restoring = true) }
    fun setEnabled(value: Boolean, restoring: Boolean = false): Boolean {
        if (value) {
            val route = output()
            val issue = if (restoring && route.selected.key.isNotBlank()) {
                if (!phonePermission()) "Phone permission is needed for call detection. Mode disabled." else null
            } else HeadphoneMusicPolicy.requirement(Build.VERSION.SDK_INT, phonePermission(), route.selected, route.devices)
            if (issue != null) { if (enabled || restoring) disable(issue) else state.value = state.value.copy(message = issue); return false }
        }
        pause("Audio mode changed • press Play to continue your book.")
        monitor?.close(); monitor = null
        preferences.saveMusicMode(value)
        state.value = HeadphoneMusicState(value, message = if (value) "Starting call detection • wait before pressing Play." else "Off • ordinary media interruptions still pause the book.")
        configure(value)
        if (value) {
            monitor = monitorFactory(::observeCalls)
            monitor?.start()
        }
        preferences.recordEvent(if (value) "Headphone music mode requested." else "Headphone music mode off.")
        controlsChanged()
        return enabled == value
    }
    private fun configure(value: Boolean) {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (value) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_UNKNOWN).build(), !value)
    }
    private fun disable(message: String) {
        pause(message); preferences.saveMusicMode(false)
        state.value = HeadphoneMusicState(message = message)
        monitor?.close(); monitor = null
        configure(false); preferences.recordEvent(message); controlsChanged()
    }
    private fun observeCalls(calls: CallProtection) {
        if (!enabled) return
        if (calls.error.isNotBlank()) { disable(calls.error); return }
        val previous = state.value.calls
        state.value = HeadphoneMusicState(true, calls, when {
            calls.active -> "Paused for a call or communication audio • press Play after it ends."
            !calls.ready -> "Checking call state • wait before pressing Play."
            else -> "Ready • car music keeps media focus; use Roadleaf or headset controls for your book."
        })
        if (!HeadphoneMusicPolicy.canPlay(calls) && player.playWhenReady) pause(state.value.message)
        if (calls.active && !previous.active) preferences.recordEvent("Call/communication state paused narration.")
    }
    fun allowPlayback(): Boolean {
        if (!enabled) return true
        val route = output()
        val issue = HeadphoneMusicPolicy.requirement(Build.VERSION.SDK_INT, phonePermission(), route.selected, route.devices)
        if (issue != null) {
            if (!phonePermission()) disable(issue) else { pause(issue); state.value = state.value.copy(message = issue) }
            return false
        }
        val calls = monitor?.current() ?: CallProtection(error = "Call detection is unavailable.")
        observeCalls(calls)
        return enabled && HeadphoneMusicPolicy.canPlay(calls)
    }
    fun close() { monitor?.close(); monitor = null }
}
