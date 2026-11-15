package com.example.sdr.receiver

import java.util.ArrayDeque

/** Absorbs short network bursts without allowing old frames to build up latency. */
internal class SpectrumFrameQueue<T>(private val capacity: Int = 3, private val maximumAgeMs: Long = 250) {
    private data class Entry<T>(val value: T, val at: Long)
    private val entries = ArrayDeque<Entry<T>>()
    private var dropped = 0

    @Synchronized fun offer(value: T, now: Long) {
        discardExpired(now)
        while (entries.size >= capacity) { entries.removeFirst(); dropped++ }
        entries.addLast(Entry(value, now))
    }
    @Synchronized fun poll(now: Long): T? {
        discardExpired(now)
        return entries.pollFirst()?.value
    }
    @Synchronized fun isNotEmpty() = entries.isNotEmpty()
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun takeDropped(): Int = dropped.also { dropped = 0 }
    private fun discardExpired(now: Long) {
        while (entries.isNotEmpty() && now - entries.first.at > maximumAgeMs) {
            entries.removeFirst(); dropped++
        }
    }
}
