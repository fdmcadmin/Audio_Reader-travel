package app.roadleaf.playback

data class CallProtection(val ready: Boolean = false, val active: Boolean = false, val error: String = "")
data class HeadphoneMusicState(val enabled: Boolean = false, val calls: CallProtection = CallProtection(),
    val message: String = "Off • ordinary media interruptions still pause the book.") {
    fun report() = "Headphones with car music: $enabled\nCall monitoring ready: ${calls.ready}; call active: ${calls.active}\nMode status: $message\n"
}

object HeadphoneMusicPolicy {
    fun headphones(selection: OutputSelection, devices: List<OutputDevice>) =
        AudioOutputPolicy.resolve(selection, devices)?.kind in setOf("Bluetooth media", "Bluetooth LE headphones", "Hearing aid", "Wired headphones", "USB audio")
    fun requirement(sdk: Int, permission: Boolean, selection: OutputSelection, devices: List<OutputDevice>): String? = when {
        sdk < 26 -> "This mode requires Android 8 or later."
        !permission -> "Allow Phone permission for call detection before enabling this mode."
        !headphones(selection, devices) -> "Connect and select your headphones by name first."
        else -> null
    }
    fun carController(packageName: String?) = packageName in setOf("com.google.android.projection.gearhead", "com.google.android.gms")
    fun blocksCarController(enabled: Boolean, packageName: String?) = enabled && carController(packageName)
    fun canPlay(calls: CallProtection) = calls.ready && !calls.active && calls.error.isBlank()
}

interface CallProtectionMonitor {
    fun start()
    fun current(): CallProtection
    fun close()
}
