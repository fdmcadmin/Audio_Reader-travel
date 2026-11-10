package app.roadleaf.playback

import java.security.MessageDigest

data class OutputSelection(val key: String = "", val name: String = "")
data class OutputDevice(val key: String, val name: String, val kind: String, val id: Int)
data class OutputState(val selected: OutputSelection = OutputSelection(), val devices: List<OutputDevice> = emptyList(),
    val message: String = "System default / Android Auto", val reportedDevice: String = "", val confirmed: Boolean = false,
    val lastResult: String = "") {
    fun report(version: String) = "Roadleaf $version output test\nRequested: ${selected.name.ifBlank { "System default / Android Auto" }}\n" +
        "Android reported: ${reportedDevice.ifBlank { "Not observed during playback" }}\n" +
        "Live selected device matched: $confirmed\nLast output check: ${lastResult.ifBlank { "No test yet" }}\nStatus: $message\nPhone/Android Auto speaker separation: requires a parked listening check.\n" +
        "This report contains no book text, Bluetooth addresses or audio."
}

/** Device IDs can change on reconnect. Never guess between two matching endpoints. */
object AudioOutputPolicy {
    fun key(type: Int, address: String, name: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$type\u0000${address.trim()}\u0000${name.trim()}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun resolve(selection: OutputSelection, devices: List<OutputDevice>): OutputDevice? =
        devices.filter { it.key == selection.key }.singleOrNull()
    fun canStart(selection: OutputSelection, devices: List<OutputDevice>) = selection.key.isBlank() || resolve(selection, devices) != null
    fun matches(selection: OutputSelection, devices: List<OutputDevice>, routedId: Int?) =
        selection.key.isNotBlank() && routedId != null && resolve(selection, devices)?.id == routedId
}
