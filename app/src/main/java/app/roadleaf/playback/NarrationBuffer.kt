package app.roadleaf.playback

/** Extra character transitions need one additional staged file, not a larger disk budget. */
object NarrationBuffer {
    fun parts(mode: ResourceMode, profile: BookVoiceCast, available: List<String> = emptyList()): Int {
        val assigned = (listOf(profile.mainVoice, profile.maleVoice, profile.femaleVoice) + profile.characters.map { it.voice })
            .filter(String::isNotBlank).distinct()
        val multiple = assigned.size >= 2 || (profile.adaptive && available.distinct().size >= 2)
        return if (mode == ResourceMode.ECONOMY && profile.enabled && multiple) 3 else mode.lookahead
    }
}
