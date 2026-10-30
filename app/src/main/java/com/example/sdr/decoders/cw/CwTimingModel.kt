package com.example.sdr.decoders.cw

import kotlin.math.*

/** Learns the sender's mark lengths separately from character/word spacing. */
internal class CwTimingModel {
    private data class Event(val duration: Double, val mark: Boolean)
    private val events = ArrayDeque<Event>()
    var dotMs = 60.0; private set
    private var dashRatio = 3.0
    private var clustered = false
    val dashMs get() = dotMs * dashRatio
    val markBoundaryMs get() = sqrt(dotMs * dashMs)
    var hasSpacing = false; private set
    var characterGapMs = 120.0; private set
    var wordGapMs = 330.0; private set

    fun reset() {
        events.clear(); dotMs = 60.0; dashRatio = 3.0; clustered = false
        hasSpacing = false; characterGapMs = 120.0; wordGapMs = 330.0
    }

    fun observe(duration: Double, mark: Boolean) {
        events.addLast(Event(duration, mark))
        if (events.size > 40) events.removeFirst()
        val marks = events.filter { it.mark }.map { it.duration }.sorted()
        var bestCost = Double.POSITIVE_INFINITY
        var bestDot = 0.0
        var bestDash = 0.0
        // Require evidence for both classes; do not split jitter in a run of dots.
        for (split in 2..marks.size - 2) {
            val low = marks.subList(0, split)
            val high = marks.subList(split, marks.size)
            // A median snaps short marks to the 10 ms detector grid (e.g. 35 -> 40 WPM).
            // A trimmed mean keeps sub-window timing while rejecting isolated outliers.
            fun center(values: List<Double>): Double {
                val trim = values.size / 8
                return values.subList(trim, values.size - trim).average()
            }
            val dot = center(low)
            val dash = center(high)
            if (dot !in 25.0..180.0 || dash / dot !in 1.8..5.5) continue
            val cost = (low.sumOf { ln(it / dot).pow(2) } + high.sumOf { ln(it / dash).pow(2) }) / marks.size +
                .005 * abs(ln(dot / dotMs))
            if (cost < bestCost) { bestCost = cost; bestDot = dot; bestDash = dash }
        }
        if (bestCost < .12) {
            dotMs = dotMs * .65 + bestDot * .35
            dashRatio = dashRatio * .65 + (bestDash / bestDot) * .35
            clustered = true
        } else if (!clustered) {
            // Bootstrap before enough dots and dashes exist, using the original timing fit.
            val candidates = events.flatMap { event ->
                (if (event.mark) listOf(1, 3) else listOf(1, 3, 7)).map { event.duration / it }
            }.filter { it in 25.0..180.0 }
            val best = candidates.minByOrNull { candidate ->
                events.sumOf { event ->
                    val ratio = event.duration / candidate
                    val error = (if (event.mark) listOf(1, 3) else listOf(1, 3, 7))
                        .minOf { abs(ratio - it) / it }
                    min(error * error, 1.0)
                } / events.size + .005 * abs(ln(candidate / dotMs))
            }
            if (best != null) dotMs = if (events.size < 6) best else dotMs * .65 + best * .35
        }
        // Only recompute on edges, never allocate/sort on every 10 ms detector tick.
        val gaps = events.filter { !it.mark }.takeLast(12).map { it.duration }.sorted()
        val long = gaps.filter { it >= dotMs * 2 }
        val short = gaps.filter { it < dotMs * 2 }
        val letter = long.getOrNull((long.size * .2).toInt()) ?: dotMs * 3
        val intra = short.getOrNull(short.size / 2) ?: dotMs
        hasSpacing = long.isNotEmpty()
        characterGapMs = max(dotMs * 2, sqrt(intra * letter))
        wordGapMs = max(dotMs * 5.5, letter * 1.65)
    }

    fun score(durations: List<Double>, pattern: String): Double = durations.indices.sumOf { index ->
        val expected = if (pattern[index] == '-') dashMs else dotMs
        (ln(durations[index] / expected) / .3).pow(2)
    }

    /** Timing-only ranking, with a heuristic separation score, not a calibrated probability. */
    fun recognize(durations: List<Double>, alphabet: Map<String, Char>): CwCharacter? {
        val choices = alphabet.entries.filter { it.key.length == durations.size }
            .map { it.value to score(durations, it.key) }.sortedBy { it.second }
        val best = choices.firstOrNull() ?: return null
        val margin = (choices.getOrNull(1)?.second ?: best.second + 20) - best.second
        val confidence = (100 * (1 - exp(-margin.coerceAtLeast(0.0) / 2))).roundToInt().coerceIn(0, 100)
        return CwCharacter(best.first, confidence, choices.getOrNull(1)?.first.takeIf { confidence < 80 })
    }
}
