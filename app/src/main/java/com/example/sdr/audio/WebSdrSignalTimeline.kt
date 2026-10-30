package com.example.sdr.audio

import java.util.ArrayDeque

/** Audio-worker-only meter markers in the same frame coordinates as AudioTrack. */
internal class WebSdrSignalTimeline {
    private data class Marker(val frame: Long, val dbm: Float?)
    private val markers = ArrayDeque<Marker>()
    private var current: Float? = null

    fun record(firstFrame: Long, dbm: Float?) { markers.addLast(Marker(firstFrame, dbm)) }
    fun advance(playedFrames: Long): Float? {
        while (markers.isNotEmpty() && markers.first.frame < playedFrames) current = markers.removeFirst().dbm
        return current
    }
    fun clear() { markers.clear(); current = null }
}
