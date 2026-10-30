package com.example.sdr.decoders.cw

import kotlin.math.*

/** Adapted from e04/deepcw-engine's MIT-licensed posterior stitcher.
 * Keeps absolute frame positions and retains only uncommitted rows. */
internal class DeepCwStitcher(
    private val chars: List<String>, private val blank: Int,
    private val classes: Int, private val framesPerSecond: Double,
    private val wordBoundaryFrames: Int,
) {
    data class Lane(val text: String = "", val prependSpace: Boolean = false)
    private data class Span(val symbol: Int, val start: Long, var end: Long)
    private data class Row(val sum: FloatArray, var weight: Float = 0f)
    private val space = chars.indexOf(" ")
    private val rows = ArrayList<Row>()
    private var rowBase = 0L
    private var coveredEnd = 0L
    private var frozenEnd = 0L
    private var committedEnd = 0L
    private var previousSymbol = 0 // 0 none, 1 character, 2 space
    private var blankFrames = 0
    private var hasText = false
    val contextStartFrame get() = max(0L, frozenEnd - (4 * framesPerSecond).roundToLong())

    fun add(start: Long, logits: Array<FloatArray>) {
        if (logits.isEmpty()) return
        val end = start + logits.size
        while (rowBase + rows.size < end) rows.add(Row(FloatArray(classes)))
        val probabilities = Array(logits.size) { normalize(logits[it]) }
        for (i in maxOf(0L, frozenEnd - start, rowBase - start).toInt() until logits.size) {
            val dilated = FloatArray(classes) { c ->
                if (c == blank) probabilities[i][c]
                else (max(0, i - 1)..min(logits.lastIndex, i + 1)).maxOf { probabilities[it][c] }
            }
            val values = normalize(dilated)
            val left = if (start == 0L) 1.0 else smooth(i / framesPerSecond / 2)
            val right = smooth((logits.lastIndex - i) / framesPerSecond)
            val weight = max(.001, left * right).toFloat()
            val row = rows[(start + i - rowBase).toInt()]
            for (c in 0 until classes) row.sum[c] += weight * values[c]
            row.weight += weight
        }
        coveredEnd = max(coveredEnd, end)
        frozenEnd = max(frozenEnd, end - (2 * framesPerSecond).roundToLong())
    }

    fun take(): Pair<Lane, Lane> {
        val start = committedEnd
        if (coveredEnd <= start) return Lane() to Lane()
        val argmax = IntArray((coveredEnd - start).toInt()) { i ->
            val row = rows[(start + i - rowBase).toInt()]
            if (row.weight <= 0) blank else row.sum.indices.maxBy { row.sum[it] / row.weight }
        }
        val indices = argmax.copyOf()
        var previous = when (previousSymbol) { 1 -> -2; 2 -> space; else -> -1 }
        var i = 0
        while (space >= 0 && i < indices.size) {
            if (indices[i] != blank) { previous = indices[i++]; continue }
            val run = i
            while (i < indices.size && indices[i] == blank) i++
            val following = if (i < indices.size) indices[i] else -1
            if (i - run + (if (run == 0) blankFrames else 0) >= wordBoundaryFrames &&
                previous != -1 && previous != space && following >= 0 && following != space)
                for (j in run until i) indices[j] = space
        }
        val spans = ArrayList<Span>()
        previous = -1
        for (j in indices.indices) {
            val symbol = indices[j]
            if (symbol == blank) { previous = -1; continue }
            if (symbol == previous) { if (spans.isNotEmpty()) spans.last().end = start + j; continue }
            previous = symbol
            if (symbol in chars.indices) spans.add(Span(symbol, start + j, start + j))
        }
        var commitCount = 0
        for (j in spans.indices) {
            if (spans[j].end + 1 >= frozenEnd) break
            if (spans[j].symbol != space) commitCount = j + 1
        }
        val committed = lane(spans.take(commitCount), !hasText || previousSymbol == 2)
        if (commitCount > 0) {
            hasText = true; committedEnd = spans[commitCount - 1].end + 1
            previousSymbol = 1; blankFrames = 0
        }
        val remaining = spans.drop(commitCount)
        val next = remaining.firstOrNull { it.symbol != space }?.start ?: Long.MAX_VALUE
        val releaseEnd = min(frozenEnd, next) - 1
        var frame = committedEnd
        while (frame < releaseEnd) {
            if (argmax[(frame - start).toInt()] == space) { previousSymbol = 2; blankFrames = 0 }
            else blankFrames++
            frame++
        }
        if (releaseEnd > committedEnd) committedEnd = releaseEnd
        val drop = min((committedEnd - rowBase).toInt(), rows.size)
        if (drop > 0) { rows.subList(0, drop).clear(); rowBase += drop }
        return committed to lane(remaining.filter { it.end >= committedEnd }, !hasText || previousSymbol == 2)
    }

    private fun lane(spans: List<Span>, prepend: Boolean): Lane {
        val out = StringBuilder()
        var leading = prepend; var pendingSpace = false
        for (span in spans) {
            if (span.symbol == space) { if (out.isEmpty()) leading = true else pendingSpace = true }
            else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false; out.append(chars[span.symbol])
            }
        }
        return Lane(out.toString(), leading)
    }
    private fun normalize(values: FloatArray): FloatArray {
        val peak = values.max()
        val scale = peak + ln(values.sumOf { exp((it - peak).toDouble()) })
        return FloatArray(values.size) { (values[it] - scale).toFloat() }
    }
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    fun reset() {
        rows.clear(); rowBase = 0; coveredEnd = 0; frozenEnd = 0; committedEnd = 0
        previousSymbol = 0; blankFrames = 0; hasText = false
    }
}
