package com.example.sdr

import com.example.sdr.ui.receiver.AutomaticDisplayLevels
import com.example.sdr.ui.receiver.DisplayLevels
import com.example.sdr.receiver.WaterfallHistory
import org.junit.Assert.*
import org.junit.Test

class AutomaticDisplayLevelsTest {
    private fun frame(bins: FloatArray, session: Long = 1) = SpectrumFrame(bins, 1_000_000, 1, sessionId = session, spanHz = 100_000)

    @Test fun weakFloorIsVisibleAndOneStrongPeakDoesNotChangeTheRange() {
        val bins = FloatArray(1024) { -130f }
        val normal = AutomaticDisplayLevels().update(frame(bins), 1_000_000.0, 100_000, 1000)!!
        assertTrue(normal.waterfall.low < -130 && normal.waterfall.high > -130)
        assertTrue(normal.spectrum.low < -130 && normal.spectrum.high > -130)
        bins[512] = 0f
        assertEquals(normal, AutomaticDisplayLevels().update(frame(bins), 1_000_000.0, 100_000, 1000))
        assertEquals(0f, bins[512], 0f)
        assertNotNull(AutomaticDisplayLevels().update(frame(bins), 975_000.0, 100, 1000))
    }

    @Test fun smoothsChangesAndUsesTheVisiblePartOfTheBand() {
        val bins = FloatArray(1024) { if (it < 512) -120f else -70f }
        val controller = AutomaticDisplayLevels()
        val left = controller.update(frame(bins), 975_000.0, 50_000, 1000)!!
        val right = controller.update(frame(bins), 1_025_000.0, 50_000, 1100)!!
        assertTrue(right.waterfall.low > left.waterfall.low)
        assertTrue(right.waterfall.low < -73f)
        val reset = controller.update(frame(bins, 2), 1_025_000.0, 50_000, 1200)!!
        assertEquals(-73f, reset.waterfall.low, .01f)
        assertNull(AutomaticDisplayLevels().update(frame(FloatArray(64) { Float.NaN }), 1_000_000.0, 100_000, 1000))
    }

    @Test fun historyRetainsWeakAndStrongLevelsForAutoWhileDefaultsStayFixed() {
        val bins = FloatArray(16) { -130f }.apply { this[8] = -10f }
        val history = WaterfallHistory(16, 2)
        history.append(frame(bins))
        val row = history.rowAtAge(0)
        assertEquals(-130f, history.dbAt(row, 0), .01f)
        assertEquals(0, history.levelAt(row, 0))
        assertEquals(-10f, history.dbAt(row, 8), .01f)
        assertEquals(255, history.levelAt(row, 8))
        assertEquals(-120f, DisplayLevels.defaults(false).spectrum.low, 0f)
        assertEquals(-94f, DisplayLevels.defaults(true).spectrum.low, 0f)
        assertEquals(-25f, DisplayLevels.defaults(false).waterfall.high, 0f)
    }
}
