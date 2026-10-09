package com.example.sdr

import kotlin.math.exp
import kotlin.math.floor

/** Display-only smoothing; never blend bins belonging to different RF frequencies. */
internal class SpectrumTraceSmoother {
    private var previous: SpectrumFrame? = null
    private var levels = FloatArray(0)
    private var scratch = FloatArray(0)

    fun update(frame: SpectrumFrame): FloatArray {
        val old = previous
        if (old === frame) return levels
        if (old == null || old.sessionId != frame.sessionId ||
            old.spanHz != frame.spanHz ||
            levels.size != frame.bins.size || frame.generation <= old.generation) {
            levels = frame.bins.copyOf()
        } else {
            if (old.centerFrequencyHz != frame.centerFrequencyHz) {
                if (scratch.size != levels.size) scratch = FloatArray(levels.size)
                val shift = (frame.centerFrequencyHz - old.centerFrequencyHz).toDouble() * levels.size / frame.spanHz
                for (i in levels.indices) {
                    // Each new bin inherits only the level at the same absolute RF.
                    val source = i + shift
                    if (source < 0.0 || source > levels.lastIndex.toDouble()) {
                        scratch[i] = frame.bins[i]
                    } else {
                        val first = floor(source).toInt()
                        val last = minOf(first + 1, levels.lastIndex)
                        val fraction = (source - first).toFloat()
                        scratch[i] = levels[first] + (levels[last] - levels[first]) * fraction
                    }
                }
                val reusable = levels
                levels = scratch
                scratch = reusable
            }
            val elapsed = if (frame.timestampNanos > old.timestampNanos)
                (frame.timestampNanos - old.timestampNanos) / 1_000_000_000.0 else 1.0 / 30.0
            val rise = (1.0 - exp(-elapsed / 0.060)).toFloat()
            val fall = (1.0 - exp(-elapsed / 0.120)).toFloat()
            for (i in levels.indices) {
                val target = frame.bins[i]
                levels[i] += (target - levels[i]) * if (target > levels[i]) rise else fall
            }
        }
        previous = frame
        return levels
    }
}
