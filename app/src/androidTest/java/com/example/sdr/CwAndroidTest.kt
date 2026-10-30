package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.decoders.cw.CwDecodeWorker
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CwAndroidTest {
    @Test fun workerDecodesFarnsworthAndStretchedDashesOnThePhone() {
        val pcm = ArrayList<Short>()
        var phase = 0.0
        fun segment(units: Double, mark: Boolean) {
            repeat((units * .06 * 8000).roundToInt()) {
                pcm.add((if (mark) 4000 * sin(phase) else 0.0).roundToInt().toShort())
                phase += 2 * PI * 700 / 8000
            }
        }
        segment(10.0, false)
        for (pattern in listOf("-.-.", "--.-")) {
            pattern.forEachIndexed { index, symbol ->
                segment(if (symbol == '-') 4.0 else 1.0, true)
                if (index < pattern.lastIndex) segment(1.0, false)
            }
            segment(7.0, false)
        }
        segment(20.0, false)
        val worker = CwDecodeWorker()
        try {
            worker.configure(true, 7_030_000)
            worker.requestDecoding(true)
            val input = pcm.toShortArray()
            worker.offer(input, input.size, 8000, 1)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (worker.snapshot().text.trim() != "CQ" && System.nanoTime() < deadline) Thread.sleep(10)
            val result = worker.snapshot()
            assertEquals("CQ", result.text.trim())
            assertEquals(result.text.length, result.characters.size)
            assertEquals(20.0, result.wpm.toDouble(), 3.0)
        } finally { worker.close() }
    }
}
