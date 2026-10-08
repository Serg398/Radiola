package com.example.sdr

/** Bounds route recovery and prevents a permanently stalled output from spinning forever. */
class AudioRecovery {
    private var windowStart = 0L
    private var attempts = 0
    private var stalledSince: Long? = null
    fun recreate(nowMs: Long): Boolean {
        if (nowMs - windowStart >= 10_000) { windowStart = nowMs; attempts = 0 }
        stalledSince = null
        return ++attempts <= 3
    }
    fun wrote(count: Int, nowMs: Long): Boolean {
        if (count > 0) { stalledSince = null; return true }
        val start = stalledSince ?: nowMs.also { stalledSince = it }
        return nowMs - start < 1_000
    }
}

data class AudioMetrics(
    val queuedFrames: Long = 0,
    val bufferFrames: Int = 0,
    val underruns: Int = 0,
    val recoveries: Int = 0,
    val route: Int = 0
)
