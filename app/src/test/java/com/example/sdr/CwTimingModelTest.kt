package com.example.sdr

import com.example.sdr.decoders.cw.CwTimingModel
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class CwTimingModelTest {
    private fun trained() = CwTimingModel().apply {
        repeat(12) {
            observe(60.0, true); observe(60.0, false)
            observe(180.0, true); observe(180.0, false)
        }
    }

    @Test fun ambiguousMarkRetainsAnAlternativeWithLowerConfidence() {
        val model = trained()
        val alphabet = mapOf("." to 'E', "-" to 'T')
        val clear = model.recognize(listOf(model.dotMs), alphabet)!!
        val ambiguous = model.recognize(listOf(sqrt(model.dotMs * model.dashMs)), alphabet)!!
        assertEquals('E', clear.value)
        assertTrue(clear.confidence >= 90)
        assertNull(clear.alternative)
        assertTrue(ambiguous.confidence < 20)
        assertNotNull(ambiguous.alternative)
        assertNotEquals(ambiguous.value, ambiguous.alternative)
    }

    @Test fun spacingDoesNotChangeTheLearnedSendingSpeed() {
        val model = trained()
        repeat(8) { model.observe(420.0, false); model.observe(960.0, false) }
        assertEquals(60.0, model.dotMs, 2.0)
        assertTrue(model.wordGapMs > 420.0)
        assertTrue(model.wordGapMs < 960.0)
    }
}
