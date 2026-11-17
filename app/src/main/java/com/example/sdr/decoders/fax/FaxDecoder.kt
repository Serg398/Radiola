package com.example.sdr.decoders.fax

import kotlin.math.*

/** Streaming USB audio -> FM subcarrier -> grayscale scan lines. No Android dependencies. */
internal class FaxDecoder(
    val lpm: Int = 120, val ioc: Int = 576,
    private val onStart: () -> Unit = {},
    private val onLine: (ByteArray) -> Unit,
) {
    val width = (PI * ioc).toInt()
    var status = "Waiting for start"; private set
    var receiving = false; private set
    private var phasing = false
    private var phaseLines = 0
    private var missedPhase = 0
    private var alignment = 0
    private val lineSamples = 8000.0 * 60 / lpm
    private var linePosition = 0.0
    private val sums = DoubleArray(width)
    private val counts = IntArray(width)
    private var sourceIndex = 0L
    private var nextSample = 0.0
    private var previous = 0.0
    private var inputRate = 0
    private var oscillator = 0
    private val cosines = DoubleArray(160) { cos(2 * PI * 38 * it / 160) }
    private val sines = DoubleArray(160) { -sin(2 * PI * 38 * it / 160) }
    private val real = DoubleArray(3)
    private val imaginary = DoubleArray(3)
    private val alpha = 1 - exp(-2 * PI * 700 / 8000)
    private var lastReal = 0.0
    private var lastImaginary = 0.0
    private var aptIndex = 0
    private var aptSamples = 0
    private var aptPower = 0.0
    private var energy = 0.0
    private val aptReal = DoubleArray(2)
    private val aptImaginary = DoubleArray(2)
    private var startWindows = 0
    private var stopWindows = 0
    private val aptCos = Array(2) { kind -> DoubleArray(160) { cos(2 * PI * (if (kind == 0) 6 else 9) * it / 160) } }
    private val aptSin = Array(2) { kind -> DoubleArray(160) { sin(2 * PI * (if (kind == 0) 6 else 9) * it / 160) } }

    init { require(lpm in listOf(60, 90, 120, 240) && ioc in listOf(288, 576)) }

    fun manualStart() {
        onStart(); receiving = true; phasing = false; alignment = 0
        linePosition = 0.0; sums.fill(0.0); counts.fill(0)
        status = "Receiving"
    }
    fun stop() { receiving = false; phasing = false; status = "Stopped" }

    fun process(samples: ShortArray, rate: Int, channels: Int) {
        require(rate in 4000..48000 && channels in 1..2 && samples.size % channels == 0)
        if (inputRate != 0 && inputRate != rate) {
            // Do not silently stitch unrelated sample clocks into one scan line.
            stop(); status = "Audio rate changed — restart Decode"
            sourceIndex = 0; nextSample = 0.0; previous = 0.0
            real.fill(0.0); imaginary.fill(0.0); lastReal = 0.0; lastImaginary = 0.0
        }
        inputRate = rate
        for (i in 0 until samples.size / channels) {
            val value = if (channels == 1) samples[i] / 32768.0 else (samples[2 * i].toInt() + samples[2 * i + 1]) / 65536.0
            while (nextSample <= sourceIndex) {
                val fraction = (nextSample - sourceIndex + 1).coerceIn(0.0, 1.0)
                demodulate(previous + (value - previous) * fraction)
                nextSample += rate / 8000.0
            }
            previous = value; sourceIndex++
        }
    }

    private fun demodulate(value: Double) {
        var r = value * cosines[oscillator]; var q = value * sines[oscillator]
        oscillator = (oscillator + 1) % 160
        for (stage in 0..2) {
            real[stage] += alpha * (r - real[stage]); imaginary[stage] += alpha * (q - imaginary[stage])
            r = real[stage]; q = imaginary[stage]
        }
        val delta = atan2(q * lastReal - r * lastImaginary, r * lastReal + q * lastImaginary)
        lastReal = r; lastImaginary = q
        val gray = ((delta * 8000 / (2 * PI) + 400) * 255 / 800).coerceIn(0.0, 255.0)
        val normalized = (gray - 127.5) / 127.5
        aptPower += normalized * normalized; energy += r * r + q * q
        for (kind in 0..1) {
            aptReal[kind] += normalized * aptCos[kind][aptIndex]
            aptImaginary[kind] += normalized * aptSin[kind][aptIndex]
        }
        aptIndex = (aptIndex + 1) % 160
        if (++aptSamples == 4000) {
            val scores = DoubleArray(2) { 2 * (aptReal[it].pow(2) + aptImaginary[it].pow(2)) / max(1e-10, aptSamples * aptPower) }
            val valid = energy / aptSamples > 1e-7
            startWindows = if (valid && scores[0] > .45) startWindows + 1 else 0
            stopWindows = if (valid && scores[1] > .45) stopWindows + 1 else 0
            if (startWindows == 2 && !phasing) {
                onStart(); receiving = true; phasing = true; phaseLines = 0; missedPhase = 0
                alignment = 0; linePosition = 0.0; sums.fill(0.0); counts.fill(0); status = "Phasing"
            }
            if (stopWindows == 2 && receiving) { receiving = false; phasing = false; status = "Complete" }
            aptSamples = 0; aptPower = 0.0; energy = 0.0; aptReal.fill(0.0); aptImaginary.fill(0.0)
        }
        if (!receiving) return
        val pixel = (linePosition * width / lineSamples).toInt().coerceIn(0, width - 1)
        sums[pixel] += gray; counts[pixel]++
        linePosition++
        if (linePosition >= lineSamples) {
            linePosition -= lineSamples
            val row = ByteArray(width) { (if (counts[it] > 0) sums[it] / counts[it] else 127.0).roundToInt().coerceIn(0, 255).toByte() }
            sums.fill(0.0); counts.fill(0)
            if (phasing) {
                val dark = row.indices.filter { (row[it].toInt() and 255) < 80 }
                val white = row.count { (it.toInt() and 255) > 180 }
                if (dark.size in (width * .02).toInt()..(width * .09).toInt() && white > width * .85) {
                    // A black phasing pulse can wrap across the arbitrary initial row boundary.
                    val start = row.indices.firstOrNull { (row[it].toInt() and 255) < 80 && (row[(it + width - 1) % width].toInt() and 255) >= 80 }
                    if (start != null) { alignment = start; phaseLines++; missedPhase = 0 }
                } else missedPhase++
                if (phaseLines >= 3 && missedPhase >= 2) { phasing = false; status = "Receiving" }
                // If the header was damaged, manual Start is always available.
            }
            if (!phasing) onLine(ByteArray(width) { row[(it + alignment) % width] })
        }
    }
}
