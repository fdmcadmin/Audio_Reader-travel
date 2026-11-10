package app.roadleaf

import app.roadleaf.importing.CastFiles
import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterDelivery
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.LearnedCast
import app.roadleaf.playback.LearnedCharacter

/** Cast files on the real Android JSON runtime: lorebooks, cards and the export round trip. Original fixtures only. */
class CastFileChecks(private val app: RoadleafApp, private val report: MutableList<String>) {
    private val secret = "keeps a brass compass that her mother gave her"
    private val novelAi = """{"lorebookVersion":5,"entries":[
        {"id":"e-mara","displayName":"Mara Vell","keys":["Mara","Vell","/mara/i"],"text":"Mara Vell grew up on the night ferries. She $secret, and she trusts it more than any map.","category":"cat-people","enabled":true},
        {"id":"e-tobin","displayName":"Tobin Ashe","keys":["Tobin"],"text":"[ Tobin Ashe: gender: male; age: 30; occupation: carpenter ]","category":"","enabled":true},
        {"id":"e-harbour","displayName":"Harbour of Glass","keys":["Harbour"],"text":"A port city with glass towers.","category":"cat-places","enabled":true},
        {"id":"e-off","displayName":"Iris Quell","keys":["Iris"],"text":"Gender: female","category":"cat-people","enabled":false}],
        "categories":[{"id":"cat-people","name":"Characters"},{"id":"cat-places","name":"Places"}]}"""
    private val scenario = """{"scenarioVersion":3,"prompt":"An original fixture.","lorebook":$novelAi}"""
    private val worldInfo = """{"entries":{"0":{"uid":0,"key":["Wren","Captain Wren"],"comment":"Wren Hale","content":"Wren Hale commands the ferry. Gender: female. Age: 41.","disable":false},
        "1":{"uid":1,"key":["Salt Road"],"comment":"The Salt Road","content":"A trade route along the coast.","disable":false}}}"""
    private val card = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Juniper","description":"Juniper is a lighthouse keeper. She writes letters to herself and she sings to her lamp.",
        "personality":"","scenario":"","first_mes":"Hello.","character_book":{"entries":[{"keys":["Oskar"],"content":"Oskar is her brother. He fixes boats and he whistles while he works. Gender: male.","comment":"Oskar","enabled":true}]}}}"""

    fun run() {
        val empty = BookVoiceCast(enabled = false)
        val fromNovelAi = CastFiles.read(novelAi, empty)
        check(fromNovelAi.kind == CastFiles.Kind.NOVELAI && fromNovelAi.cast.characters.map { it.name } == listOf("Mara Vell", "Tobin Ashe")) { "NovelAI characters: ${fromNovelAi.cast.characters}" }
        val mara = fromNovelAi.cast.characters[0]
        check(mara.pronoun == CharacterPronoun.SHE && mara.aliases == "Mara,Vell" && mara.profileId == "e-mara") { "Mara: $mara" }
        check(fromNovelAi.cast.characters[1].pronoun == CharacterPronoun.HE && fromNovelAi.cast.enabled && fromNovelAi.cast.sceneOnly)
        check(CastFiles.read(scenario, empty).cast.characters.map { it.name } == listOf("Mara Vell", "Tobin Ashe"))
        report.add("PASS a NovelAI lorebook, also inside a scenario file, yields only its enabled characters with nicknames and pronoun hints; places and disabled entries are skipped")

        val key = "https://tales.example/cast-check"
        val preferences = app.getSharedPreferences("voice-cast", 0)
        try {
            app.casts.save(key, fromNovelAi.cast)
            val stored = preferences.getString(key, null).orEmpty()
            check(stored.isNotBlank() && secret !in stored && "ferries" !in stored) { "Lorebook text was stored" }
            report.add("PASS the saved cast keeps no lorebook text")

            val fromWorldInfo = CastFiles.read(worldInfo, empty)
            check(fromWorldInfo.kind == CastFiles.Kind.LOREBOOK && fromWorldInfo.cast.characters.single().let {
                it.name == "Wren Hale" && it.pronoun == CharacterPronoun.SHE && it.aliases == "Wren,Captain Wren"
            }) { "Lorebook entries: ${fromWorldInfo.cast.characters}" }
            val fromCard = CastFiles.read(card, empty)
            check(fromCard.kind == CastFiles.Kind.CARD && fromCard.cast.characters.map { it.name to it.pronoun } ==
                listOf("Juniper" to CharacterPronoun.SHE, "Oskar" to CharacterPronoun.HE)) { "Card: ${fromCard.cast.characters}" }
            report.add("PASS lorebooks in the common entries form and character cards with their own lorebook are read the same way")

            val learned = LearnedCast(3, "Mara Vell", listOf(LearnedCharacter("Pell", turns = 6, windows = 3, maleCues = 3, voice = "voice-b", engine = "engine")))
            val chosen = fromNovelAi.cast.copy(mainCharacter = "Mara Vell, Mara", mainVoice = "voice-a", mainPronoun = CharacterPronoun.SHE, narratorVoice = "voice-n",
                characters = fromNovelAi.cast.characters.map { if (it.name == "Tobin Ashe") it.copy(role = CastRole.CUSTOM, voice = "voice-t", delivery = CharacterDelivery.LOWER) else it },
                chapterViewpoints = mapOf("$key/chapter-2" to "Tobin Ashe")).validated()
            val back = CastFiles.read(CastFiles.export(chosen, learned, "Tales", "engine", "en-US", "0.3.19"), BookVoiceCast())
            check(back.kind == CastFiles.Kind.CAST && back.engine == "engine")
            check(back.cast.copy(characters = back.cast.characters.filter { it.name != "Pell" }) == chosen) { "Round trip changed the cast: ${back.cast}" }
            check(back.cast.characters.any { it.name == "Pell" && it.voice == "voice-b" && it.pronoun == CharacterPronoun.HE && it.role == CastRole.CUSTOM })
            report.add("PASS an exported cast imports back unchanged, with voices, delivery, viewpoint choices and learned voices")

            check(runCatching { CastFiles.read("""{"hello":1}""", empty) }.isFailure && runCatching { CastFiles.read("not json", empty) }.isFailure)
            report.add("PASS files that are not casts or lorebooks are refused with a message")
        } finally {
            check(preferences.edit().remove(key).commit())
        }
    }
}
