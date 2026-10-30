package com.example.sdr.receiver

/** Main-thread throttle with a trailing latest value: continuous input never starves delivery. */
internal class LatestCommand<T : Any>(
    private val intervalMs: Long,
    private val now: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (T) -> Unit,
) {
    private var pending: T? = null
    private var scheduled = false
    private var nextAt = 0L
    private val drain = Runnable {
        scheduled = false
        val value = pending
        pending = null
        if (value != null) { nextAt = now() + intervalMs; send(value) }
    }
    fun offer(value: T) {
        pending = value
        if (!scheduled) { scheduled = true; post(drain, (nextAt - now()).coerceAtLeast(0)) }
    }
    fun flush() { cancel(drain); scheduled = false; drain.run() }
    fun clear() { cancel(drain); pending = null; scheduled = false; nextAt = 0 }
}
