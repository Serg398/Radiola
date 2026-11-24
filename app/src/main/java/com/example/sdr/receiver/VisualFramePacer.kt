package com.example.sdr.receiver

import com.example.sdr.SpectrumFrame

/** Main-thread VSync publication with one frame of headroom and bounded latency. */
internal class VisualFramePacer {
    private val pending = ArrayDeque<SpectrumFrame>()
    var lastGeneration = 0L
        private set
    private var sessionId = 0L
    private var nextFrameNanos = 0L
    private var previousVsyncNanos = 0L

    fun offer(frame: SpectrumFrame) {
        if (frame.generation <= 0 || frame.bins.isEmpty() || frame.spanHz <= 0 || frame.sessionId < sessionId) return
        if (frame.sessionId != sessionId) {
            clear()
            sessionId = frame.sessionId
        }
        if (frame.generation <= lastGeneration) return
        lastGeneration = frame.generation
        pending.addLast(frame)
        while (pending.size > 3) pending.removeFirst()
    }

    fun poll(vsyncNanos: Long): SpectrumFrame? {
        val vsyncInterval = if (previousVsyncNanos == 0L) 0L else
            (vsyncNanos - previousVsyncNanos).coerceIn(0L, INTERVAL_NANOS)
        previousVsyncNanos = vsyncNanos
        if (nextFrameNanos == 0L && pending.size >= 2) nextFrameNanos = vsyncNanos
        if (nextFrameNanos == 0L || vsyncNanos + vsyncInterval / 2 < nextFrameNanos) return null
        if (pending.isEmpty()) {
            nextFrameNanos = 0L
            return null
        }
        val frame = pending.removeFirst()
        nextFrameNanos += INTERVAL_NANOS
        // Never catch up with a burst after missing a display deadline.
        if (nextFrameNanos <= vsyncNanos - vsyncInterval / 2)
            nextFrameNanos = vsyncNanos + INTERVAL_NANOS
        return frame
    }

    fun clear() {
        pending.clear()
        lastGeneration = 0L
        sessionId = 0L
        nextFrameNanos = 0L
        previousVsyncNanos = 0L
    }

    private companion object {
        const val INTERVAL_NANOS = 1_000_000_000L / 30
    }
}
