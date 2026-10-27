package com.example.sdr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CwDecodeWorkerTest {
    @Test fun decodingRequiresUserSwitchAndActiveSource() {
        val worker = CwDecodeWorker()
        // Four rhythmic dots (H) acquire CW confidence; an isolated E is intentionally suppressed.
        val pcm = ShortArray(12000) { i ->
            val offset = i - 4800
            if (offset in 0 until 3360 && offset % 960 < 480)
                (4000 * sin(2 * PI * 700 * i / 8000)).toInt().toShort() else 0
        }
        try {
            worker.configure(true, 7_030_000)
            worker.offer(pcm, pcm.size, 8000, 1)
            assertFalse(worker.decodingEnabled)
            assertEquals("", worker.snapshot().text)
            worker.requestDecoding(true)
            worker.offer(pcm, pcm.size, 8000, 1)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (worker.snapshot().text.isBlank() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("H", worker.snapshot().text.trim())
            worker.requestDecoding(false)
            worker.offer(pcm, pcm.size, 8000, 1)
            assertEquals("", worker.snapshot().text)
            worker.configure(false, 7_030_000)
            worker.requestDecoding(true)
            worker.offer(pcm, pcm.size, 8000, 1)
            assertEquals("", worker.snapshot().text)
        } finally { worker.close() }
    }
}
