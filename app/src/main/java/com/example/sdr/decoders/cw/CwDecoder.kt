package com.example.sdr.decoders.cw

import kotlin.math.*

data class CwCharacter(val value: Char, val confidence: Int, val alternative: Char? = null)

data class CwInformation(val text: String = "", val pending: String = "", val wpm: Int = 0,
    val toneHz: Int = 0, val tonePresent: Boolean = false,
    val characters: List<CwCharacter> = emptyList())

/** Streaming PCM detector. A 10 ms Goertzel bank tracks a CW tone without modifying audio. */
internal class CwDecoder {
    private var rate = 0
    private var size = 0
    private var position = 0
    private var window = DoubleArray(0)
    private val frequencies = IntArray(15) { 400 + it * 50 }
    private val coefficients = DoubleArray(15)
    private val first = DoubleArray(15)
    private val second = DoubleArray(15)
    private var energy = 0.0
    private var peak = 0.0
    private val amplitudes = DoubleArray(200)
    private var amplitudeCount = 0
    private var amplitudeIndex = 0
    private var thresholdOn = .0008
    private var thresholdOff = .0006
    private var contrast = false
    private var noiseFloor = 0.0
    private var clock = 0.0
    private var keyed = false
    private var candidate = false
    private var candidateCount = 0
    private var edgeAt = 0.0
    private val timing = CwTimingModel()
    private val unit get() = timing.dotMs
    private var marks = 0
    private val characters = ArrayList<CwCharacter>()
    private val probationCharacters = ArrayList<CwCharacter>()
    private val probation = StringBuilder()
    private var locked = false
    private var credibleMarks = 0
    private var lastCoherentMark = 0.0
    private val phases = DoubleArray(15)
    private var previousDetected = false
    private val markFrequencies = ArrayList<Double>()
    private var tone = 0
    private val symbols = StringBuilder()
    private val durations = ArrayList<Double>()
    private val text = StringBuilder()

    fun information() = CwInformation(text.toString(), symbols.toString(),
        if (marks >= 3) (1200 / unit).roundToInt() else 0, tone, keyed, characters.toList())

    fun process(samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        require(sampleRate in 4000..48000 && channels in 1..2)
        if (rate != sampleRate) {
            rate = sampleRate; size = (rate / 100).coerceAtLeast(1)
            window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
            frequencies.indices.forEach { coefficients[it] = 2 * cos(2 * PI * frequencies[it] / rate) }
            position = 0; first.fill(0.0); second.fill(0.0); energy = 0.0
            keyed = false; candidateCount = 0; symbols.clear(); edgeAt = clock
            durations.clear(); markFrequencies.clear(); probation.clear(); probationCharacters.clear(); timing.reset()
            previousDetected = false; phases.fill(0.0); locked = false; credibleMarks = 0
            peak = 0.0; noiseFloor = 0.0; amplitudeCount = 0; amplitudeIndex = 0
        }
        var index = 0
        while (index + channels <= samples.size) {
            val mono = if (channels == 2) (samples[index].toInt() + samples[index + 1]) / 2.0 else samples[index].toDouble()
            val value = mono / 32768.0 * window[position]
            energy += value * value
            for (bin in frequencies.indices) {
                val next = value + coefficients[bin] * first[bin] - second[bin]
                second[bin] = first[bin]; first[bin] = next
            }
            position++; index += channels
            if (position == size) {
                var strongest = 0.0; var best = 0
                for (bin in frequencies.indices) {
                    val power = first[bin] * first[bin] + second[bin] * second[bin] - coefficients[bin] * first[bin] * second[bin]
                    if (power > strongest) { strongest = power; best = bin }
                }
                val amplitude = sqrt(strongest.coerceAtLeast(0.0)) * 4 / size
                peak = max(amplitude, peak * .995)
                amplitudes[amplitudeIndex] = amplitude
                amplitudeIndex = (amplitudeIndex + 1) % amplitudes.size
                amplitudeCount = min(amplitudeCount + 1, amplitudes.size)
                if (amplitudeCount >= 30 && amplitudeIndex % 10 == 0) {
                    val sorted = amplitudes.copyOf(amplitudeCount).apply { sort() }
                    // Long hand-keyed dashes can occupy >80% of this window.
                    // Keep the noise estimate in the gaps instead of learning the carrier.
                    noiseFloor = sorted[(sorted.size * .1).toInt()]
                }
                thresholdOn = max(.0008, noiseFloor + (peak - noiseFloor) * .45)
                thresholdOff = max(.0006, noiseFloor + (peak - noiseFloor) * .25)
                contrast = peak > max(.001, noiseFloor * 1.8)
                val coherence = 2 * strongest / (size * energy + 1e-20)
                val detected = contrast && coherence > (if (keyed) .32 else .45) &&
                    amplitude > if (keyed) thresholdOff else thresholdOn
                if (detected) {
                    tone = frequencies[best]
                    if (previousDetected) {
                        val w = 2 * PI * frequencies[best] / rate
                        val phase = atan2(second[best] * sin(w), first[best] - second[best] * cos(w))
                        val delta = phase - phases[best] - w * size
                        val measured = frequencies[best] + atan2(sin(delta), cos(delta)) * rate / (2 * PI * size)
                        if (markFrequencies.size < 128) markFrequencies.add(measured)
                    }
                }
                for (bin in frequencies.indices) {
                    val w = 2 * PI * frequencies[bin] / rate
                    phases[bin] = atan2(second[bin] * sin(w), first[bin] - second[bin] * cos(w))
                }
                previousDetected = detected
                clock += size * 1000.0 / rate
                tick(detected)
                first.fill(0.0); second.fill(0.0); energy = 0.0; position = 0
            }
        }
    }

