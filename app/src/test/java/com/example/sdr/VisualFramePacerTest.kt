package com.example.sdr

import com.example.sdr.receiver.VisualFramePacer
import org.junit.Assert.*
import org.junit.Test

class VisualFramePacerTest {
    private fun frame(generation: Long, session: Long = 1) =
        SpectrumFrame(floatArrayOf(-60f), 100_000_000L, generation, sessionId = session)

    @Test fun waitsForHeadroomAndPublishesOnlyAtDeadline() {
        val pacer = VisualFramePacer()
        pacer.offer(frame(1))
        assertNull(pacer.poll(1_000_000_000L))
        pacer.offer(frame(2))
        assertEquals(1L, pacer.poll(1_016_666_667L)!!.generation)
        assertNull(pacer.poll(1_033_333_334L))
        assertEquals(2L, pacer.poll(1_050_000_001L)!!.generation)
    }

    @Test fun steadySourceStaysAtThirtyAcrossDisplayRates() {
        for (hz in listOf(59.94, 60.0, 90.0, 120.0)) {
            val pacer = VisualFramePacer()
            val publications = mutableListOf<Long>()
            var generation = 0L
            repeat((hz * 6).toInt()) { tick ->
                val time = 1_000_000_000L + (tick * 1_000_000_000.0 / hz).toLong()
                val available = ((time - 1_000_000_000L) * 30 / 1_000_000_000L) + 1
                while (generation < available) pacer.offer(frame(++generation))
                if (pacer.poll(time) != null) publications.add(time)
            }
            assertTrue("$hz Hz count=${publications.size}", publications.size in 177..180)
            for ((first, second) in publications.zipWithNext())
                assertTrue("$hz Hz interval=${second - first}", second - first in 25_000_000L..42_000_000L)
        }
    }

    @Test fun overloadedUiDropsBacklogWithoutCatchUpBurst() {
        val pacer = VisualFramePacer()
        repeat(100) { pacer.offer(frame(it + 1L)) }
        assertEquals(98L, pacer.poll(1_000_000_000L)!!.generation)
        assertNull(pacer.poll(1_000_000_000L))
        assertEquals(99L, pacer.poll(2_000_000_000L)!!.generation)
        assertNull(pacer.poll(2_008_333_333L))
    }

    @Test fun newSessionDiscardsPreviousFramesAndRejectsLateOldSession() {
        val pacer = VisualFramePacer()
        pacer.offer(frame(100))
        pacer.offer(frame(101))
        pacer.offer(frame(1, 2))
        pacer.offer(frame(102, 1))
        assertNull(pacer.poll(1_000_000_000L))
        pacer.offer(frame(2, 2))
        assertEquals(2L, pacer.poll(1_016_666_667L)!!.sessionId)
        assertEquals(2L, pacer.lastGeneration)
    }

    @Test fun resetDropsBufferedFramesAndAllowsGenerationRestart() {
        val pacer = VisualFramePacer()
        pacer.offer(frame(1)); pacer.offer(frame(2))
        pacer.clear()
        assertNull(pacer.poll(1_000_000_000L))
        pacer.offer(frame(1)); pacer.offer(frame(1))
        assertNull(pacer.poll(1_016_666_667L))
        pacer.offer(frame(2))
        assertEquals(1L, pacer.poll(1_033_333_334L)!!.generation)
    }
}
