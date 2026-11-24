package com.example.sdr.receiver

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/** Network producers enqueue; only the main thread publishes, on display VSync. */
internal class VsyncFramePublisher<T>(private val publish: (T) -> Unit) {
    private val pending = SpectrumFrameQueue<T>()
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer by lazy { Choreographer.getInstance() }
    private var wakePosted = false
    private var framePosted = false
    private var startupDeadlineNanos = 0L
    private var nextFrameNanos = 0L
    private var previousVsyncNanos = 0L
    private val wake = Runnable {
        synchronized(this) {
            wakePosted = false
            if (!framePosted && pending.isNotEmpty()) {
                startupDeadlineNanos = System.nanoTime() + INTERVAL_NANOS
                postFrame()
            }
        }
    }
    private val callback = Choreographer.FrameCallback { time -> onFrame(time) }

    @Synchronized fun offer(value: T) {
        pending.offer(value, SystemClock.elapsedRealtime())
        if (!wakePosted && !framePosted) {
            wakePosted = true
            handler.post(wake)
        }
    }

    private fun postFrame() {
        framePosted = true
        choreographer.postFrameCallback(callback)
    }

    private fun onFrame(time: Long) {
        val value = synchronized(this) {
            framePosted = false
            val vsyncInterval = if (previousVsyncNanos == 0L) 0L else
                (time - previousVsyncNanos).coerceIn(0L, INTERVAL_NANOS)
            previousVsyncNanos = time
            // Wait at most one 30 Hz interval for headroom, never a second slow server frame.
            if (nextFrameNanos == 0L && (pending.size() >= 2 || time >= startupDeadlineNanos))
                nextFrameNanos = time
            var row: T? = null
            if (nextFrameNanos != 0L && time + vsyncInterval / 2 >= nextFrameNanos) {
                row = pending.poll(SystemClock.elapsedRealtime())
                nextFrameNanos += INTERVAL_NANOS
                if (nextFrameNanos <= time - vsyncInterval / 2) nextFrameNanos = time + INTERVAL_NANOS
            }
            if (pending.isNotEmpty()) postFrame()
            else {
                nextFrameNanos = 0L
                previousVsyncNanos = 0L
            }
            row
        }
        value?.let(publish)
    }

    /** Called by the main-thread session owner on disconnect/profile changes. */
    @Synchronized fun clear() {
        check(Looper.myLooper() == Looper.getMainLooper())
        pending.clear()
        handler.removeCallbacks(wake)
        if (framePosted) choreographer.removeFrameCallback(callback)
        wakePosted = false
        framePosted = false
        startupDeadlineNanos = 0L
        nextFrameNanos = 0L
        previousVsyncNanos = 0L
    }

    fun takeDropped(): Int = pending.takeDropped()

    private companion object {
        const val INTERVAL_NANOS = 1_000_000_000L / 30
    }
}
