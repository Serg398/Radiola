package com.example.sdr

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.ui.receiver.DisplayDbRange
import com.example.sdr.ui.receiver.WaterfallView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaterfallDisplayLevelsTest {
    @Test fun disablingAutoRestoresTheEntireHistoryWithoutWaitingForNewRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val history = WaterfallHistory(16, 4)
            repeat(4) { row -> history.append(SpectrumFrame(FloatArray(16) { -135f + it * 6 + row },
                1_000_000, row + 1L, sessionId = 1, spanHz = 100_000)) }
            val view = WaterfallView(instrumentation.targetContext, history, minimumRenderWidth = 16)
            view.layout(0, 0, 64, 32)
            view.setTuning(1_000_000, 100_000)
            fun pixels(): IntArray {
                view.refresh()
                val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                return IntArray(64 * 32).also { bitmap.getPixels(it, 0, 64, 0, 0, 64, 32) }
            }
            val defaults = pixels()
            view.setDisplayRange(DisplayDbRange(-138f, -78f))
            assertFalse(defaults.contentEquals(pixels()))
            view.setDisplayRange(null)
            assertArrayEquals(defaults, pixels())
        }
    }
}
