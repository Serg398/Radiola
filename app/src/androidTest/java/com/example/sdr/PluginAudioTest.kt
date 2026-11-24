package com.example.sdr

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import com.example.sdr.plugins.gainPlugin

class PluginAudioTest {
    @Test fun bundledScriptProcessesPcmInIsolatedService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as RadiolaApplication
        val manager = application.plugins
        instrumentation.runOnMainSync {
            manager.select(gainPlugin)
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
