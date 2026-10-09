package com.example.sdr

/** Main-thread counters; diagnostic logging runs on the same thread. */
object RenderTimings {
    class Stage {
        var totalNs = 0L
        var count = 0L
        var maximumNs = 0L
        fun record(ns: Long) { totalNs += ns; count++; maximumNs = maxOf(maximumNs, ns) }
        fun meanMs() = if (count == 0L) 0.0 else totalNs / count / 1_000_000.0
    }
    val spectrum = Stage()
    val waterfall = Stage()
    val history = Stage()
    val cache = Stage()
    val frameAge = Stage()
}
