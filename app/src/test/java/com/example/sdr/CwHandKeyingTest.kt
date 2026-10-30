package com.example.sdr

import com.example.sdr.decoders.cw.CwDecoder
import com.example.sdr.decoders.cw.CwInformation
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CwHandKeyingTest {
    private val codes = mapOf('C' to "-.-.", 'Q' to "--.-", 'D' to "-..", 'E' to ".",
        'T' to "-", 'S' to "...", '5' to ".....", '9' to "----.")

    private fun receive(message: String, wpm: Int = 20, dash: Double = 3.0,
        intra: Double = 1.0, letter: Double = 3.0, word: Double = 7.0,
        jitter: Double = 0.0, noise: Double = .012, fading: Boolean = false): CwInformation {
        val decoder = CwDecoder()
        val random = Random(307)
        val rate = 8000
        var phase = 0.0
        fun segment(units: Double, mark: Boolean) {
            var remaining = (units * 1.2 / wpm * rate *
                (1 + jitter * (random.nextDouble() * 2 - 1))).roundToInt()
            while (remaining > 0) {
                val count = min(257, remaining)
                val pcm = ShortArray(count) {
                    val gain = if (fading) .65 + .35 * sin(phase / 1400) else 1.0
                    val sample = (if (mark) .12 * gain * sin(phase) else 0.0) + noise * (random.nextDouble() * 2 - 1)
                    phase += 2 * PI * 700 / rate
                    (sample * 32767).roundToInt().toShort()
                }
                decoder.process(pcm, rate); remaining -= count
            }
        }
        segment(10.0, false)
        message.forEachIndexed { index, char ->
            if (char == ' ') return@forEachIndexed
            val code = codes.getValue(char)
            code.forEachIndexed { i, symbol ->
                segment(if (symbol == '-') dash else 1.0, true)
                if (i < code.lastIndex) segment(intra, false)
            }
            segment(if (message.getOrNull(index + 1) == ' ') word else letter, false)
        }
        segment(30.0, false)
        return decoder.information()
    }

    @Test fun stretchedDashesAndUnevenHandKeying() {
        val message = "CQ CQ DE TEST 599"
        val result = receive(message, dash = 4.2, intra = 1.15, letter = 3.6, word = 8.5, jitter = .18)
        assertEquals(message, result.text.trim())
        assertEquals(20.0, result.wpm.toDouble(), 3.0)
    }

    @Test fun farnsworthGapsDoNotTurnEveryLetterIntoAWord() {
        val message = "CQ CQ DE TEST 599"
        val result = receive(message, wpm = 28, letter = 7.0, word = 16.0, jitter = .08)
        assertEquals(message, result.text.trim())
        assertEquals(28.0, result.wpm.toDouble(), 3.0)
    }

    @Test fun shortDashesAndLooseElementSpacing() {
        val message = "CQ CQ TEST 599"
        assertEquals(message, receive(message, wpm = 16, dash = 2.4, intra = 1.4,
            letter = 4.0, word = 10.0, jitter = .12).text.trim())
    }

    @Test fun characterMetadataMatchesDecodedText() {
        val result = receive("CQ CQ TEST 599")
        assertEquals(result.text, result.characters.joinToString("") { it.value.toString() })
        assertTrue(result.characters.all { it.confidence in 0..100 })
        assertTrue(result.characters.filter { it.value != ' ' }.all { it.confidence >= 80 })
    }

    @Test fun operatorTimingWorksAtSeveralSpeeds() {
        val message = "CQ CQ TEST 599"
        for (wpm in listOf(12, 20, 28)) {
            val result = receive(message, wpm, dash = 4.0, letter = 5.0, word = 12.0, jitter = .12)
            assertEquals("wpm=$wpm", message, result.text.trim())
            assertEquals("wpm=$wpm", wpm.toDouble(), result.wpm.toDouble(), 3.0)
        }
    }

    @Test fun fadingAndNoiseKeepTheHandKeyedMessageReadable() {
        val message = "CQ CQ TEST 599"
        assertEquals(message, receive(message, dash = 3.6, letter = 4.0, word = 10.0,
            jitter = .12, noise = .02, fading = true).text.trim())
    }

    @Test fun textAndConfidenceHistoryStayBoundedTogether() {
        val result = receive("CQ " + "TEST ".repeat(140), wpm = 28, noise = 0.0)
        assertTrue(result.text.length <= 512)
        assertEquals(result.text.length, result.characters.size)
        assertEquals(result.text, result.characters.joinToString("") { it.value.toString() })
    }
}
