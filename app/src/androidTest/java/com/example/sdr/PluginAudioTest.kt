package com.example.sdr

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import com.example.sdr.plugins.PluginCardSize
import com.example.sdr.plugins.PluginManifest
import com.example.sdr.plugins.PluginPort

class PluginAudioTest {
    @Test fun packageScriptProcessesPcmInIsolatedService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as RadiolaApplication
        val manager = application.plugins
        instrumentation.runOnMainSync {
            manager.select(PluginManifest(
                "test.gain", "Gain test", "1", PluginPort.PCM, PluginCardSize.COMPACT,
                """
                function processPcm(samples, gain) {
                    for (var i = 0; i < samples.length; i++) {
                        samples[i] = Math.max(-32768, Math.min(32767, samples[i] * gain));
                    }
                }
                """.trimIndent(),
                emptyMap(),
            ))
            manager.updateGain(1.5)
            manager.play()
        }
        try {
            val input = shortArrayOf(-24000, -2000, 0, 2000, 24000)
            val expected = shortArrayOf(-32768, -3000, 0, 3000, 32767)
            var transformed: ShortArray? = null
            val deadline = System.nanoTime() + 3_000_000_000
            while (transformed == null && System.nanoTime() < deadline) {
                val block = manager.submit(input)
                if (block != null) {
                    block.ready.await(1, java.util.concurrent.TimeUnit.SECONDS)
                    transformed = manager.output(block)
                }
                if (transformed == null) Thread.sleep(20)
            }
            assertArrayEquals(expected, transformed)
        } finally {
            instrumentation.runOnMainSync { manager.select(null) }
        }
    }
}
