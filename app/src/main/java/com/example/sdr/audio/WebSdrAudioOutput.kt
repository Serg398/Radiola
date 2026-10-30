package com.example.sdr.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.example.sdr.receiver.ReceiverAudioMetrics
import com.example.sdr.receiver.ReceiverSquelch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One worker owns the track. Network jitter buffering is measured in milliseconds, not blocks. */
internal class WebSdrAudioOutput(private val onPlaying: () -> Unit, private val onError: (String) -> Unit,
    private val onSignal: (Float?) -> Unit = {},
    private val squelchSetting: () -> ReceiverSquelch = { ReceiverSquelch() },
    private val onSquelchOpen: (Boolean) -> Unit = {},
    private val noiseModel: () -> Int = { 0 },
    private val needsPlayedPcm: () -> Boolean = { false },
    private val onPlayedPcm: (Int, ShortArray) -> Unit = { _, _ -> }) {
    private val queue = WebSdrPcmQueue()
    private val signal = Semaphore(0)
    @Volatile private var running = true
    @Volatile var metrics = ReceiverAudioMetrics()
        private set
    private val worker = Thread({ playback() }, "WebSdrAudio").apply { start() }

    fun offer(rate: Int, samples: ShortArray, signalDbm: Float? = null) {
        if (!running) return
        queue.offer(WebSdrPcmQueue.Block(rate, samples, signalDbm))
        if (signal.availablePermits() == 0) signal.release()
    }
    fun close() { running = false; queue.clear(); worker.interrupt() }

    private fun playback() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var rate = 0
        var announced = false
        var playing = false
        var buffering = true
        var scratch = ShortArray(128)
        var written = 0L
        val targetMs = 40
        var prebufferAt = SystemClock.elapsedRealtime()
        var lastUnderruns = 0
        var underruns = 0
        var recoveries = 0
        var previousHead = 0L
        var headWrap = 0L
        val recovery = AudioRecovery()
        val meter = WebSdrSignalTimeline()
        val gateTimeline = WebSdrSignalTimeline()
        var gate = WebSdrSquelch()
        val playedPcm = PlaybackPcmTimeline(1)
        val noiseReduction = HamNoiseProcessor()
        var reportedSignal: Float? = null
        fun playbackHead(): Long {
            val head = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: return 0L
            if (head < previousHead) headWrap += 1L shl 32
            previousHead = head
            val played = headWrap + head
            if (needsPlayedPcm()) playedPcm.advance(played) { onPlayedPcm(rate, it) } else playedPcm.clear()
            val value = meter.advance(played)
            onSquelchOpen(gateTimeline.advance(played) == 1f)
            if (value != reportedSignal) { reportedSignal = value; onSignal(value) }
            return played
        }
        fun release() {
            runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
            noiseReduction.close()
            track = null; playing = false; written = 0; previousHead = 0; headWrap = 0
            meter.clear(); gateTimeline.clear(); gate = WebSdrSquelch(); playedPcm.clear()
            if (reportedSignal != null) { reportedSignal = null; onSignal(null) }
        }
        fun create(sampleRate: Int) {
            release(); rate = sampleRate
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Unsupported server audio rate: $rate" }
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, rate / 10 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            check(track!!.state == AudioTrack.STATE_INITIALIZED)
            lastUnderruns = 0
        }
        fun reopen(reason: String) {
            while (running) {
                check(recovery.recreate(SystemClock.elapsedRealtime())) { "Audio recovery exhausted: $reason" }
                release(); queue.clear(); recoveries++
                try { create(rate); buffering = true; prebufferAt = SystemClock.elapsedRealtime(); return }
                catch (_: IllegalStateException) { Thread.sleep(100) }
            }
        }
        try {
            while (running) {
                if (playing) playbackHead()
                if (buffering && queue.milliseconds() < targetMs && SystemClock.elapsedRealtime() - prebufferAt < 250) {
                    signal.tryAcquire(20, TimeUnit.MILLISECONDS); continue
                }
                buffering = false
                val block = queue.poll()
                if (block == null) { signal.tryAcquire(10, TimeUnit.MILLISECONDS); continue }
                if (block.rate != rate || track == null) create(block.rate)
                if (scratch.size < block.samples.size) scratch = ShortArray(block.samples.size)
                for (i in block.samples.indices) scratch[i] = (block.samples[i] * .65536f).toInt().toShort()
                val gateOpen = gate.process(scratch, block.samples.size, rate, squelchSetting(), block.signalDbm)
                val audible = noiseReduction.process(scratch, block.samples.size, rate, 1, noiseModel())
                var offset = 0
                while (running && offset < block.samples.size) {
                    val count = try { track!!.write(audible, offset, block.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING) }
                        catch (_: IllegalStateException) { AudioTrack.ERROR_DEAD_OBJECT }
                    if (count < 0) {
                        check(count != AudioTrack.ERROR_BAD_VALUE) { "Invalid audio write: $count" }
                        reopen("write=$count"); break
                    }
                    if (!recovery.wrote(count, SystemClock.elapsedRealtime())) { reopen("write stalled"); break }
                    if (playing) playbackHead()
                    if (count == 0) {
                        // Some routes need play before their complete requested prefill fits.
                        if (!playing && written > 0) { track!!.play(); playing = true }
                        Thread.sleep(2); continue
                    }
                    if (offset == 0) {
                        meter.record(written, block.signalDbm)
                        gateTimeline.record(written, if (gateOpen) 1f else 0f)
                    }
                    if (needsPlayedPcm()) playedPcm.record(written, block.samples, offset, count)
                    offset += count; written += count
                    if (!playing && written >= rate * targetMs / 1000) { track!!.play(); playing = true }
                }
                if (!announced && playing && running) { announced = true; onPlaying() }
                val current = track ?: continue
                val count = current.underrunCount
                if (count > lastUnderruns) {
                    // Do not withhold the next available PCM to refill a jitter
                    // target while the track is already playing: that prolongs a gap.
                    underruns += count - lastUnderruns
                }
                lastUnderruns = count
                val played = playbackHead()
                val queued = ((written - played).coerceAtLeast(0) * 1000 / rate).toInt() + queue.milliseconds()
                metrics = ReceiverAudioMetrics(queued, queue.droppedUs / 1000, underruns, recoveries, targetMs, current.routedDevice?.type ?: 0)
            }
        } catch (_: InterruptedException) {
            // close() interrupts waits and write recovery.
        } catch (error: Exception) {
            if (running) onError(error.message ?: "Audio output failed")
        } finally { release() }
    }
}
