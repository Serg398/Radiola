package com.example.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** One audio worker owns AudioTrack. Queue is bounded to about 200 ms of 128-sample blocks. */
internal class WebSdrAudioOutput(private val onPlaying: () -> Unit, private val onError: (String) -> Unit) {
    private data class Block(val rate: Int, val samples: ShortArray)
    private val queue = LinkedBlockingDeque<Block>(12)
    @Volatile private var running = true
    private val worker = Thread({ playback() }, "WebSdrAudio").apply { start() }

    fun offer(rate: Int, samples: ShortArray) {
        if (!running) return
        val block = Block(rate, samples)
        while (!queue.offerLast(block)) queue.pollFirst()
    }
    fun close() { running = false; queue.clear(); worker.interrupt() }

    private fun playback() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var rate = 0
        var announced = false
        try {
            while (running) {
                val block = queue.pollFirst(250, TimeUnit.MILLISECONDS) ?: continue
                if (block.rate != rate) {
                    track?.release()
                    rate = block.rate
                    val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "Unsupported WebSDR audio rate: $rate" }
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, rate / 10 * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    check(track.state == AudioTrack.STATE_INITIALIZED)
                    track.play()
                }
                val samples = ShortArray(block.samples.size) { (block.samples[it] * 0.65536f).toInt().toShort() }
                var offset = 0
                while (running && offset < samples.size) {
                    val written = track!!.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0) { "WebSDR audio write failed: $written" }
                    if (written == 0) Thread.sleep(2) else offset += written
                }
                if (!announced && offset > 0 && running) { announced = true; onPlaying() }
            }
        } catch (_: InterruptedException) {
            // close() wakes both queue waits and a nonblocking write retry.
        } catch (error: Exception) {
            if (running) onError(error.message ?: "WebSDR audio output failed")
        } finally {
            runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
        }
    }
}
