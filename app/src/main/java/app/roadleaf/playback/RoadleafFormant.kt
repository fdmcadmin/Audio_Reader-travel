package app.roadleaf.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Roadleaf's English parametric voice. Voicing, friction, closures and moving vocal-tract
 * resonances are synthesized in PCM. All coefficients are hand-authored; no model files,
 * recordings, Android speech services or provider calls are used.
 */
internal object RoadleafFormant {
    const val SAMPLE_RATE = 22_050

    data class Voice(
        val family: Char = 'F', val sources: List<String> = listOf("1", "0", "0", "0"),
        val emotion: Int = 0, val presence: Int = 0, val age: Int = 0
    )

    private data class Shape(val first: Double, val second: Double, val third: Double) {
        fun toward(other: Shape, mix: Double) = Shape(
            first + mix * (other.first - first), second + mix * (other.second - second),
            third + mix * (other.third - third))
        fun scaled(amount: Double) = Shape(first * amount, second * amount, third * amount)
    }
    private enum class Manner { VOWEL, SONORANT, NASAL, FRICATIVE, STOP, AFFRICATE, BREATH, SPACE }
    private data class Phone(
        val shape: Shape, val end: Shape, val ms: Int, val manner: Manner, val voicing: Double,
        val hiss: Double = 0.0, val noiseHz: Double = 3000.0
    )
    private fun vowel(a: Int, b: Int, c: Int, ms: Int, end: Shape? = null): Phone {
        val shape = Shape(a.toDouble(), b.toDouble(), c.toDouble())
        return Phone(shape, end ?: shape, ms, Manner.VOWEL, 1.0)
    }
    private fun consonant(a: Int, b: Int, c: Int, ms: Int, manner: Manner, voice: Double,
        noise: Double = 0.0, center: Double = 3000.0): Phone {
        val shape = Shape(a.toDouble(), b.toDouble(), c.toDouble())
        return Phone(shape, shape, ms, manner, voice, noise, center)
    }

    // Resonances in Hz at the reference tract length. Plosives have a closure and a timed release.
    private val phones = mapOf(
        "AA" to vowel(730, 1110, 2570, 120), "AE" to vowel(680, 1710, 2540, 129),
        "AH" to vowel(600, 1220, 2600, 105), "AO" to vowel(550, 900, 2470, 123),
        "AX" to vowel(490, 1450, 2500, 66), "EH" to vowel(550, 1850, 2520, 109),
        "IH" to vowel(400, 1900, 2570, 89), "IY" to vowel(285, 2240, 2940, 125),
        "ER" to vowel(470, 1330, 1670, 121), "UH" to vowel(430, 1050, 2250, 96),
        "UW" to vowel(310, 800, 2220, 122),
        "AY" to vowel(730, 1250, 2540, 145, Shape(340.0, 2080.0, 2800.0)),
        "AW" to vowel(695, 1400, 2530, 142, Shape(350.0, 850.0, 2230.0)),
        "EY" to vowel(505, 1790, 2500, 130, Shape(335.0, 2160.0, 2770.0)),
        "OY" to vowel(520, 870, 2490, 145, Shape(360.0, 1990.0, 2700.0)),
        "OW" to vowel(485, 995, 2450, 134, Shape(335.0, 805.0, 2280.0)),
        "OR" to vowel(520, 900, 2420, 150, Shape(440.0, 1280.0, 1660.0)),
        "L" to consonant(360, 1160, 2560, 74, Manner.SONORANT, .64),
        "R" to consonant(385, 1130, 1550, 74, Manner.SONORANT, .76),
        "W" to consonant(280, 675, 2160, 63, Manner.SONORANT, .79),
        "Y" to consonant(260, 2150, 3020, 50, Manner.SONORANT, .76),
        "M" to consonant(260, 1020, 2320, 84, Manner.NASAL, .77),
        "N" to consonant(280, 1610, 2660, 70, Manner.NASAL, .74),
        "NG" to consonant(295, 1950, 2480, 81, Manner.NASAL, .73),
        "B" to consonant(280, 790, 2220, 67, Manner.STOP, .36, .31, 1050.0),
        "P" to consonant(300, 860, 2360, 79, Manner.STOP, 0.0, .68, 1250.0),
        "D" to consonant(320, 1740, 2780, 60, Manner.STOP, .37, .54, 3900.0),
        "T" to consonant(300, 1780, 2860, 74, Manner.STOP, 0.0, .82, 4200.0),
        "G" to consonant(310, 1870, 2290, 69, Manner.STOP, .36, .57, 2050.0),
        "K" to consonant(300, 1850, 2320, 83, Manner.STOP, 0.0, .86, 2180.0),
        "F" to consonant(320, 1200, 2430, 86, Manner.FRICATIVE, 0.0, .38, 3400.0),
        "V" to consonant(365, 1210, 2450, 74, Manner.FRICATIVE, .44, .27, 3300.0),
        "TH" to consonant(390, 1500, 2520, 80, Manner.FRICATIVE, 0.0, .35, 4050.0),
        "DH" to consonant(370, 1450, 2580, 64, Manner.FRICATIVE, .46, .25, 3900.0),
        "S" to consonant(320, 1770, 2630, 105, Manner.FRICATIVE, 0.0, .83, 6150.0),
        "Z" to consonant(340, 1770, 2600, 96, Manner.FRICATIVE, .46, .57, 5670.0),
        "SH" to consonant(350, 1790, 2370, 108, Manner.FRICATIVE, 0.0, .80, 2970.0),
        "ZH" to consonant(360, 1770, 2370, 89, Manner.FRICATIVE, .41, .58, 2800.0),
        "CH" to consonant(300, 1790, 2470, 115, Manner.AFFRICATE, 0.0, .78, 3470.0),
        "JH" to consonant(320, 1720, 2430, 106, Manner.AFFRICATE, .38, .60, 3210.0),
        "HH" to consonant(475, 1450, 2490, 66, Manner.BREATH, 0.0, .56)
    )

