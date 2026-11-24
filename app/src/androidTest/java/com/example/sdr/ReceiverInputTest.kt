package com.example.sdr

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.example.sdr.ui.receiver.ReceiverViewModel
import org.junit.Assert.*
import org.junit.Test

class ReceiverInputTest {
    @Test fun invalidInputKeepsFrequencyAndCorrectionClearsError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReceiverViewModel::class.java]
                val original = model.frequencyHz
                try {
                    model.editFrequency("999999")
                    assertFalse(model.applyFrequency())
                    assertEquals(original, model.frequencyHz)
                    assertNotNull(model.frequencyInputError)
                    model.editFrequency("7.074125")
                    assertNull(model.frequencyInputError)
                    assertTrue(model.applyFrequency())
                    assertEquals(7_074_125L, model.frequencyHz)
                } finally { model.requestFrequency(original) }
            }
        }
    }
}
