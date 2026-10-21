package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

class WebSdrControlTest {
    @Test fun continuousInputDeliversDuringMotionAndRetainsFinalValue() {
        var now = 0L
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        val sent = mutableListOf<Pair<Long, Int>>()
        val gate = LatestCommand<Int>(33, { now }, { task, delay -> tasks += now + delay to task },
            { task -> tasks.removeAll { it.second === task } }) { sent += now to it }
        fun tick() {
            while (tasks.any { it.first <= now }) {
                val task = tasks.first { it.first <= now }; tasks.remove(task); task.second.run()
            }
        }
        repeat(1000) { gate.offer(it); tick(); now++ }
        repeat(34) { tick(); now++ }
        assertEquals(999, sent.last().second)
        assertTrue(sent.size in 30..32)
        assertTrue(sent.zipWithNext().all { (a, b) -> b.first - a.first >= 33 })
        assertTrue(sent.any { it.first in 300..700 })
        gate.offer(2000); gate.clear(); now += 100; tick()
        assertEquals(999, sent.last().second)
    }

    @Test fun pcmQueueBoundsTimeAtDifferentServerRates() {
        for (rate in listOf(4000, 7119, 8000, 48000)) {
            val queue = WebSdrPcmQueue(160)
            repeat(200) { queue.offer(WebSdrPcmQueue.Block(rate, ShortArray(128) { it.toShort() })) }
            assertTrue("rate=$rate queued=${queue.milliseconds()}", queue.milliseconds() <= 160)
            assertTrue(queue.droppedUs > 0)
            var samples = 0
            while (true) { val block = queue.poll() ?: break; samples += block.samples.size }
            assertTrue(samples * 1000L / rate <= 160)
            assertEquals(0, queue.milliseconds())
        }
    }

    @Test fun onlineBandwidthLimitsMatchActualFilterEdges() {
        val band = WebSdrTunerBand(0, "80m", 3650000, 512000, 3660000, 5, 8000)
        assertEquals(15200, WebSdrBandwidth.maximum(DemodulationMode.AM, band))
        assertEquals(7450, WebSdrBandwidth.maximum(DemodulationMode.USB, band))
        assertEquals(30000, WebSdrBandwidth.maximum(DemodulationMode.NFM, band))
        for (mode in DemodulationMode.entries.filter { it != DemodulationMode.WFM }) {
            val maximum = WebSdrBandwidth.maximum(mode, band)
            val options = WebSdrBandwidth.options(mode, maximum)
            assertEquals(WebSdrBandwidth.default(mode).coerceAtMost(maximum), options.defaultHz)
            assertTrue(options.presets.all { it in options.minimumHz..options.maximumHz })
        }
    }
}
