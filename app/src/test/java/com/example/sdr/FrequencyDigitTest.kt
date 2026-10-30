package com.example.sdr

import com.example.sdr.ui.receiver.frequencyDigitStep
import org.junit.Assert.*
import org.junit.Test

class FrequencyDigitTest {
    @Test fun eachDigitUsesItsOwnFrequencyPlace() {
        val text = "0.007.435.000"
        val expected = listOf(1_000_000_000L, null, 100_000_000L, 10_000_000L, 1_000_000L,
            null, 100_000L, 10_000L, 1_000L, null, 100L, 10L, 1L)
        assertEquals(expected, text.indices.map { frequencyDigitStep(text, it) })
        assertNull(frequencyDigitStep(text, -1))
        assertNull(frequencyDigitStep(text, text.length))
    }
}
