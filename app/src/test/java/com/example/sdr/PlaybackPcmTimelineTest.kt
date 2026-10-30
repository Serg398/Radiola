package com.example.sdr

import com.example.sdr.audio.PlaybackPcmTimeline
import org.junit.Assert.*
import org.junit.Test

class PlaybackPcmTimelineTest {
    @Test fun onlyPlayedSamplesReachDecoderAndAreNotRepeated() {
        val timeline = PlaybackPcmTimeline(1)
        timeline.record(0, shortArrayOf(10, 20, 30, 40), 0, 4)
        val output = mutableListOf<Short>()
        timeline.advance(0) { output.addAll(it.toList()) }
        assertTrue(output.isEmpty())
        timeline.advance(2) { output.addAll(it.toList()) }
        assertEquals(listOf<Short>(10, 20), output)
        timeline.advance(2) { fail("Repeated PCM") }
        timeline.advance(4) { output.addAll(it.toList()) }
        assertEquals(listOf<Short>(10, 20, 30, 40), output)
    }
    @Test fun stereoWritesUseFramesAndCopyMutablePlaybackBuffer() {
        val timeline = PlaybackPcmTimeline(2)
        val samples = shortArrayOf(1, 2, 3, 4, 5, 6)
        timeline.record(100, samples, 2, 4)
        samples.fill(0)
        timeline.advance(100) { fail("Not played") }
        timeline.advance(101) { assertArrayEquals(shortArrayOf(3, 4), it) }
        timeline.advance(102) { assertArrayEquals(shortArrayOf(5, 6), it) }
    }
    @Test fun flushDiscardsAudioFromOldTrack() {
        val timeline = PlaybackPcmTimeline(1)
        timeline.record(0, shortArrayOf(1, 2), 0, 2)
        timeline.clear()
        timeline.advance(100) { fail("Stale audio") }
        timeline.record(0, shortArrayOf(9), 0, 1)
        timeline.advance(1) { assertArrayEquals(shortArrayOf(9), it) }
    }
}
