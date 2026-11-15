package com.example.sdr

import com.example.sdr.receiver.SpectrumFrameQueue
import org.junit.Assert.*
import org.junit.Test

class SpectrumFrameQueueTest {
    @Test fun shortBurstRetainsFramesInOrder() {
        val queue = SpectrumFrameQueue<Int>()
        repeat(3) { queue.offer(it, 100) }
        assertEquals(0, queue.poll(100))
        assertEquals(1, queue.poll(134))
        assertEquals(2, queue.poll(168))
        assertNull(queue.poll(202))
        assertEquals(0, queue.takeDropped())
    }
    @Test fun overloadKeepsNewestThreeFrames() {
        val queue = SpectrumFrameQueue<Int>()
        repeat(100) { queue.offer(it, 100) }
        assertEquals(97, queue.takeDropped())
        assertEquals(listOf(97, 98, 99), List(3) { queue.poll(134) })
    }
    @Test fun stalledUiDoesNotReplayOldFrames() {
        val queue = SpectrumFrameQueue<Int>()
        queue.offer(1, 0)
        queue.offer(2, 200)
        assertEquals(2, queue.poll(300))
        assertEquals(1, queue.takeDropped())
        queue.offer(3, 400)
        assertNull(queue.poll(651))
    }
    @Test fun clearPreventsPreviousSessionFramesFromLeaking() {
        val queue = SpectrumFrameQueue<Int>()
        queue.offer(1, 0)
        queue.clear()
        queue.offer(2, 10)
        assertEquals(2, queue.poll(20))
        assertNull(queue.poll(20))
    }
}
