package com.example.sdr.decoders.cw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ArrayBlockingQueue

/** Audio producers only copy into a bounded queue; all analysis runs on its own thread. */
internal class CwDecodeWorker {
    private data class Block(val generation: Long, val rate: Int, val channels: Int, val samples: ShortArray)
    private val queue = ArrayBlockingQueue<Block>(16)
    @Volatile private var generation = 0L
    @Volatile private var enabled = false
    var decodingEnabled by mutableStateOf(false)
        private set
    private var sourceActive = false
    @Volatile private var information = CwInformation()
    private var frequency = 0L
    private var thread: Thread? = null
    @Volatile private var closed = false

    @Synchronized fun requestDecoding(value: Boolean) {
        if (closed || decodingEnabled == value) return
        decodingEnabled = value
        configure(sourceActive, frequency)
    }

    @Synchronized fun configure(sourceAvailable: Boolean, hz: Long) {
        sourceActive = sourceAvailable
        val active = sourceAvailable && decodingEnabled
        if (closed || enabled == active && frequency == hz) return
        enabled = active; frequency = hz; clear()
        if (active && thread == null) {
            thread = Thread({
                var decoder = CwDecoder(); var epoch = -1L
                try {
                    while (!closed) {
                        val block = queue.take()
                        if (block.generation != generation) continue
                        if (epoch != block.generation) { decoder = CwDecoder(); epoch = block.generation }
                        decoder.process(block.samples, block.rate, block.channels)
                        synchronized(this) {
                            if (enabled && epoch == generation) information = decoder.information()
                        }
                    }
                } catch (_: InterruptedException) { /* close */ }
            }, "CwDecoder").apply { isDaemon = true; start() }
        }
    }

    @Synchronized fun clear() { generation++; queue.clear(); information = CwInformation() }
    fun snapshot(): CwInformation = information
    fun acceptsAudio(): Boolean = enabled && !closed
    fun offer(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        if (!enabled || closed || count <= 0) return
        val epoch = generation
        if (!queue.offer(Block(epoch, rate, channels, samples.copyOf(count)))) {
            // Missing PCM invalidates Morse timings. Reset instead of decoding across a gap.
            clear()
        }
    }
    @Synchronized fun close() { closed = true; enabled = false; clear(); thread?.interrupt() }
}
