package com.example.sdr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random
import java.util.zip.GZIPInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CwDecoderTest {
    private val codes = mapOf('C' to "-.-.", 'Q' to "--.-", 'S' to "...", 'O' to "---", 'T' to "-", 'E' to ".", '5' to ".....", '9' to "----.")
    private fun decode(message: String, rate: Int, wpm: Int, tone: Int, channels: Int = 1, noise: Double = .0): CwInformation {
        val decoder = CwDecoder()
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
