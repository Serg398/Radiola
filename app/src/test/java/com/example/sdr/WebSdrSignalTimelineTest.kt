package com.example.sdr

import com.example.sdr.audio.WebSdrPcmQueue
import com.example.sdr.audio.WebSdrSignalTimeline
import org.junit.Assert.*
import org.junit.Test

class WebSdrSignalTimelineTest {
    @Test fun meterWaitsUntilAssociatedAudioActuallyPlays() {
        val meter = WebSdrSignalTimeline()
        meter.record(0, -90f)
        meter.record(128, -55f)
        assertNull(meter.advance(0))
        assertEquals(-90f, meter.advance(1))
        assertEquals(-90f, meter.advance(128))
        assertEquals(-55f, meter.advance(129))
    }
    @Test fun jumpsFollowLatestPlayedBlockAndDoNotExposeFutureSignal() {
        val meter = WebSdrSignalTimeline()
        meter.record(0, -90f)
        meter.record(128, -60f)
        meter.record(256, -40f)
        assertEquals(-60f, meter.advance(200))
        assertEquals(-60f, meter.advance(200))
        assertEquals(-40f, meter.advance(400))
    }
    @Test fun recoveryDiscardsMarkersFromOldTrack() {
        val meter = WebSdrSignalTimeline()
        meter.record(0, -90f)
        meter.record(128, -40f)
        meter.advance(1)
        meter.clear()
        assertNull(meter.advance(1000))
        meter.record(0, -70f)
        assertEquals(-70f, meter.advance(1))
    }
    @Test fun droppedQueueBlocksDropTheirMeterValuesToo() {
        val queue = WebSdrPcmQueue(16)
        queue.offer(WebSdrPcmQueue.Block(8000, ShortArray(128), -40f))
        queue.offer(WebSdrPcmQueue.Block(8000, ShortArray(128), -90f))
        val meter = WebSdrSignalTimeline()
        meter.record(0, queue.poll()!!.signalDbm)
        assertEquals(-90f, meter.advance(1))
    }
}
