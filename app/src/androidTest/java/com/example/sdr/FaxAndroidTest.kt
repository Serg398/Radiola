package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.decoders.fax.FaxDecodeWorker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class FaxAndroidTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("FAX worker timed out", condition())
    }
    @Test fun immediateDecodeImmutableTilesAndLifecycle() {
        FaxDecodeWorker().use { worker ->
            worker.configure(true, 3_613_000)
            assertFalse(worker.acceptsAudio())
            worker.enable(true)
            var phase = 0.0
            val pcm = ShortArray(8000 * 3) { i ->
                val frequency = if (i % 4000 < 2000) 1500 else 2300
                phase += 2 * PI * frequency / 8000
                (20000 * cos(phase)).roundToInt().toShort()
            }
            worker.offer(pcm, pcm.size, 8000, 1)
            await { worker.snapshot().lines == 6 }
            val original = worker.snapshot()
            assertEquals(6, original.pages.sumOf { it.height })
            assertFalse(original.pages.first().isMutable)
            assertTrue(original.pages.first().getPixel(800, 4) and 255 < 30)
            worker.offer(pcm, pcm.size, 8000, 1)
            await { worker.snapshot().lines == 12 }
            assertEquals(6, original.pages.first().height)
            assertTrue("Published tiles must remain immutable", original.pages.first().getPixel(800, 4) and 255 < 30)
            worker.enable(false)
            assertFalse(worker.acceptsAudio())
            worker.configure(false, 3_613_000)
            await { worker.snapshot().status == "Stopped" }
            assertEquals(12, worker.snapshot().lines)
            worker.configure(true, 3_614_000)
            await { worker.snapshot().lines == 0 }
        }
    }
}
