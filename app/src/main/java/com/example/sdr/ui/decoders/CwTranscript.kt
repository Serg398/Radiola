package com.example.sdr.ui.decoders

/** Keeps completed rows stable when the decoder trims its rolling character history. */
internal class CwTranscript {
    data class Line(val id: Long, val text: String)
    private var previous = ""
    private var width = 0
    private var nextId = 0L
    private val rows = ArrayList<Line>()

    fun update(snapshot: String, columns: Int): List<Line> {
        val newWidth = columns.coerceAtLeast(1)
        if (newWidth != width) { rows.clear(); previous = ""; width = newWidth }
        if (snapshot == previous) return rows.toList()
        if (snapshot.isEmpty()) { rows.clear(); previous = ""; return emptyList() }
        val overlap = (minOf(previous.length, snapshot.length) downTo 1)
            .firstOrNull { previous.regionMatches(previous.length - it, snapshot, 0, it) } ?: 0
        if (overlap == 0) rows.clear()
        for (char in snapshot.drop(overlap)) append(char)
        previous = snapshot
        if (rows.size > 200) rows.subList(0, rows.size - 200).clear()
        return rows.toList()
    }

    private fun append(char: Char) {
        if (rows.isEmpty()) rows.add(Line(nextId++, ""))
        if (char == '\n') { rows.add(Line(nextId++, "")); return }
        if (char == '\r') return
        val last = rows.last()
        val value = last.text + char
        if (value.length <= width) { rows[rows.lastIndex] = last.copy(text = value); return }
        val split = value.lastIndexOf(' ', width)
        if (split > 0) {
            rows[rows.lastIndex] = last.copy(text = value.substring(0, split).trimEnd())
            rows.add(Line(nextId++, value.substring(split + 1)))
        } else {
            rows[rows.lastIndex] = last.copy(text = value.substring(0, width))
            rows.add(Line(nextId++, value.substring(width).trimStart()))
        }
    }
}
