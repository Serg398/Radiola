package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

class WebSdrSquelchTest {
    @Test fun holdsWeakSignalsThenClosesAndRampsWithoutClicks() {
        val gate = WebSdrSquelch()
        val setting = ReceiverSquelch(true, -65f)
        fun block(dbm: Float?) = ShortArray(480) { 10000 }.also {
            gate.process(it, it.size, 48000, setting, dbm)
        }
        assertTrue(block(-100f).last() > 9900) // Relative -63 dB opens.
        repeat(20) { assertTrue(block(-104f).last() > 9900) } // -67 dB is inside hysteresis.
        repeat(5) { assertTrue(block(-110f).last() > 9900) } // Short gaps stay audible.
        var muted = block(-110f)
        repeat(15) { muted = block(-110f) }
        assertEquals(0, muted.last().toInt())
        val opening = block(-100f)
        assertTrue(opening.first() in 1..100)
        assertEquals(10000, opening.last().toInt())
    }

    @Test fun missingSignalClosesEnabledGateButDisabledGatePassesAudio() {
        val gate = WebSdrSquelch()
        val pcm = ShortArray(480) { 10000 }
        assertFalse(gate.process(pcm, pcm.size, 48000, ReceiverSquelch(true), null))
        assertTrue(pcm.all { it == 0.toShort() })
        pcm.fill(10000)
        assertTrue(gate.process(pcm, pcm.size, 48000, ReceiverSquelch(false), null))
        assertEquals(10000, pcm.last().toInt())
    }
}