    private val rest = consonant(480, 1450, 2500, 12, Manner.SPACE, 0.0)
    private data class Traits(val hz: Double, val tract: Double, val breath: Double, val contour: Double)
    // Deliberately independent dimensions: two different source numbers can have similar pitch
    // without sharing tract length, excitation or intonation.
    private val pitchSteps = listOf(0, -9, 13, -17, 6, 19, -4, 11, -13, 3, -21, 16, -6, 9, -2)
    private val tractSteps = listOf(0, 4, -3, 2, -5, 1, 6, -2, -4, 5, -1, 3, -6, 2, 4)
    private fun source(family: Char, number: Int): Traits {
        require(family == 'F' || family == 'M')
        require(number in 1..15)
        val index = number - 1
        return Traits((if (family == 'F') 177.0 else 116.0) + pitchSteps[index],
            (if (family == 'F') 1.105 else 0.975) + 0.012 * tractSteps[index],
            .022 + ((index * 7) % 13) * .003, 0.088 + ((index * 5) % 11) * .006)
    }
    private fun traits(voice: Voice): Traits {
        require(voice.sources.size == 4 && voice.sources.first() != "0")
        require(minOf(voice.emotion, voice.presence, voice.age) >= 0)
        val active = voice.sources.mapIndexedNotNull { index, raw ->
            if (raw == "0") return@mapIndexedNotNull null
            val parts = raw.removeSurrounding("(", ")").split('/')
            require(parts.size == 1 || parts.size == 2 && index == 0)
            val primary = source(voice.family, parts.first().toInt())
            if (parts.size == 1 || parts[1] == "0") primary else {
                val partner = source(if (voice.family == 'F') 'M' else 'F', parts[1].toInt())
                Traits(primary.hz * .72 + partner.hz * .28, primary.tract * .72 + partner.tract * .28,
                    primary.breath * .72 + partner.breath * .28, primary.contour * .72 + partner.contour * .28)
            }
        }
        val weights = listOf(.52, .26, .15, .07).take(active.size)
        val total = weights.sum()
        fun weighted(pick: (Traits) -> Double) = active.indices.sumOf { weights[it] * pick(active[it]) } / total
        // 0 is unchanged. Positive levels remain bounded without changing the saved numeric code.
        fun strength(n: Int) = n / (n.toDouble() + 5.0)
        val emotion = strength(voice.emotion)
        val age = strength(voice.age)
        val presence = strength(voice.presence)
        return Traits(weighted { it.hz } * (1 + .08 * emotion - .10 * age), weighted { it.tract } * (1 - .045 * age),
            weighted { it.breath } + .043 * age - .013 * presence, weighted { it.contour } * (1 + emotion * .75 + presence * .25))
    }

