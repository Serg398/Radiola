package com.example.sdr

import java.util.ArrayDeque

/** Audio-worker-only PCM retained until the track reports those frames played. */
internal class PlaybackPcmTimeline(private val channels: Int) {
    private data class Entry(val frame: Long, val samples: ShortArray, var consumed: Int = 0)
    private val entries = ArrayDeque<Entry>()
    fun record(frame: Long, samples: ShortArray, offset: Int, count: Int) {
        if (count > 0) entries.addLast(Entry(frame, samples.copyOfRange(offset, offset + count)))
    }
    fun advance(played: Long, consume: (ShortArray) -> Unit) {
        while (entries.isNotEmpty()) {
            val entry = entries.first
            val end = ((played - entry.frame).coerceAtLeast(0) * channels).coerceAtMost(entry.samples.size.toLong()).toInt()
            if (end <= entry.consumed) break
            consume(entry.samples.copyOfRange(entry.consumed, end))
            entry.consumed = end
            if (end < entry.samples.size) break
            entries.removeFirst()
        }
    }
    fun clear() { entries.clear() }
}
