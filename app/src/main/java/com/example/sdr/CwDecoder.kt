package com.example.sdr

import kotlin.math.*

data class CwInformation(val text: String = "", val pending: String = "", val wpm: Int = 0,
    val toneHz: Int = 0, val tonePresent: Boolean = false)

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
    private var unit = 60.0
    private var marks = 0
    private val units = ArrayDeque<Double>()
    private var tone = 0
    private val symbols = StringBuilder()
    private val text = StringBuilder()

    fun information() = CwInformation(text.toString(), symbols.toString(),
        if (marks >= 3) (1200 / unit).roundToInt() else 0, tone, keyed)

    fun process(samples: ShortArray, sampleRate: Int, channels: Int = 1) {
        require(sampleRate in 4000..48000 && channels in 1..2)
        if (rate != sampleRate) {
            rate = sampleRate; size = (rate / 100).coerceAtLeast(1)
            window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
            frequencies.indices.forEach { coefficients[it] = 2 * cos(2 * PI * frequencies[it] / rate) }
            position = 0; first.fill(0.0); second.fill(0.0); energy = 0.0
            keyed = false; candidateCount = 0; symbols.clear(); edgeAt = clock
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
                    noiseFloor = sorted[(sorted.size * .2).toInt()]
                }
                thresholdOn = max(.0008, noiseFloor + (peak - noiseFloor) * .45)
                thresholdOff = max(.0006, noiseFloor + (peak - noiseFloor) * .25)
                contrast = peak > max(.001, noiseFloor * 1.8)
                val coherence = 2 * strongest / (size * energy + 1e-20)
                val detected = contrast && coherence > (if (keyed) .32 else .45) &&
                    amplitude > if (keyed) thresholdOff else thresholdOn
                if (detected) tone = frequencies[best]
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
                if (duration in 20.0..1000.0) {
                    val dash = duration > unit * 2
                    symbols.append(if (dash) '-' else '.')
                    val estimate = duration / if (dash) 3 else 1
                    // Outliers from fades and interference must not drag the speed estimate.
                    if (estimate in unit * .55..unit * 1.8) {
                        units.addLast(estimate)
                        if (units.size > 21) units.removeFirst()
                        val ordered = units.sorted()
                        unit = (unit * .7 + ordered[ordered.size / 2] * .3).coerceIn(20.0, 240.0)
                    }
                    marks++
                    if (symbols.length > 8) finishLetter()
                } else symbols.clear()
            } else if (symbols.isNotEmpty() && duration < unit * 2) {
                unit = (unit * .85 + duration * .15).coerceIn(20.0, 240.0)
            }
            keyed = candidate; edgeAt = at
        }
        if (!keyed && !candidate) {
            val silence = clock - edgeAt
            if (silence >= unit * 2.0) finishLetter()
            if (silence >= unit * 5.5 && text.isNotEmpty() && text.last() != ' ') text.append(' ')
        }
        if (text.length > 512) text.delete(0, text.length - 512)
    }

    private fun finishLetter() {
        if (symbols.isEmpty()) return
        text.append(alphabet[symbols.toString()] ?: '·'); symbols.clear()
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
