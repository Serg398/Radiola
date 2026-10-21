package com.example.sdr

import java.util.ArrayDeque

/** Bounded by audio duration at any sample rate. Producers never wait for the consumer. */
internal class WebSdrPcmQueue(private val maximumMs: Int = 200) {
    data class Block(val rate: Int, val samples: ShortArray) {
        val durationUs = samples.size * 1_000_000L / rate
    }
    private val blocks = ArrayDeque<Block>()
    private var durationUs = 0L
    @Volatile var droppedUs = 0L
        private set
    @Synchronized fun offer(block: Block) {
        blocks.addLast(block); durationUs += block.durationUs
        while (blocks.size > 1 && (durationUs > maximumMs * 1000L || blocks.size > 128)) {
            val old = blocks.removeFirst(); durationUs -= old.durationUs; droppedUs += old.durationUs
        }
    }
    @Synchronized fun poll(): Block? = blocks.pollFirst()?.also { durationUs -= it.durationUs }
    @Synchronized fun milliseconds(): Int = (durationUs / 1000).toInt()
    @Synchronized fun clear() { blocks.clear(); durationUs = 0 }
}
