package app.roadleaf.playback

import java.text.Normalizer
import java.util.Locale

/** English front end for Roadleaf's procedural voice. Each group is one word or one phrase break. */
internal object RoadleafEnglish {
    data class Group(val phones: List<String>, val breakMark: Char? = null)
    private val token = Regex("""[a-zA-Z]+(?:'[a-zA-Z]+)*|[0-9]+|[.,!?;:\n—…]""")
    private val vowelLetters = "aeiouy"
    private val unit = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(" ")
    private val tens = "zero ten twenty thirty forty fifty sixty seventy eighty ninety".split(" ")
    private val letters = "EY/B IY/S IY/D IY/IY/EH F/JH IY/EY CH/AY/JH EY/K EY/EH L/EH M/EH N/OW/P IY/K Y UW/AA R/EH S/T IY/Y UW/V IY/D AH B AH L Y UW/EH K S/W AY/Z IY".split("/")
    private val voicedEnd = setOf("B", "D", "G", "M", "N", "NG", "L", "R", "V", "Z", "DH", "Y", "W", "ZH")
    private val hissEnd = setOf("S", "Z", "SH", "ZH", "CH", "JH")
    private val quietEnd = setOf("P", "T", "K", "F", "TH", "S", "SH", "CH", "HH")
    private val entries = """
a AX
i AY
the DH AX
an AE N
and AE N D
am AE M
as AE Z
at AE T
are AA R
is IH Z
it IH T
its IH T S
of AH V
off AO F
on AA N
one W AH N
once W AH N S
to T UW
two T UW
too T UW
do D UW
does D AH Z
done D AH N
did D IH D
go G OW
gone G AO N
good G UH D
could K UH D
would W UH D
should SH UH D
who HH UW
whose HH UW Z
whom HH UW M
whole HH OW L
how HH AW
show SH OW
know N OW
no N OW
now N AW
oh OW
so S OW
both B OW TH
only OW N L IY
won W AH N
was W AH Z
has HH AE Z
had HH AE D
have HH AE V
give G IH V
live L IH V
love L AH V
move M UW V
prove P R UW V
above AX B AH V
some S AH M
come K AH M
comes K AH M Z
from F R AH M
he HH IY
she SH IY
we W IY
me M IY
be B IY
her HH ER
were W ER
there DH EH R
their DH EH R
they DH EY
them DH EH M
then DH EH N
than DH AE N
this DH IH S
that DH AE T
these DH IY Z
those DH OW Z
though DH OW
through TH R UW
thought TH AO T
although AO L DH OW
thus DH AH S
you Y UW
your Y OR
yours Y OR Z
our AW R
hour AW R
my M AY
by B AY
why W AY
try T R AY
any EH N IY
many M EH N IY
such S AH CH
much M AH CH
very V EH R IY
every EH V R IY
again AX G EH N
said S EH D
says S EH Z
say S EY
see S IY
sea S IY
been B IH N
being B IY IH NG
use Y UW Z
used Y UW Z D
us AH S
yes Y EH S
put P UH T
pull P UH L
full F UH L
push P UH SH
book B UH K
look L UH K
took T UH K
stood S T UH D
foot F UH T
food F UW D
room R UW M
wood W UH D
read R IY D
head HH EH D
dead D EH D
bread B R EH D
ready R EH D IY
heard HH ER D
hear HH IH R
heart HH AA R T
earth ER TH
early ER L IY
learn L ER N
word W ER D
work W ER K
world W ER L D
wore W OR
war W OR
warn W OR N
want W AA N T
water W AO T ER
watch W AA CH
what W AH T
where W EH R
here HH IH R
werewolf W EH R W UH L F
wear W EH R
year Y IH R
near N IH R
door D OR
floor F L OR
more M OR
four F OR
for F OR
before B IH F OR
friend F R EH N D
people P IY P AX L
woman W UH M AX N
women W IH M IH N
man M AE N
men M EH N
child CH AY L D
children CH IH L D R AX N
mother M AH DH ER
father F AA DH ER
brother B R AH DH ER
other AH DH ER
another AX N AH DH ER
together T AX G EH DH ER
answer AE N S ER
listen L IH S AX N
often AO F AX N
speak S P IY K
spoken S P OW K AX N
new N UW
knew N UW
few F Y UW
first F ER S T
second S EH K AX N D
third TH ER D
five F AY V
three TH R IY
eight EY T
eighteen EY T IY N
eighty EY T IY
eleven IH L EH V AX N
twelve T W EH L V
twenty T W EH N T IY
zero Z IH R OW
hundred HH AH N D R AX D
thousand TH AW Z AX N D
million M IH L Y AX N
billion B IH L Y AX N
enough IH N AH F
rough R AH F
tough T AH F
laugh L AE F
cough K AO F
bough B AW
brought B R AO T
caught K AO T
bought B AO T
great G R EY T
break B R EY K
steak S T EY K
leave L IY V
gone G AO N
lose L UW Z
lost L AO S T
choose CH UW Z
voice V OY S
voices V OY S IH Z
english IH NG G L IH SH
native N EY T IH V
audio AO D IY OW
hello HH EH L OW
quiet K W AY AX T
quite K W AY T
little L IH T AX L
while W AY L
never N EH V ER
ever EH V ER
even IY V AX N
open OW P AX N
close K L OW Z
wind W IH N D
mind M AY N D
kind K AY N D
find F AY N D
behind B IH HH AY N D
night N AY T
light L AY T
right R AY T
bring B R IH NG
circle S ER K AX L
enter EH N T ER
roadleaf R OW D L IY F
road R OW D
leaf L IY F
story S T OR IY
storyline S T OR IY L AY N
stories S T OR IY Z
captain K AE P T AX N
character K AE R IH K T ER
chapter CH AE P T ER
sentence S EH N T AX N S
narrator N AE R EY T ER
narration N AE R EY SH AX N
throughout TH R UW AW T
toward T AX W OR D
focus F OW K AX S
turn T ER N
harbor HH AA R B ER
ship SH IH P
sail S EY L
sails S EY L Z
fire F AY ER
walk W AO K
talk T AO K
calm K AA M
half HH AE F
city S IH T IY
pretty P R IH T IY
busy B IH Z IY
high HH AY
low L OW
age EY JH
presence P R EH Z AX N S
emotion IH M OW SH AX N
example IH G Z AE M P AX L
name N EY M
personal P ER S AX N AX L
numeric N UW M EH R IH K
number N AH M B ER
test T EH S T
reading R IY D IH NG
different D IH F R AX N T
clear K L IH R
clearly K L IH R L IY
offline AO F L AY N
engine EH N JH AX N
blue B L UW
synthesizer S IH N TH AX S AY Z ER
synthesis S IH N TH AX S IH S
model M AA D AX L
library L AY B R EH R IY
riker R AY K ER
voss V AO S
kira K IH R AX
davos D AE V OW S
reyes R EY Y EH S
jin JH IH N
tarro T AA R OW
meridian M AX R IH D IY AX N
can't K AE N T
won't W OW N T
don't D OW N T
doesn't D AH Z AX N T
isn't IH Z AX N T
wasn't W AH Z AX N T
weren't W ER N T
i'm AY M
i'll AY L
i've AY V
i'd AY D
you're Y UH R
you'll Y UW L
they're DH EH R
we're W IH R
he's HH IY Z
she's SH IY Z
it's IH T S
that's DH AE T S
there's DH EH R Z
""".trimIndent().lineSequence().associate {
        val parts = it.split(' ')
        parts.first() to parts.drop(1)
    }