    private data class Segment(val phone: Phone, val frames: Int, val accent: Double, var position: Double = 0.0,
        var question: Boolean = false)
    private fun plan(text: String): List<Segment> {
        val segments = ArrayList<Segment>()
        var phraseStart = 0
        fun finishPhrase(question: Boolean) {
            val length = (phraseStart until segments.size).sumOf { segments[it].frames }.coerceAtLeast(1)
            var elapsed = 0
            for (i in phraseStart until segments.size) {
                val segment = segments[i]
                segment.position = (elapsed + .5 * segment.frames) / length
                segment.question = question
                elapsed += segment.frames
            }
            phraseStart = segments.size
        }
        for (group in RoadleafEnglish.groups(text)) {
            val mark = group.breakMark
            if (mark != null) {
                if (segments.isEmpty() || phraseStart == segments.size) continue
                if (segments.last().phone == rest) segments.removeAt(segments.lastIndex)
                finishPhrase(mark == '?')
                val pauseMs = when (mark) { ',', ':' -> 135; ';', '—' -> 170; '\n' -> 260; else -> 222 }
                segments += Segment(rest, SAMPLE_RATE * pauseMs / 1000, 0.0)
                phraseStart = segments.size
                continue
            }
            val sounds = group.phones.map { requireNotNull(phones[it]) { "Unknown Roadleaf phone $it" } }
            val stress = sounds.indexOfFirst { it.manner == Manner.VOWEL && it !== phones["AX"] }
            sounds.forEachIndexed { index, phone ->
                val accented = index == stress
                val duration = if (accented) phone.ms * 1.11 else phone.ms.toDouble()
                segments += Segment(phone, (SAMPLE_RATE * duration / 1000).roundToInt(), if (accented) 1.0 else 0.0)
            }
            if (sounds.isNotEmpty()) segments += Segment(rest, SAMPLE_RATE * 12 / 1000, 0.0)
        }
        if (phraseStart < segments.size) finishPhrase(false)
        return segments
    }

    /** Each pole pair retains its history as the formants move between adjacent phones. */
    private class Resonance {
        var coefficient = 0.0
        var damping = 0.0
        var gain = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        fun tune(frequency: Double, width: Double) {
            val radius = exp(-PI * width / SAMPLE_RATE)
            val omega = 2 * PI * frequency.coerceIn(70.0, SAMPLE_RATE * .46) / SAMPLE_RATE
            coefficient = 2 * radius * cos(omega)
            damping = radius * radius
            gain = (1 - radius) * 2 * sin(omega)
        }
        fun next(input: Double): Double {
            val value = coefficient * y1 - damping * y2 + gain * input
            y2 = y1
            y1 = value
            return value
        }
    }

