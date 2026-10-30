package com.example.sdr

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.decoders.cw.DeepCwDecoder
import com.example.sdr.decoders.cw.CwDecodeWorker
import com.example.sdr.decoders.cw.CwBackend
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepCwAndroidTest {
    @Test fun decodesStreamingPcmAcrossWindowsAndResets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val patterns = mapOf('C' to "-.-.", 'Q' to "--.-", 'T' to "-", 'E' to ".", 'S' to "...", 'D' to "-..")
        for (rate in listOf(8000, 12000, 48000)) DeepCwDecoder(context).use { decoder ->
            val samples = ArrayList<Short>()
            fun segment(units: Int, mark: Boolean) {
                repeat((units * .06 * rate).roundToInt()) {
                    samples.add((if (mark) 7000 * sin(2 * PI * 700 * samples.size / rate) else 0.0).roundToInt().toShort())
                }
            }
            segment(10, false)
            val expected = "CQ TEST DE CQ TEST"
            expected.forEachIndexed { index, char ->
                if (char == ' ') segment(4, false)
                else {
                    patterns.getValue(char).forEachIndexed { j, symbol ->
                        segment(if (symbol == '-') 3 else 1, true)
                        if (j < patterns.getValue(char).lastIndex) segment(1, false)
                    }
                    segment(3, false)
                }
            }
            segment(70, false)
            val pcm = samples.toShortArray()
            var position = 0
            var maxMs = 0L
            while (position < pcm.size) {
                val end = min(pcm.size, position + 997)
                val block = pcm.copyOfRange(position, end)
                if (rate == 48000) decoder.process(ShortArray(block.size * 2) { block[it / 2] }, rate, 2)
                else decoder.process(block, rate, 1)
                maxMs = max(maxMs, decoder.inferenceMs)
                position = end
            }
            Log.i("DeepCwTest", "$rate Hz: ${decoder.information()}, max inference $maxMs ms")
            assertEquals("rate=$rate", expected, decoder.information().text.trim())
            assertTrue("Must keep up with real time: $maxMs ms", maxMs < 1000)
            decoder.reset()
            decoder.process(ShortArray(rate * 5), rate, 1)
            assertEquals("", decoder.information().text.trim())
            if (rate == 8000) {
                val worker = CwDecodeWorker(context)
                try {
                    worker.selectBackend(CwBackend.DEEPCW)
                    worker.configure(true, 7_030_000)
                    worker.requestDecoding(true)
                    worker.offer(pcm, pcm.size, rate, 1)
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (worker.snapshot().text.trim() != expected && System.nanoTime() < deadline) Thread.sleep(20)
                    assertEquals(worker.snapshot().status, expected, worker.snapshot().text.trim())
                    worker.configure(true, 7_031_000)
                    assertEquals("", worker.snapshot().text)
                    worker.requestDecoding(false)
                    assertFalse(worker.acceptsAudio())
                } finally { worker.close() }
            }
        }
    }
}
