package com.example.sdr

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class FtxMode(val windowSamples: Int, val strideSamples: Int) {
    FT8(180000, 24000), FT4(90000, 12000)
}
internal data class FtxInformation(val lines: List<String> = emptyList(), val collectedSeconds: Int = 0,
    val decoding: Boolean = false, val error: String? = null)

/** PCM collection and native decoding never block playback or the UI. */
internal class FtxDecodeWorker {
    private data class Block(val epoch: Long, val samples: ShortArray, val rate: Int, val channels: Int)
    private val queue = ArrayBlockingQueue<Block>(128)
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "FtxDecode").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    @Volatile private var epoch = 0L
    @Volatile private var mode: FtxMode? = null
    @Volatile private var hz = 0L
    @Volatile private var closed = false
    @Volatile private var information = FtxInformation()
    private var collector: Thread? = null

    @Synchronized fun configure(value: FtxMode?, frequency: Long) {
        if (closed || (mode == value && hz == frequency)) return
        mode = value; hz = frequency; clear()
        if (value != null && collector == null) collector = Thread({ collect() }, "FtxCollect").apply { isDaemon = true; start() }
    }
    @Synchronized fun clear() { epoch++; queue.clear(); information = FtxInformation() }
    fun snapshot() = information
    fun offer(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        if (mode == null || closed || count <= 0) return
        if (!queue.offer(Block(epoch, samples.copyOf(count), rate, channels))) clear()
    }
    private fun collect() {
        var generation = -1L; var sampleRate = 0
        var ring = ShortArray(0); var head = 0; var size = 0; var sinceDecode = 0
        var inputPosition = 0L; var nextOutput = 0.0; var previous = 0f
        val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val seen = mutableMapOf<String, Long>()
        var seenEpoch = -1L
        try {
            while (!closed) {
                val block = queue.take()
                val protocol = mode ?: continue
                if (block.epoch != epoch) continue
                if (generation != block.epoch || sampleRate != block.rate) {
                    generation = block.epoch; sampleRate = block.rate
                    ring = ShortArray(protocol.windowSamples); head = 0; size = 0; sinceDecode = 0
                    inputPosition = 0; nextOutput = 0.0; previous = 0f
                }
                for (index in 0 until block.samples.size / block.channels) {
                    var sum = 0f
                    for (channel in 0 until block.channels) sum += block.samples[index * block.channels + channel]
                    val sample = sum / block.channels
                    while (nextOutput <= inputPosition) {
                        val fraction = (nextOutput - inputPosition + 1).coerceIn(0.0, 1.0)
                        ring[head] = (previous + (sample - previous) * fraction).toInt().toShort()
                        head = (head + 1) % ring.size; size = minOf(size + 1, ring.size); sinceDecode++
                        nextOutput += sampleRate / 12000.0
                    }
                    previous = sample; inputPosition++
                }
                synchronized(this) {
                    if (generation == epoch) information = information.copy(collectedSeconds = size / 12000)
                }
                if (size < ring.size || sinceDecode < protocol.strideSamples || !busy.compareAndSet(false, true)) continue
                sinceDecode = 0
                val pcm = ShortArray(ring.size) { ring[(head + it) % ring.size] }
                val ownedEpoch = generation
                synchronized(this) { if (ownedEpoch == epoch) information = information.copy(decoding = true) }
                decoder.execute {
                    try {
                        val rows = NativeFtx.decode(pcm, protocol == FtxMode.FT4)
                        synchronized(this) {
                            if (ownedEpoch == epoch && !closed) {
                                if (seenEpoch != ownedEpoch) { seen.clear(); seenEpoch = ownedEpoch }
                                val now = System.currentTimeMillis()
                                val additions = rows.filter { row ->
                                    val last = seen[row] ?: 0
                                    if (now - last < if (protocol == FtxMode.FT8) 7000 else 3000) false
                                    else { seen[row] = now; true }
                                }.map { "${utc.format(Date(now))}  ${it.substringBefore('\t')} Hz  ${it.substringAfter('\t')}" }
                                seen.entries.removeAll { now - it.value > 60000 }
                                information = information.copy(lines = (information.lines + additions).takeLast(100), decoding = false, error = null)
                            }
                        }
                    } catch (failure: Exception) {
                        synchronized(this) { if (ownedEpoch == epoch) information = information.copy(decoding = false, error = failure.message ?: "Decode failed") }
                    } finally { busy.set(false) }
                }
            }
        } catch (_: InterruptedException) { /* close */ }
    }
    @Synchronized fun close() { closed = true; mode = null; clear(); collector?.interrupt(); decoder.shutdownNow() }
}
