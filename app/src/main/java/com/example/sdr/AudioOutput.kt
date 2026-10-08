package com.example.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The worker alone owns AudioTrack, including recreation and release. */
class AudioOutput(private val onFatal: (String) -> Unit = {}) {
    private class Run {
        @Volatile var running = true
        @Volatile var receiving = false
        @Volatile var flush: CountDownLatch? = null
        val ready = CountDownLatch(1)
        @Volatile var initialized = false
        @Volatile var receivingSince = 0L
        lateinit var thread: Thread
    }
    private var active: Run? = null
    @Volatile var metrics = AudioMetrics()
        private set

    @Synchronized fun start(): Boolean {
        active?.takeIf { it.running && it.thread.isAlive }?.let { return true }
        val run = Run()
        active = run
        run.thread = Thread({ playback(run) }, "SdrAudio").also { it.start() }
        if (!run.ready.await(1_000, TimeUnit.MILLISECONDS) || !run.initialized) {
            run.running = false
            return false
        }
        return true
    }
    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported audio format: $minimum" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minimum, 3840)).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); error("AudioTrack initialization failed") }
        return track
    }
    private fun playback(run: Run) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var output: AudioTrack? = null
        val recovery = AudioRecovery()
        val samples = ShortArray(1920)
        var writtenFrames = 0L
        var recovered = 0
        var lastReport = 0L
        var playing = false
        var headWrap = 0L
        var previousHead = 0L
        var lastReceivingSince = 0L
        val started = SystemClock.elapsedRealtime()
        fun release() {
            output?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }
            output = null
        }
        fun reopen(reason: String) {
            while (run.running) {
                check(recovery.recreate(SystemClock.elapsedRealtime())) { "Audio recovery exhausted: $reason" }
                release()
                try { output = createTrack() }
                catch (error: Exception) {
                    Log.w("SdrAudio", "route not ready: ${error.message}")
                    Thread.sleep(100)
                    continue
                }
                NativeSdr.discardAudio()
                playing = false; writtenFrames = 0; previousHead = 0; headWrap = 0
                recovered++
                Log.w("SdrAudio", "recreated: $reason recoveries=$recovered")
                return
            }
        }
        try {
            output = createTrack()
            run.initialized = true; run.ready.countDown()
            while (run.running) {
                var track = output ?: break
                val request = run.flush
                if (request != null) {
                    try {
                        track.pause(); track.flush(); NativeSdr.discardAudio()
                        playing = false; writtenFrames = 0; previousHead = 0; headWrap = 0
                    } catch (_: IllegalStateException) { reopen("flush on dead output") }
                    finally { run.flush = null; request.countDown() }
                    if (!run.running) break
                    track = output ?: break
                }
                val count = if (run.receiving) NativeSdr.readAudio(samples) else {
                    samples.fill(0); samples.size
                }
                if (count == 0) { Thread.sleep(4); continue }
                if (run.receiving && run.receivingSince != lastReceivingSince) {
                    lastReceivingSince = run.receivingSince
                    Log.d("SdrAudio", "first_received_pcm_ms=${SystemClock.elapsedRealtime()-lastReceivingSince} route=${track.routedDevice?.type}")
                }
                var offset = 0
                while (run.running && run.flush == null && offset < count) {
                    val written = try {
                        output!!.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                    } catch (_: IllegalStateException) { AudioTrack.ERROR_DEAD_OBJECT }
                    if (written < 0) {
                        if (written == AudioTrack.ERROR_BAD_VALUE) error("Invalid audio write: $written")
                        reopen("write=$written")
                        // Resume with fresh PCM after a route reset, never replay the old queue.
                        break
                    }
                    if (!recovery.wrote(written, SystemClock.elapsedRealtime())) {
                        reopen("write stalled"); break
                    }
                    if (written == 0) { Thread.sleep(2); continue }
                    offset += written; writtenFrames += written / 2
                    if (!playing && writtenFrames >= 960) {
                        try { output!!.play(); playing = true }
                        catch (_: IllegalStateException) { reopen("play on dead output"); break }
                        Log.d("SdrAudio", "play_ms=${SystemClock.elapsedRealtime()-started} buffer_frames=${output!!.bufferSizeInFrames} route=${output!!.routedDevice?.type}")
                    }
                }
                val current = output ?: continue
                val head = current.playbackHeadPosition.toLong() and 0xffffffffL
                if (head < previousHead) headWrap += 1L shl 32
                previousHead = head
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= 1000) {
                    metrics = AudioMetrics((writtenFrames - headWrap - head).coerceAtLeast(0),
                        current.bufferSizeInFrames, current.underrunCount, recovered, current.routedDevice?.type ?: 0)
                    Log.d("SdrAudio", "queued_frames=${metrics.queuedFrames} buffer_frames=${metrics.bufferFrames} underruns=${metrics.underruns} recoveries=$recovered route=${metrics.route}")
                    lastReport = now
                }
            }
        } catch (_: InterruptedException) {
            if (run.running) onFatal("Audio worker interrupted")
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (run.running) onFatal(error.message ?: "Audio output failed")
        } finally {
            run.running = false
            run.ready.countDown(); run.flush?.countDown(); run.flush = null
            release()
        }
    }
    @Synchronized fun flushForModeChange(): Boolean {
        val run = active?.takeIf { it.running } ?: return false
        val request = CountDownLatch(1)
        run.flush = request
        return request.await(300, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun setReceiving(enabled: Boolean) {
        if (enabled) active?.receivingSince = SystemClock.elapsedRealtime()
        active?.receiving = enabled
        flushForModeChange()
    }
    @Synchronized fun stop() {
        val run = active ?: return
        run.receiving = false; run.running = false
        run.thread.interrupt(); run.thread.join(1000)
        check(!run.thread.isAlive) { "Audio worker did not stop" }
        active = null
    }
}
