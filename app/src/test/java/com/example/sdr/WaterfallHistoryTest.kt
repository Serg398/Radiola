package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

class WaterfallHistoryTest {
    @Test fun zoomAndPanRevealASignalOutsideTheOriginalCentralCrop() {
        val history = WaterfallHistory(16, 4)
        val data = FloatArray(16) { if (it == 14) -25f else -94f }
        history.append(frame(100000, 1).copy(bins = data))
        val row = history.rowAtAge(0)
        val bin = history.binAtFrequency(row, 100600.0)
        val screenFraction = history.imageLeftFraction(row, 100600.0, 400) + bin / 16 * 1600 / 400
        assertEquals(.5, screenFraction, .001)
        assertEquals(255, history.levelAt(row, bin.toInt()))
    }
    private fun frame(center: Long, generation: Long, session: Long = 1) =
        SpectrumFrame(FloatArray(16) { if (it == 8) -25f else -94f }, center, generation, generation * 1000, session, 1600)

    @Test fun captureMetadataStaysWithItsOwnRowAfterTuning() {
        val history = WaterfallHistory(16, 4)
        history.append(frame(100000, 1)); history.append(frame(100400, 2))
        val older = history.rowAtAge(1)
        assertEquals(100000L, history.centerAt(older))
        assertEquals(1000L, history.timestampAt(older))
        assertEquals(8.0, history.binAtFrequency(older, 100000.0), .001)
        // A narrowed, shifted viewport still includes captured data outside the old central crop.
        val left = history.imageLeftFraction(older, 100600.0, 400)
        assertEquals(-3.0, left, .001)
        assertEquals(14.0, history.binAtFrequency(older, 100600.0), .001)
        assertEquals(255, history.levelAt(older, 8))
    }
    @Test fun circularHistoryRetainsLatestRowsAndOwnsData() {
        val history = WaterfallHistory(16, 3)
        val first = frame(100, 1); history.append(first); first.bins.fill(-94f)
        assertEquals(255, history.levelAt(history.rowAtAge(0), 8))
        for (n in 2..5) history.append(frame(n.toLong(), n.toLong()))
        assertEquals(3, history.size)
        assertEquals(listOf(5L, 4L, 3L), (0..2).map { history.centerAt(history.rowAtAge(it)) })
    }
    @Test fun duplicateAndLateFramesCannotCorruptHistoryAcrossSessions() {
        val history = WaterfallHistory(16, 4)
        assertTrue(history.append(frame(100, 2)))
        assertFalse(history.append(frame(200, 2)))
        assertFalse(history.append(frame(200, 1)))
        assertTrue(history.append(frame(300, 1, 2)))
        assertFalse(history.append(frame(400, 100, 1)))
        assertEquals(2, history.size)
        assertEquals(300L, history.centerAt(history.rowAtAge(0)))
    }
    @Test fun eachRowUsesItsOwnCapturedSpan() {
        val history = WaterfallHistory(16, 4)
        history.append(frame(10000, 1).copy(spanHz = 3200))
        history.append(frame(10000, 2).copy(spanHz = 1600))
        assertEquals(12.0, history.binAtFrequency(history.rowAtAge(1), 10800.0), .001)
        assertEquals(16.0, history.binAtFrequency(history.rowAtAge(0), 10800.0), .001)
    }
}