    private val digraphs = linkedMapOf(
        "tion" to "SH AX N", "sion" to "ZH AX N", "ture" to "CH ER", "cian" to "SH AX N",
        "tch" to "CH", "dge" to "JH", "igh" to "AY", "eigh" to "EY", "air" to "EH R",
        "ear" to "IH R", "eer" to "IH R", "ore" to "OR", "oor" to "OR", "our" to "AW R",
        "sch" to "S K", "sh" to "SH", "ch" to "CH", "th" to "TH", "ph" to "F", "ng" to "NG",
        "ck" to "K", "qu" to "K W", "wh" to "W", "ee" to "IY", "ea" to "IY", "oo" to "UW",
        "ai" to "EY", "ay" to "EY", "oa" to "OW", "ow" to "AW", "ou" to "AW", "oi" to "OY",
        "oy" to "OY", "au" to "AO", "aw" to "AO", "ar" to "AA R", "or" to "OR", "er" to "ER",
        "ir" to "ER", "ur" to "ER", "ew" to "Y UW", "ie" to "IY", "ue" to "UW"
    )
    private val consonants = mapOf('b' to "B", 'd' to "D", 'f' to "F", 'h' to "HH", 'j' to "JH",
        'k' to "K", 'l' to "L", 'm' to "M", 'n' to "N", 'p' to "P", 'r' to "R", 's' to "S",
        't' to "T", 'v' to "V", 'w' to "W", 'z' to "Z", 'q' to "K")

    fun groups(text: String): List<Group> {
        val plain = Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return buildList {
            for (match in token.findAll(plain)) {
                val value = match.value
                when {
                    value.first().isDigit() -> numberWords(value).forEach { add(Group(word(it))) }
                    value.first().isLetter() -> {
                        val lower = value.lowercase(Locale.US)
                        if (value.length in 2..5 && value.all { it in 'A'..'Z' } && lower !in entries)
                            value.forEach { add(Group(letters[it - 'A'].split(' '))) }
                        else add(Group(word(lower)))
                    }
                    else -> add(Group(emptyList(), value.first()))
                }
            }
        }
    }

