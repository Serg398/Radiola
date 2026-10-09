package com.example.sdr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumTraceSmootherTest {
    private fun frame(center: Long, generation: Long, bins: FloatArray) =
        SpectrumFrame(bins, center, generation, generation * 33_333_333, 1, 800)

    @Test fun retuningKeepsThePeakAtItsAbsoluteFrequency() {
        val smoother = SpectrumTraceSmoother()
        smoother.update(frame(10000, 1, FloatArray(8) { if (it == 4) -20f else -90f }))
        val result = smoother.update(frame(10200, 2, FloatArray(8) { if (it == 2) -20f else -90f }))
        assertEquals(-20f, result[2], .001f)
        assertEquals(-90f, result[4], .001f)
    }

    @Test fun newlyCapturedEdgeDoesNotInheritUnrelatedOldBins() {
        val smoother = SpectrumTraceSmoother()
        smoother.update(frame(10000, 1, FloatArray(8) { -20f }))
        val result = smoother.update(frame(10200, 2, FloatArray(8) { -90f }))
        assertEquals(-90f, result[6], .001f)
        assertEquals(-90f, result[7], .001f)
    }

    @Test fun fractionalRetuningInterpolatesTheMatchingFrequency() {
        val smoother = SpectrumTraceSmoother()
        smoother.update(frame(10000, 1, FloatArray(8) { -90f + it * 10f }))
        val result = smoother.update(frame(10050, 2, FloatArray(8) { -85f + it * 10f }))
        assertEquals(-55f, result[3], .001f)
    }
}
