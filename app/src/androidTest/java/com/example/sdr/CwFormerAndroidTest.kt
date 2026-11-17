package com.example.sdr

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.decoders.cw.CwFormerDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class CwFormerAndroidTest {
    @Test fun streamingStateResamplingAndReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val patterns = mapOf('C' to "-.-.", 'Q' to "--.-", 'T' to "-", 'E' to ".", 'S' to "...", 'D' to "-..")
        for (rate in listOf(8000, 12000, 48000)) CwFormerDecoder(context).use { decoder ->
            val samples = ArrayList<Short>()
            fun segment(units: Int, mark: Boolean) {
                repeat((units * .06 * rate).roundToInt()) {
                    samples.add((if (mark) 22000 * sin(2 * PI * 700 * samples.size / rate) else 0.0).roundToInt().toShort())
                }
            }
            val expected = "CQ TEST DE CQ TEST"
            segment(10, false)
            for (char in expected) {
                if (char == ' ') segment(4, false)
                else {
                    patterns.getValue(char).forEachIndexed { j, symbol ->
                        segment(if (symbol == '-') 3 else 1, true)
                        if (j < patterns.getValue(char).lastIndex) segment(1, false)
                    }
                    segment(3, false)
                }
            }
            segment(100, false)
            val pcm = samples.toShortArray()
            var maxMs = 0L
            var position = 0
            while (position < pcm.size) {
                val end = min(pcm.size, position + 997)
                val block = pcm.copyOfRange(position, end)
                if (rate == 48000) decoder.process(ShortArray(block.size * 2) { block[it / 2] }, rate, 2)
                else decoder.process(block, rate, 1)
                position = end
                maxMs = max(maxMs, decoder.inferenceMs)
            }
            Log.i("CwFormerTest", "$rate Hz: ${decoder.information()}, max inference $maxMs ms")
            assertEquals("rate=$rate", expected, decoder.information().text.trim())
            assertTrue("Must keep up with 500 ms chunks: $maxMs ms", maxMs < 500)
            decoder.reset()
            decoder.process(ShortArray(rate * 7), rate, 1)
            assertEquals("", decoder.information().text.trim())
        }
    }
}