    fun synthesize(text: String, voice: Voice = Voice(), pitch: Float = 1f): ShortArray {
        require(pitch.isFinite() && pitch > 0)
        require(text.length <= 2048) { "Submit speech in bounded passages." }
        val segments = plan(text)
        // A scene divider or a punctuation-only reading span is a short silent beat.
        if (segments.isEmpty()) return ShortArray(SAMPLE_RATE / 5)
        val frames = segments.sumOf { it.frames.toLong() }
        require(frames <= SAMPLE_RATE * 180L) { "Split this passage into shorter speech windows." }
        val output = ShortArray(frames.toInt() + SAMPLE_RATE / 40)
        val speaker = traits(voice)
        val first = Resonance()
        val second = Resonance()
        val third = Resonance()
        val friction = Resonance()
        var phase = .78
        var glottal = 0.0
        var previousNoise = 0.0
        var random = 0x1273B5A
        var offset = 0
        for ((index, segment) in segments.withIndex()) {
            val phone = segment.phone
            val before = segments.getOrNull(index - 1)?.phone
            val after = segments.getOrNull(index + 1)?.phone
            val manner = phone.manner
            var hz = speaker.hz
            var amplitude = 0.0
            var noiseGain = 0.0
            var airGain = 0.0
            var nasalFactor = 1.0
            friction.tune(phone.noiseHz * speaker.tract, if (manner == Manner.STOP) 1400.0 else 1760.0)
            for (frame in 0 until segment.frames) {
                if ((frame and 15) == 0) {
                    val t = frame.toDouble() / segment.frames
                    var shape = phone.shape.toward(phone.end, ((t - .20) / .67).coerceIn(0.0, 1.0))
                    if (manner == Manner.BREATH && after != null) shape = after.shape
                    if (manner != Manner.SPACE) {
                        if (t < .28 && before != null && before.manner != Manner.SPACE)
                            shape = shape.toward(before.end, .47 * (1 - t / .28))
                        if (t > .77 && after != null && after.manner != Manner.SPACE)
                            shape = shape.toward(after.shape, .44 * (t - .77) / .23)
                    }
                    shape = shape.scaled(speaker.tract)
                    first.tune(shape.first, if (manner == Manner.NASAL) 70.0 else 82.0)
                    second.tune(shape.second, 118.0)
                    third.tune(shape.third, 172.0)
                    val p = segment.position
                    val accent = segment.accent * sin(PI * t)
                    val terminal = if (segment.question) ((p - .65) / .35).coerceIn(0.0, 1.0) * .26 else -.035 * p
                    hz = speaker.hz * pitch.coerceIn(.65f, 1.55f) *
                        (1.08 - .15 * p + terminal + speaker.contour * accent + .01 * sin((offset + frame) * .00097))
                    val fadeIn = if (before == null || before.manner == Manner.SPACE) 105.0 else 54.0
                    val fade = minOf(1.0, (frame + 1) / fadeIn, (segment.frames - frame) / 85.0)
                    amplitude = fade * phone.voicing * (1 + .12 * accent)
                    noiseGain = 0.0
                    airGain = 0.0
                    nasalFactor = 1.0
                    when (manner) {
                        Manner.NASAL -> nasalFactor = .22
                        Manner.BREATH -> airGain = fade * .72
                        Manner.STOP -> {
                            val release = if (phone.voicing > 0) .73 else .57
                            amplitude *= if (t < release) .33 else .42
                            if (t >= release) {
                                val burst = (t - release) / (1 - release)
                                noiseGain = phone.hiss * exp(-burst * 3.6) * fade
                                airGain = if (phone.voicing == 0.0) .26 * (1 - burst) else 0.0
                            }
                        }
                        Manner.AFFRICATE -> {
                            amplitude *= .5
                            noiseGain = phone.hiss * ((t - .26) / .1).coerceIn(0.0, 1.0) * fade
                        }
                        Manner.FRICATIVE -> noiseGain = phone.hiss * fade
                        Manner.SPACE -> amplitude = 0.0
                        else -> airGain = amplitude * speaker.breath
                    }
                }
                random = random xor (random shl 13)
                random = random xor (random ushr 17)
                random = random xor (random shl 5)
                val noise = random / 2147483648.0
                val highNoise = noise - .75 * previousNoise
                previousNoise = noise
                phase += hz / SAMPLE_RATE
                val tick = if (phase >= 1) { phase -= 1.0; 1.0 } else 0.0
                // A short glottal closure excites the tract. The decaying tail sets the spectral tilt.
                glottal = .58 * glottal + tick * 32.0 * (135.0 / hz)
                val excitation = glottal * amplitude + noise * airGain * 2.9
                val formants = first.next(excitation) -
                    .83 * nasalFactor * second.next(excitation) + .52 * nasalFactor * third.next(excitation)
                val hiss = noiseGain * (friction.next(highNoise) * 2.15 + .11 * highNoise)
                val wave = .47 * formants + .56 * hiss
                output[offset + frame] = (tanh(wave * 1.40) * 27_800).roundToInt().toShort()
            }
            offset += segment.frames
        }
        // Smooth the last few milliseconds even if the sentence ends on an unreleased consonant.
        val tail = minOf(offset, SAMPLE_RATE / 100)
        for (i in 0 until tail) {
            val p = offset - tail + i
            output[p] = (output[p] * (tail - i - 1).toDouble() / tail).roundToInt().toShort()
        }
        return output
    }
}
