package app.roadleaf.playback

/** A user-installed engine can exist before Android has any default TTS engine. */
internal object SpeechEngineSelection {
    fun select(requested: String, systemDefault: String?, installed: List<String>): String {
        require(installed.isNotEmpty()) { "No speech engine installed. Install an offline text-to-speech engine, then refresh voices." }
        if (requested.isNotBlank()) {
            require(requested in installed) { "This speech engine is unavailable. Choose an installed engine in Voice settings." }
            return requested
        }
        return systemDefault?.takeIf { it in installed } ?: installed.first()
    }
}
