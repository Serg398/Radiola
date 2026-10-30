package com.example.sdr

import com.example.sdr.decoders.cw.DeepCwStitcher
import org.junit.Assert.*
import org.junit.Test

class DeepCwStitcherTest {
    @Test fun matchesUpstreamAcrossOverlapWordGapsAndMisleadingWindowEdges() {
        val stitcher = DeepCwStitcher(listOf("A", "B", "C", " "), 4, 5, 3200.0 / 96, 67)
        // Golden output from the pinned upstream Python PosteriorStitcher.
        val fixture = javaClass.getResourceAsStream("/deepcw-stitch.tsv")!!.bufferedReader().use { it.readLines() }
        val events = listOf(Triple(20,25,0), Triple(75,80,1), Triple(150,155,0), Triple(220,225,0),
            Triple(240,245,1), Triple(330,335,2), Triple(420,425,1), Triple(500,505,2), Triple(600,605,0))
        repeat(2) {
            for (line in fixture) {
                val columns = line.split('\t')
                val start = columns[0].toLong(); val end = columns[1].toInt()
                val size = end - start.toInt()
                val logits = Array(size) { i ->
                    var symbol = events.firstOrNull { start + i >= it.first && start + i < it.second }?.third ?: 4
                    if (start > 0 && i < 5 || i >= size - 4) symbol = 2
                    FloatArray(5) { if (it == symbol) 5f else -5f }
                }
                stitcher.add(start, logits)
                val (committed, pending) = stitcher.take()
                assertEquals("committed end=$end", columns[2], committed.text)
                assertEquals("leading end=$end", columns[3].toBoolean(), committed.prependSpace)
                assertEquals("pending end=$end", columns[4], pending.text)
                assertEquals(columns[5].toBoolean(), pending.prependSpace)
                assertEquals(columns[6].toLong(), stitcher.contextStartFrame)
            }
            stitcher.reset()
        }
    }
}
