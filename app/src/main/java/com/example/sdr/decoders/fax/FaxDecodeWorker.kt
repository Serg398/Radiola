package com.example.sdr.decoders.fax

import android.graphics.Bitmap
import java.util.concurrent.ArrayBlockingQueue

internal data class FaxInformation(
    val pages: List<Bitmap> = emptyList(), val width: Int = 1809, val lines: Int = 0,
    val status: String = "Ready", val lpm: Int = 120, val ioc: Int = 576,
    val revision: Long = 0,
)

/** Bounded audio queue, independent DSP thread, immutable 64-row image tiles for Compose. */
internal class FaxDecodeWorker : AutoCloseable {
    private data class Message(val epoch: Long, val samples: ShortArray? = null, val rate: Int = 0,
        val channels: Int = 1, val action: (() -> Unit)? = null)
    private val queue = ArrayBlockingQueue<Message>(64)
    @Volatile private var available = false
    @Volatile var decoding = false; private set
    @Volatile private var closed = false
    @Volatile private var epoch = 0L
    private var workingEpoch = 0L
    @Volatile private var info = FaxInformation()
    private var frequency = 0L
    private var lpm = 120; private var ioc = 576
    private var revision = 0L
    private var lines = 0
    private val complete = ArrayList<Bitmap>()
    private var tile: Bitmap? = null
    private var decoder: FaxDecoder = createDecoder()
    private val thread = Thread({
        try {
            while (!closed) {
                val message = queue.take()
                if (message.epoch == epoch) {
                    workingEpoch = message.epoch
                    try {
                        if (message.action != null) message.action.invoke()
                        else if (acceptsAudio() && message.samples != null) {
                            decoder.process(message.samples, message.rate, message.channels)
                            if (info.status != decoder.status && lines < MAX_LINES) publish()
                        }
                    } catch (e: Exception) { decoding = false; publish(e.message ?: "FAX decoding failed") }
                }
            }
        } catch (_: InterruptedException) { /* closed */ }
    }, "FaxDecoder").apply { isDaemon = true; start() }

    private fun createDecoder(): FaxDecoder = FaxDecoder(lpm, ioc, onStart = { clearImage() }) { row ->
        if (workingEpoch == epoch && lines < MAX_LINES) {
            renderRow(row, lines); lines++
            if (lines == MAX_LINES) { decoder.stop(); decoding = false; publish("Image limit reached") }
            else publish()
        }
    }
    private fun clearImage() { lines = 0; complete.clear(); tile = null; publish() }
    private fun publish(status: String = decoder.status) {
        if (workingEpoch != epoch) return
        val pages = complete.toList() + listOfNotNull(tile?.let {
            val height = lines % TILE_ROWS
            if (height == 0) null else Bitmap.createBitmap(it, 0, 0, decoder.width, height)
                .copy(Bitmap.Config.ARGB_8888, false)
        })
        info = FaxInformation(pages, decoder.width, lines, status, lpm, ioc, ++revision)
    }
    private fun renderRow(row: ByteArray, index: Int) {
        if (index % TILE_ROWS == 0) tile = Bitmap.createBitmap(decoder.width, TILE_ROWS, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(decoder.width) { x ->
            val gray = row[x].toInt() and 255
            0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
        tile!!.setPixels(pixels, 0, decoder.width, 0, index % TILE_ROWS, decoder.width, 1)
        if ((index + 1) % TILE_ROWS == 0) { complete.add(tile!!.copy(Bitmap.Config.ARGB_8888, false)); tile = null }
    }
    private fun command(action: () -> Unit) {
        if (closed) return
        if (!queue.offer(Message(epoch, action = action))) {
            epoch++; queue.clear(); queue.offer(Message(epoch, action = action))
        }
    }
    @Synchronized fun configure(active: Boolean, hz: Long) {
        if (closed || available == active && frequency == hz) return
        val changed = frequency != hz
        available = active; frequency = hz; epoch++; queue.clear()
        command {
            decoder = createDecoder()
            if (changed) clearImage()
            if (active && decoding) decoder.manualStart()
            publish(if (!active) "Stopped" else if (decoding) decoder.status else "Ready")
        }
    }
    fun acceptsAudio() = available && decoding && !closed
    fun snapshot() = info
    @Synchronized fun offer(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        if (!acceptsAudio() || count == 0) return
        if (!queue.offer(Message(epoch, samples.copyOf(count), rate, channels))) {
            epoch++; queue.clear(); decoding = false
            command { decoder.stop(); publish("Audio gap — restart Decode") }
        }
    }
    @Synchronized fun enable(value: Boolean) {
        decoding = value; epoch++; queue.clear()
        command {
            decoder = createDecoder()
            if (value && available) decoder.manualStart()
            publish(if (value && available) decoder.status else "Paused")
        }
    }
    @Synchronized fun clear() {
        epoch++; queue.clear()
        command {
            decoder = createDecoder(); clearImage()
            if (decoding && available) decoder.manualStart()
            publish(if (decoding && available) decoder.status else "Ready")
        }
    }
    @Synchronized fun format(lpm: Int, ioc: Int) {
        require(lpm in listOf(60, 90, 120, 240) && ioc in listOf(288, 576))
        epoch++; queue.clear()
        command {
            this.lpm = lpm; this.ioc = ioc; decoder = createDecoder(); clearImage()
            if (decoding && available) decoder.manualStart()
            publish(if (decoding && available) decoder.status else "Ready")
        }
    }
    @Synchronized override fun close() { closed = true; available = false; queue.clear(); thread.interrupt() }

    companion object { const val MAX_LINES = 2500; private const val TILE_ROWS = 64 }
}
