package com.example.sdr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.audio.HamNoiseProcessor
import com.example.sdr.audio.HamNoiseSettings
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class HamNoiseAndroidTest {
    private fun run(pcm: ShortArray, rate: Int, channels: Int, model: Int, strength: Float,
        blockFrames: Int): ShortArray = HamNoiseProcessor().use { processor ->
        val result = ShortArray(pcm.size)
        var offset = 0
        while (offset < pcm.size) {
            val count = min(pcm.size - offset, blockFrames * channels)
            val block = pcm.copyOfRange(offset, offset + count)
            val original = block.copyOf()
            val output = processor.process(block, count, rate, channels, model, HamNoiseSettings.Config(true, strength))
            assertArrayEquals("Decoder PCM must stay unchanged", original, block)
            output.copyInto(result, offset, 0, count)
            offset += count
        }
        result
    }

    @Test fun resamplingIsContinuousAndDryMixHasExpectedDelay() {
        for (rate in listOf(7997, 12000, 48000)) {
            val channels = if (rate == 48000) 2 else 1
            val pcm = ShortArray(rate * channels * 2) { i ->
                (5000 * sin(2 * PI * 700 * (i / channels) / rate)).roundToInt().toShort()
            }
            val output = run(pcm, rate, channels, 1, 0f, 293)
            val delay = .045 * rate + 31
            var error = 0.0
            for (frame in rate until rate * 2) {
                val position = frame - delay
                val left = floor(position).toInt()
                val expected = pcm[left * channels] * (1 - (position - left)) + pcm[(left + 1) * channels] * (position - left)
                error = max(error, abs(output[frame * channels] - expected))
            }
            assertTrue("Dry alignment at $rate: error $error", error <= 1.1)
            val wet = run(pcm, rate, channels, 1, 1f, 293)
            assertArrayEquals("Chunk boundaries must not affect resampling at $rate", wet,
                run(pcm, rate, channels, 1, 1f, 1024))
        }
    }

    @Test fun cwReducesNoiseAndKeepsToneWithRealtimeHeadroom() {
        val rate = 12000
        val random = Random(42)
        val pcm = ShortArray(rate * 4) { i ->
            val keyed = i % (rate / 2) < rate / 4
            ((if (keyed) 5000 * sin(2 * PI * 700 * i / rate) else 0.0) +
                (random.nextDouble() - .5) * 3000).roundToInt().toShort()
        }
        val started = System.nanoTime()
        val output = run(pcm, rate, 1, 1, 1f, 293)
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        var before = 0.0; var after = 0.0; var tone = 0.0
        val delay = (.045 * rate + 31).roundToInt()
        for (i in rate..pcm.lastIndex - delay) {
            val phase = i % (rate / 2)
            if (phase in 3600..5500) { before += pcm[i].toDouble().pow(2); after += output[i + delay].toDouble().pow(2) }
            if (phase in 500..2500) tone += output[i + delay].toDouble().pow(2)
        }
        Log.i("HamNoiseTest", "CW silence power ratio=${after / before}; tone energy=$tone; 4 s processing=$elapsed ms")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "CW noise power ratio=${after / before}; 4 s processing=$elapsed ms\n")
        })
        assertTrue("Noise must decrease", after < before)
        assertTrue("CW tone must survive", tone > after)
        assertTrue("Must keep up with live playback", elapsed < 2000)
        // Exercise the separate Voice weights and exact disabled/unsupported bypass.
        val voice = run(pcm, rate, 1, 2, .6f, 293)
        assertTrue(voice.any { it != 0.toShort() })
        HamNoiseProcessor().use { processor ->
            assertSame(pcm, processor.process(pcm, pcm.size, rate, 1, 0, HamNoiseSettings.Config(true)))
            assertSame(pcm, processor.process(pcm, pcm.size, rate, 1, 1, HamNoiseSettings.Config(false)))
        }
    }

    @Test fun voiceRecording() {
        if (InstrumentationRegistry.getArguments().getString("nr_fixture") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = File(context.filesDir, "nr-fixture.raw").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(buffer.remaining()); buffer.get(pcm)
        val result = run(pcm, 12000, 1, 2, 1f, 293)
        val out = ByteBuffer.allocate(result.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(result)
        File(context.filesDir, "nr-result.raw").writeBytes(out.array())
    }
}
