package com.example.sdr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CwDecodeWorkerTest {
    @Test fun decodingRequiresUserSwitchAndActiveSource() {
        val worker = CwDecodeWorker()
        // One E at 20 WPM, preceded by silence for noise estimation.
        val pcm = ShortArray(10080) { i ->
            if (i in 4800 until 5280) (4000 * sin(2 * PI * 700 * i / 8000)).toInt().toShort() else 0
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
            assertEquals("E", worker.snapshot().text.trim())
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
