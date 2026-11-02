package com.example.sdr.ui.receiver

import com.example.sdr.SpectrumFrame
import kotlin.math.abs
import kotlin.math.exp

internal data class DisplayDbRange(val low: Float, val high: Float)
internal data class DisplayLevels(val spectrum: DisplayDbRange, val waterfall: DisplayDbRange) {
    companion object {
        fun defaults(relative: Boolean) = DisplayLevels(
            if (relative) DisplayDbRange(-94f, -25f) else DisplayDbRange(-120f, 0f), DisplayDbRange(-94f, -25f))
    }
}

/** Bounded percentile sampling: no FFT mutation, sorting of full frames, or influence from one peak. */
internal class AutomaticDisplayLevels {
    private val samples = FloatArray(1024)
    private var levels: DisplayLevels? = null
    private var lastAt = 0L
    private var lastCenter = Double.NaN
    private var fastUntil = 0L
    private var session = Long.MIN_VALUE

    fun update(frame: SpectrumFrame, center: Double, span: Int, nowMs: Long): DisplayLevels? {
        if (frame.bins.isEmpty() || frame.spanHz <= 0 || span <= 0) return levels
        if (session != frame.sessionId) { levels = null; session = frame.sessionId; lastAt = 0; lastCenter = Double.NaN }
        if (levels != null && nowMs - lastAt < 33) return levels
        if (center + span * .5 < frame.centerFrequencyHz - frame.spanHz * .5 ||
            center - span * .5 > frame.centerFrequencyHz + frame.spanHz * .5) return levels
        var first = ((.5 + (center - span * .5 - frame.centerFrequencyHz) / frame.spanHz) * frame.bins.size)
            .toInt().coerceIn(0, frame.bins.size)
        var end = ((.5 + (center + span * .5 - frame.centerFrequencyHz) / frame.spanHz) * frame.bins.size)
            .toInt().coerceIn(first, frame.bins.size)
        if (end - first < 8) {
            val middle = (first + end) / 2
            first = (middle - 8).coerceIn(0, maxOf(0, frame.bins.size - 16))
            end = minOf(frame.bins.size, first + 16)
        }
        val count = minOf(1024, end - first)
        var valid = 0
        for (i in 0 until count) {
            val value = frame.bins[first + i * (end - first) / count]
            if (value.isFinite()) samples[valid++] = value.coerceIn(-240f, 80f)
        }
        if (valid < 8) return levels
        java.util.Arrays.sort(samples, 0, valid)
        val floor = samples[((valid - 1) * .2f).toInt()]
        val upper = samples[((valid - 1) * .98f).toInt()]
        val target = DisplayLevels(DisplayDbRange(floor - 12f, maxOf(floor + 48f, upper + 12f)),
            DisplayDbRange(floor - 3f, maxOf(floor + 32f, upper + 8f)))
        if (lastCenter.isFinite() && abs(center - lastCenter) > 1) fastUntil = nowMs + 600
        lastCenter = center
        val previous = levels
        val dt = (nowMs - lastAt).coerceIn(0, 500).toDouble()
        val alpha = (1 - exp(-dt / if (nowMs < fastUntil) 450.0 else 2000.0)).toFloat()
        fun smooth(old: DisplayDbRange, next: DisplayDbRange) = DisplayDbRange(
            old.low + (next.low - old.low) * alpha, old.high + (next.high - old.high) * alpha)
        levels = if (previous == null) target else DisplayLevels(smooth(previous.spectrum, target.spectrum),
            smooth(previous.waterfall, target.waterfall))
        lastAt = nowMs
        return levels
    }
}
