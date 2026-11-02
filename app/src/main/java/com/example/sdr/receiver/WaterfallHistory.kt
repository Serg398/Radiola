package com.example.sdr.receiver

import com.example.sdr.SpectrumFrame
import com.example.sdr.telemetry.RenderTimings

/** Main-thread ring of full-band rows, retained independently of Android Views. */
class WaterfallHistory(val binCount: Int = 8192, val capacity: Int = 160) {
    // 1/128 dB precision, before display clipping; allows AUTO to reveal weak signals.
    private val levels = ShortArray(binCount * capacity)
    private val centers = LongArray(capacity)
    private val spans = IntArray(capacity)
    private val timestamps = LongArray(capacity)
    var newestRow = 0
        private set
    var size = 0
        private set
    var revision = 0L
        private set
    private var lastSession = -1L
    private var lastGeneration = -1L
    val latestSpanHz get() = if (size == 0) 2_400_000 else spans[newestRow]

    fun append(frame: SpectrumFrame): Boolean {
        if (frame.bins.isEmpty() || frame.spanHz <= 0 || frame.generation <= 0) return false
        if (frame.sessionId < lastSession || (frame.sessionId == lastSession && frame.generation <= lastGeneration)) return false
        val started = System.nanoTime()
        lastSession = frame.sessionId; lastGeneration = frame.generation
        newestRow = (newestRow + capacity - 1) % capacity
        centers[newestRow] = frame.centerFrequencyHz
        spans[newestRow] = frame.spanHz
        timestamps[newestRow] = frame.timestampNanos
        val rowStart = newestRow * binCount
        if (frame.bins.size == binCount) {
            for (x in 0 until binCount)
                levels[rowStart + x] = encodeDb(frame.bins[x])
        } else {
            for (x in 0 until binCount) {
                val first = x * frame.bins.size / binCount
                val end = maxOf(first + 1, (x + 1) * frame.bins.size / binCount)
                var db = frame.bins[first]
                for (bin in first + 1 until end) db = maxOf(db, frame.bins[bin])
                levels[rowStart + x] = encodeDb(db)
            }
        }
        size = minOf(size + 1, capacity); revision++
        RenderTimings.history.record(System.nanoTime() - started)
        return true
    }
    fun rowAtAge(age: Int): Int { require(age in 0 until size); return (newestRow + age) % capacity }
    fun centerAt(row: Int) = centers[row]
    fun spanAt(row: Int) = spans[row]
    fun timestampAt(row: Int) = timestamps[row]
    private fun encodeDb(db: Float) = if (db.isFinite())
        (db * 128f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() else Short.MIN_VALUE
    fun dbAt(row: Int, bin: Int) = levels[row * binCount + bin] / 128f
    fun levelAt(row: Int, bin: Int) = (((dbAt(row, bin) + 94f) / 69f) * 255f).toInt().coerceIn(0, 255)
    fun binAtFrequency(row: Int, hz: Double) = (hz - centers[row]) * binCount / spans[row] + binCount * .5
    fun imageLeftFraction(row: Int, visibleCenter: Double, visibleSpan: Int) =
        (centers[row] - visibleCenter - spans[row] * .5 + visibleSpan * .5) / visibleSpan
}
