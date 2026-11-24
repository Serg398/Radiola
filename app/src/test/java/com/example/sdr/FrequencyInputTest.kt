package com.example.sdr

import com.example.sdr.receiver.validateFrequencyInput
import org.junit.Assert.*
import org.junit.Test

class FrequencyInputTest {
    private val usb = listOf(500_000L..1_766_000_000L)

    @Test fun decimalInputPreservesHertzAndAcceptsComma() {
        assertEquals(7_074_125L, validateFrequencyInput("7.074125", usb).hz)
        assertEquals(7_074_125L, validateFrequencyInput(" 7,074125 ", usb).hz)
        assertEquals(500_000L, validateFrequencyInput("0.5", usb).hz)
        assertEquals(1_766_000_000L, validateFrequencyInput("1766", usb).hz)
    }

    @Test fun malformedOrOutOfRangeInputNeverReturnsAReceiverFrequency() {
        for (text in listOf("", "abc", "NaN", "Infinity", "1e999", "-7", "0", "0.4", "1766.1")) {
            val result = validateFrequencyInput(text, usb)
            assertNull(text, result.hz)
            assertNotNull(text, result.error)
        }
    }

    @Test fun gapsBetweenServerBandsAreNotTunable() {
        val bands = listOf(3_500_000L..3_800_000L, 7_000_000L..7_200_000L)
        assertEquals(7_100_000L, validateFrequencyInput("7.1", bands).hz)
        assertNull(validateFrequencyInput("5", bands).hz)
        assertNull(validateFrequencyInput("7.1", emptyList()).hz)
    }
}
