package com.example.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays 48 kHz mono PCM produced by the selected native demodulator. */
class AudioOutput {
    @Volatile private var running = false
    @Volatile private var receiving = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var flushRequest: CountDownLatch? = null

    fun start(): Boolean {
        if (running && worker?.isAlive == true) return true
        val startedAt = SystemClock.elapsedRealtime()
        val minimum = AudioTrack.getMinBufferSize(
            48_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) return false
        val output = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(48_000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minimum, 1_920))
                .build()
        } catch (_: Exception) {
            return false
        }
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            return false
        }
        track = output
        running = true
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(960)
            var writtenSamples = 0L
            var flushes = 0
            var firstPcm = true
            var firstPlayback = true
            try {
                while (running) {
                    if (firstPlayback && output.playbackHeadPosition > 0) {
                        Log.d("SdrAudio", "playback_started_ms=${SystemClock.elapsedRealtime() - startedAt} route=${output.routedDevice?.type}")
                        firstPlayback = false
                    }
                    val request = flushRequest
                    if (request != null) {
                        output.pause()
                        output.flush()
                        NativeSdr.discardAudio()
                        output.play()
                        ++flushes
                        flushRequest = null
                        request.countDown()
                    }
                    val count = if (receiving) NativeSdr.readAudio(samples) else {
                        samples.fill(0)
                        samples.size
                    }
                    if (count == 0) {
                        Thread.sleep(4)
                        continue
                    }
                    var offset = 0
                    if (firstPcm) {
                        // Prime the track before playback so the route starts with audio ready.
                        val primed = output.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                        output.play()
                        Log.d("SdrAudio", "first_pcm_ms=${SystemClock.elapsedRealtime() - startedAt} buffer_frames=${output.bufferSizeInFrames} route=${output.routedDevice?.type}")
                        firstPcm = false
                        if (primed < 0) break
                        offset = primed
                        writtenSamples += primed
                    }
                    while (running && flushRequest == null && offset < count) {
                        val written = output.write(
                            samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING
                        )
                        if (written < 0) break
                        if (written == 0) {
                            Thread.sleep(2)
                            continue
                        }
                        offset += written
                        writtenSamples += written
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: IllegalStateException) {
                // The audio route may disappear while USB reception is still active.
            } finally {
                Log.d("SdrAudio", "stopped: samples=$writtenSamples underruns=${output.underrunCount} flushes=$flushes")
                flushRequest?.countDown()
                flushRequest = null
                runCatching { output.pause() }
                runCatching { output.flush() }
                output.release()
            }
        }, "SdrAudio").also { it.start() }
        return true
    }

    /** Used for changing demodulation mode; frequency tuning keeps playback continuous. */
    fun flushForModeChange(): Boolean {
        if (!running || worker?.isAlive != true) return false
        val request = CountDownLatch(1)
        flushRequest = request
        return request.await(300, TimeUnit.MILLISECONDS)
    }

    fun setReceiving(enabled: Boolean) {
        receiving = enabled
        if (!enabled) flushForModeChange()
        Log.d("SdrAudio", "receiving=$enabled; track_kept_open=$running")
    }

    fun stop() {
        receiving = false
        running = false
        worker?.join(1_000)
        if (worker?.isAlive == true) {
            worker?.interrupt()
            worker?.join(1_000)
        }
        worker = null
        track = null
    }
}