    private fun tick(detected: Boolean) {
        if (detected != candidate) { candidate = detected; candidateCount = 1 } else candidateCount++
        if (candidate != keyed && candidateCount >= 2) {
            val at = clock - size * 1000.0 / rate
            val duration = at - edgeAt
            if (keyed) {
                val orderedFrequencies = markFrequencies.sorted()
                val medianFrequency = orderedFrequencies.getOrNull(orderedFrequencies.size / 2) ?: 0.0
                val deviations = orderedFrequencies.map { abs(it - medianFrequency) }.sorted()
                val spread = if (deviations.size >= 2) deviations[deviations.size / 2] * 1.4826 else Double.POSITIVE_INFINITY
                val clean = duration in 20.0..1000.0 &&
                    (spread < 12.0 || locked && (spread < 30.0 || markFrequencies.size < 2))
                if (clean) {
                    if (spread < 12.0) lastCoherentMark = clock
                    timing.observe(duration, true)
                    durations.add(duration)
                    symbols.clear()
                    durations.forEach { symbols.append(if (it > timing.markBoundaryMs) '-' else '.') }
                    credibleMarks++
                    marks++
                    if (!locked && credibleMarks >= 4) { locked = true; text.append(probation); characters.addAll(probationCharacters); probation.clear(); probationCharacters.clear() }
                    if (symbols.length > 8) { symbols.clear(); durations.clear() }
                } else {
                    symbols.clear(); durations.clear()
                    if (!locked) { probation.clear(); probationCharacters.clear(); credibleMarks = 0 }
                }
                markFrequencies.clear()
            } else {
                if (credibleMarks > 0 && duration in 20.0..1800.0) timing.observe(duration, false)
            }
            keyed = candidate; edgeAt = at
        }
        if (!keyed && !candidate) {
            val silence = clock - edgeAt
            if (silence >= timing.characterGapMs) finishLetter()
            if (timing.hasSpacing && silence >= timing.wordGapMs && locked && text.isNotEmpty() && text.last() != ' ') {
                text.append(' '); characters.add(CwCharacter(' ', 100))
            }
            if (silence >= max(max(700.0, unit * 12), timing.wordGapMs + unit * 3)) {
                locked = false; credibleMarks = 0; probation.clear(); probationCharacters.clear(); symbols.clear(); durations.clear()
                markFrequencies.clear()
            }
        }
        if (text.length > 512) {
            val excess = text.length - 512
            text.delete(0, excess); characters.subList(0, excess).clear()
        }
        if (locked && clock - lastCoherentMark > max(max(1000.0, unit * 15), timing.wordGapMs + unit * 2)) {
            locked = false; credibleMarks = 0; probation.clear(); probationCharacters.clear(); symbols.clear(); durations.clear()
        }
    }

    private fun finishLetter() {
        if (symbols.isEmpty()) return
        val character = timing.recognize(durations, alphabet)
        if (character != null) {
            if (locked) { text.append(character.value); characters.add(character) }
            else { probation.append(character.value); probationCharacters.add(character) }
        }
        symbols.clear(); durations.clear()
    }

    companion object {
        private val alphabet = listOf(
            ".-" to 'A', "-..." to 'B', "-.-." to 'C', "-.." to 'D', "." to 'E', "..-." to 'F',
            "--." to 'G', "...." to 'H', ".." to 'I', ".---" to 'J', "-.-" to 'K', ".-.." to 'L',
            "--" to 'M', "-." to 'N', "---" to 'O', ".--." to 'P', "--.-" to 'Q', ".-." to 'R',
            "..." to 'S', "-" to 'T', "..-" to 'U', "...-" to 'V', ".--" to 'W', "-..-" to 'X',
            "-.--" to 'Y', "--.." to 'Z', "-----" to '0', ".----" to '1', "..---" to '2',
            "...--" to '3', "....-" to '4', "....." to '5', "-...." to '6', "--..." to '7',
            "---.." to '8', "----." to '9', ".-.-.-" to '.', "--..--" to ',', "..--.." to '?',
            "-..-." to '/', "-...-" to '=', "-....-" to '-', ".-.-." to '+', ".--.-." to '@'
        ).toMap()
    }
}
