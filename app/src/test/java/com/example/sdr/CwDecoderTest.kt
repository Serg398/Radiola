package com.example.sdr

import com.example.sdr.decoders.cw.CwDecoder
import com.example.sdr.decoders.cw.CwInformation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CwDecoderTest {
    private val codes = mapOf('C' to "-.-.", 'Q' to "--.-", 'S' to "...", 'O' to "---", 'T' to "-", 'E' to ".", '5' to ".....", '9' to "----.")
    private fun decode(message: String, rate: Int, wpm: Int, tone: Int, channels: Int = 1, noise: Double = .0,
        decoder: CwDecoder = CwDecoder()): CwInformation {
        val random = Random(32)
        var phase = 0.0
        fun segment(units: Int, keyed: Boolean) {
            val count = (units * 1.2 / wpm * rate).roundToInt()
            var remaining = count
            while (remaining > 0) {
                val frames = min(137, remaining)
                val pcm = ShortArray(frames * channels)
                repeat(frames) { i ->
                    val value = ((if (keyed) .12 * sin(phase) else 0.0) + noise * (random.nextDouble() * 2 - 1))
                    phase += 2 * PI * tone / rate
                    repeat(channels) { pcm[i * channels + it] = (value * 32767).toInt().toShort() }
                }
                decoder.process(pcm, rate, channels); remaining -= frames
            }
        }
        segment(10, false)
        message.forEachIndexed { index, char ->
            if (char == ' ') return@forEachIndexed
            val code = codes.getValue(char)
            code.forEachIndexed { i, symbol ->
                segment(if (symbol == '-') 3 else 1, true)
                if (i < code.lastIndex) segment(1, false)
            }
            segment(if (message.getOrNull(index + 1) == ' ') 7 else 3, false)
        }
        segment(10, false)
        return decoder.information()
    }

    @Test fun usbStereoCw() {
        val result = decode("CQ CQ SOS 599", 48000, 20, 700, 2)
        assertEquals("CQ CQ SOS 599", result.text.trim())
        assertEquals(20.0, result.wpm.toDouble(), 2.0)
        assertEquals(700, result.toneHz)
    }
    @Test fun webSdrVariableSampleRateAndNoise() {
        for (rate in listOf(7119, 8000, 12000)) {
            val result = decode("SOS TEST", rate, 28, 750, noise = .035)
            assertEquals("rate=$rate", "SOS TEST", result.text.trim())
        }
    }
    @Test fun slowerSending() {
        assertEquals("CQ SOS", decode("CQ SOS", 8000, 12, 600).text.trim())
    }
    @Test fun silenceAndBroadbandNoiseDoNotProduceText() {
        val decoder = CwDecoder()
        val random = Random(123)
        repeat(200) { decoder.process(ShortArray(800) { random.nextInt(-2000, 2001).toShort() }, 8000) }
        decoder.process(ShortArray(16000), 8000)
        assertEquals("", decoder.information().text)
    }

    @Test fun isolatedTonalNoiseBurstsDoNotBecomeE() {
        val decoder = CwDecoder()
        repeat(12) {
            decoder.process(ShortArray(8000), 8000)
            decoder.process(ShortArray(320) { i -> (.08 * sin(2 * PI * 750 * i / 8000) * 32767).toInt().toShort() }, 8000)
        }
        decoder.process(ShortArray(16000), 8000)
        assertEquals("", decoder.information().text.trim())
    }

    @Test fun narrowFilteredReceiverNoiseDoesNotProduceText() {
        val decoder = CwDecoder()
        val random = Random(89)
        var inPhase = 0.0; var quadrature = 0.0; var phase = 0.0
        repeat(300) {
            val pcm = ShortArray(800) {
                inPhase = .96 * inPhase + .04 * (random.nextDouble() * 2 - 1)
                quadrature = .96 * quadrature + .04 * (random.nextDouble() * 2 - 1)
                phase += 2 * PI * 750 / 8000
                ((inPhase * cos(phase) + quadrature * sin(phase)) * 10000).toInt().toShort()
            }
            decoder.process(pcm, 8000)
        }
        decoder.process(ShortArray(16000), 8000)
        assertEquals("", decoder.information().text.trim())
    }

    @Test fun automaticSpeedCoversFastAndSlowSending() {
        for (wpm in listOf(8, 12, 20, 28, 35, 40)) {
            val result = decode("CQ CQ SOS TEST 599", 8000, wpm, 700, noise = .015)
            assertEquals("wpm=$wpm", "CQ CQ SOS TEST 599", result.text.trim())
            assertEquals("wpm=$wpm", wpm.toDouble(), result.wpm.toDouble(), 3.0)
        }
    }

    @Test fun noiseAfterTransmissionDoesNotAppendLetters() {
        val decoder = CwDecoder()
        val before = decode("CQ CQ SOS", 8000, 20, 750, decoder = decoder).text.trim()
        val random = Random(91)
        var a = 0.0; var b = 0.0; var phase = 0.0
        repeat(100) {
            decoder.process(ShortArray(800) {
                a = .96 * a + .04 * (random.nextDouble() * 2 - 1)
                b = .96 * b + .04 * (random.nextDouble() * 2 - 1)
                phase += 2 * PI * 750 / 8000
                ((a * cos(phase) + b * sin(phase)) * 10000).toInt().toShort()
            }, 8000)
        }
        decoder.process(ShortArray(16000), 8000)
        assertEquals(before, decoder.information().text.trim())
    }

    @Test fun recordedWebSdrNoiseDoesNotCollapseTiming() {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/cw/websdr-3544khz-8000.pcm.gz")!!).use { it.readBytes() }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val decoder = CwDecoder()
        while (input.hasRemaining()) {
            val block = ShortArray(min(137, input.remaining()))
            input.get(block); decoder.process(block, 8000)
        }
        val result = decoder.information()
        assertTrue(result.text, result.text.contains("RST"))
        assertTrue("Speed ${result.wpm}", result.wpm in 18..26)
        assertTrue(result.text, result.text.count { it == '·' } <= 3)
    }
}
