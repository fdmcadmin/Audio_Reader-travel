package app.roadleaf.playback

/** Changes delivery only. Speaker identity and the installed voice ID stay fixed. */
enum class CharacterDelivery(val label: String) { FOLLOW_STORY("Follow the story"), NATURAL("Natural"), LOWER("Lower tone") }

object DeliveryCues {
    const val LOWER_PITCH = 0.92f
    const val VERBS = "said|saying|asked|replied|answered|whispered|shouted|murmured|called|thought|wondered|added|explained|continued|cried|yelled|remarked|told|exclaimed|responded|interrupted|spoke|ordered|roared|spat|laughed|chuckled|giggled|smiled|muttered|hissed|snapped|warned|observed|corrected|growled"
    private const val lower = "mockingly|menacingly|threateningly|scornfully|contemptuously|angrily|bitterly"
    private const val natural = "warmly|kindly|gently|reassuringly|apologetically|affectionately|gratefully"
    fun local(subject: String, before: String, after: String): Float? {
        if (subject.isBlank()) return null
        val tag = "(?:$subject)\\s+(?:(?:quietly|softly|calmly|$lower|$natural)\\s+){0,2}(?:$VERBS)(?:\\s+(?:$lower|$natural))?\\b"
        val found = Regex("^\\s*[,—–-]?\\s*($tag)", RegexOption.IGNORE_CASE).find(after)?.value
            ?: Regex("(?<![\\p{L}\\p{N}_])($tag)[^\"“”‘’'\\n.!?]{0,40}$", RegexOption.IGNORE_CASE).findAll(before).lastOrNull()?.groupValues?.get(1)
            ?: return null
        return when {
            Regex("\\b(?:$natural)\\b", RegexOption.IGNORE_CASE).containsMatchIn(found) -> 1f
            Regex("\\b(?:$lower)\\b", RegexOption.IGNORE_CASE).containsMatchIn(found) -> LOWER_PITCH
            else -> null
        }
    }

    /** Only direct named actions toward the confirmed MC count; generic sternness is not allegiance. */
    fun attitude(text: String, name: String, main: String): Int {
        if (name.isBlank() || main.isBlank()) return 0
        val target = main.split(',').map(String::trim).filter(String::isNotBlank).joinToString("|") { Regex.escape(it) }
        val subject = "(?<![\\p{L}\\p{N}_])${Regex.escape(name)}\\s+"
        val end = "\\s+(?:$target)(?![\\p{L}\\p{N}_])"
        val friendly = Regex(subject + "(?:apologized to|apologised to|thanked|helped|praised|reassured|defended|comforted|smiled warmly at|showed respect to)" + end, RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hostile = Regex(subject + "(?:mocked|threatened|bullied|taunted|humiliated|sneered at|glared at|scowled at)" + end, RegexOption.IGNORE_CASE).containsMatchIn(text)
        return when { friendly == hostile -> 0; friendly -> 1; else -> -1 }
    }
}