    private fun numberWords(digits: String): List<String> {
        if (digits.length > 9 || (digits.startsWith('0') && digits.length > 1)) return digits.map { unit[it - '0'] }
        fun words(n: Int): List<String> {
            if (n < 20) return listOf(unit[n])
            if (n < 100) return listOf(tens[n / 10]) + if (n % 10 == 0) emptyList() else listOf(unit[n % 10])
            val (divisor, scale) = when {
                n >= 1_000_000 -> 1_000_000 to "million"
                n >= 1_000 -> 1_000 to "thousand"
                else -> 100 to "hundred"
            }
            return words(n / divisor) + scale + if (n % divisor == 0) emptyList() else words(n % divisor)
        }
        return words(digits.toInt())
    }

    private fun sEnding(last: String?) = when (last) {
        in hissEnd -> listOf("IH", "Z")
        in quietEnd -> listOf("S")
        else -> listOf("Z")
    }

    private fun stem(base: String): List<String> {
        entries[base]?.let { return it }
        entries[base + "e"]?.let { return it }
        val doubled = base.length > 2 && base.last() == base[base.lastIndex - 1] && base.last() !in vowelLetters
        return word(if (doubled) base.dropLast(1) else base)
    }

    private fun word(value: String): List<String> {
        entries[value]?.let { return it }
        if (value.isEmpty()) return emptyList()
        val contractions = mapOf("n't" to "N T", "'ve" to "V", "'ll" to "L", "'re" to "R", "'d" to "D", "'m" to "M")
        for ((suffix, sounds) in contractions) {
            if (value.endsWith(suffix) && value.length > suffix.length) return word(value.dropLast(suffix.length)) + sounds.split(' ')
        }
        if (value.endsWith("'s")) return word(value.dropLast(2)).let { it + sEnding(it.lastOrNull()) }
        if (value.endsWith("ies") && value.length > 4) return word(value.dropLast(3) + "y") + "Z"
        if (value.endsWith("ing") && value.length > 4) return stem(value.dropLast(3)) + listOf("IH", "NG")
        if (value.endsWith("ed") && value.length > 3) {
            val base = stem(value.dropLast(2))
            val ending = when (base.lastOrNull()) {
                "T", "D" -> listOf("IH", "D")
                in quietEnd -> listOf("T")
                else -> listOf("D")
            }
            return base + ending
        }
        if (value.endsWith('s') && !value.endsWith("ss") && !value.endsWith("us") && value.length > 2) {
            val base = if (value.endsWith("es") && value.dropLast(2).endsWithAny("sh", "ch", "ss", "x", "z"))
                word(value.dropLast(2)) else word(value.dropLast(1))
            return base + sEnding(base.lastOrNull())
        }
        val result = mutableListOf<String>()
        var i = if (value.startsWith("kn") || value.startsWith("wr") || value.startsWith("gn")) 1 else 0
        while (i < value.length) {
            val start = i
            val c = value[i]
            val next = value.getOrNull(i + 1)
            val after = value.getOrNull(i + 2)
            when {
                i == value.lastIndex && c == 'e' && i > 1 && value[i - 1] !in vowelLetters -> Unit
                value.startsWith("le", i) && i == value.length - 2 && i > 0 && value[i - 1] !in vowelLetters -> {
                    result += listOf("AX", "L"); i++
                }
                c == 'g' && next == 'h' && i != 0 -> i++
                value.startsWith("ow", i) && i == value.length - 2 -> { result += "OW"; i++ }
                else -> {
                    val group = digraphs.entries.firstOrNull { value.startsWith(it.key, i) }
                    if (group != null) {
                        result += group.value.split(' ')
                        i += group.key.length - 1
                    } else if (c in vowelLetters) {
                        val long = next != null && next !in vowelLetters && after == 'e' && i + 2 == value.lastIndex
                        if (c == 'u' && long) result += "Y"
                        result += when (c) {
                            'a' -> if (long) "EY" else "AE"
                            'e' -> if (long) "IY" else "EH"
                            'i' -> if (long) "AY" else "IH"
                            'o' -> if (long || i == value.lastIndex) "OW" else "AA"
                            'u' -> if (long) "UW" else "AH"
                            else -> if (i == 0) "Y" else if (i == value.lastIndex) "IY" else "IH"
                        }
                    } else {
                        when (c) {
                            'c' -> result += if (next != null && next in "eiy") "S" else "K"
                            'g' -> result += if (next != null && next in "eiy") "JH" else "G"
                            'x' -> result += if (i == 0) listOf("Z") else listOf("K", "S")
                            else -> consonants[c]?.let(result::add)
                        }
                    }
                }
            }
            if (i == start && next == c && c !in vowelLetters) i++
            i++
        }
        return result
    }

    private fun String.endsWithAny(vararg suffixes: String) = suffixes.any(::endsWith)
}
