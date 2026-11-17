package com.example.sdr.decoders.cw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ArrayBlockingQueue

/** Audio producers only copy into a bounded queue; all analysis runs on its own thread. */
internal class CwDecodeWorker(context: android.content.Context? = null) {
    private val appContext = context?.applicationContext
    private val preferences = appContext?.getSharedPreferences("cw_decoder", 0)
    var backend by mutableStateOf(runCatching { CwBackend.valueOf(preferences?.getString("backend", "CLASSIC") ?: "CLASSIC") }.getOrDefault(CwBackend.CLASSIC)); private set
    @Synchronized fun selectBackend(value: CwBackend) {
        if (closed || backend == value) return
        backend = value; preferences?.edit()?.putString("backend", value.name)?.apply(); clear()
    }
    private data class Block(val generation: Long, val rate: Int, val channels: Int, val samples: ShortArray)
    private val queue = ArrayBlockingQueue<Block>(32)
    private var pendingPcm = ShortArray(0)
    private var pendingCount = 0
    private var pendingRate = 0
    private var pendingChannels = 0
    @Volatile private var generation = 0L
    @Volatile private var enabled = false
    var decodingEnabled by mutableStateOf(false)
        private set
    private var sourceActive = false
    @Volatile private var information = CwInformation()
    private var frequency = 0L
    private var thread: Thread? = null
    @Volatile private var lastProcessMs = 0L
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
                var decoder = CwDecoder(); var deep: DeepCwDecoder? = null; var epoch = -1L
                var former: CwFormerDecoder? = null
                var failed = false
                try {
                    while (!closed) {
                        val block = queue.take()
                        if (block.generation != generation) continue
                        if (epoch != block.generation) {
                            if (enabled && backend == CwBackend.DEEPCW) deep?.reset()
                            else { deep?.close(); deep = null }
                            if (enabled && backend == CwBackend.CWFORMER) former?.reset()
                            else { former?.close(); former = null }
                            decoder = CwDecoder(); epoch = block.generation; failed = false
                        }
                        if (!enabled || block.samples.isEmpty()) continue
                        try {
                            val started = System.nanoTime()
                            val result = if (backend == CwBackend.CWFORMER) {
                                if (failed) continue
                                if (former == null) former = CwFormerDecoder(checkNotNull(appContext) { "CWformer requires Android context" })
                                former!!.process(block.samples, block.rate, block.channels)
                                former!!.information()
                            } else if (backend == CwBackend.DEEPCW) {
                                if (failed) continue
                                if (deep == null) deep = DeepCwDecoder(checkNotNull(appContext) { "DeepCW requires Android context" })
                                deep!!.process(block.samples, block.rate, block.channels)
                                deep!!.information()
                            } else {
                                decoder.process(block.samples, block.rate, block.channels); decoder.information()
                            }
                            synchronized(this) { if (enabled && epoch == generation) information = result }
                            lastProcessMs = (System.nanoTime() - started) / 1_000_000
                        } catch (error: Exception) {
                            deep?.close(); deep = null; failed = true
                            former?.close(); former = null
                            synchronized(this) {
                                if (epoch == generation) information = CwInformation(status = "${backend.label} unavailable: ${error.message}")
                            }
                        }
                    }
                } catch (_: InterruptedException) { /* close */ }
                finally { deep?.close(); former?.close() }
            }, "CwDecoder").apply { isDaemon = true; start() }
        }
    }

    @Synchronized fun clear() {
        generation++; queue.clear(); pendingCount = 0; pendingRate = 0
        information = CwInformation(); queue.offer(Block(generation, 0, 1, ShortArray(0)))
    }
    fun snapshot(): CwInformation = information
    fun acceptsAudio(): Boolean = enabled && !closed
    @Synchronized fun offer(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        if (!enabled || closed || count <= 0) return
        require(count <= samples.size && rate in 4000..48000 && channels in 1..2 && count % channels == 0)
        if (pendingRate != 0 && (pendingRate != rate || pendingChannels != channels)) clear()
        pendingRate = rate; pendingChannels = channels
        // Playback heads can advance by just one sample. Queue roughly 50 ms of
        // PCM per entry rather than overflowing while inference blocks briefly.
        val target = rate / 20 * channels
        val pcm = if (pendingCount == 0 && count >= target) samples.copyOf(count) else {
            if (pendingPcm.size < pendingCount + count) pendingPcm = pendingPcm.copyOf(maxOf(target, pendingCount + count))
            samples.copyInto(pendingPcm, pendingCount, 0, count); pendingCount += count
            if (pendingCount < target) return
            pendingPcm.copyOf(pendingCount).also { pendingCount = 0 }
        }
        val epoch = generation
        if (!queue.offer(Block(epoch, rate, channels, pcm))) {
            android.util.Log.w("CwWorker", "overflow blockSamples=${pcm.size} rate=$rate lastProcessMs=$lastProcessMs")
            // Missing PCM invalidates Morse timings. Reset instead of decoding across a gap.
            clear()
        }
    }
    @Synchronized fun close() { closed = true; enabled = false; clear(); thread?.interrupt() }
}
