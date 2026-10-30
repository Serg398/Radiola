package com.example.sdr

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.ui.receiver.ReceiverViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverPresentationTest {
    @Test fun tuningAndDecoderSelectionSurviveActivityRecreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var first: ReceiverViewModel? = null
        var oldSource = ReceiverSource.USB
        var oldFrequency = 0L
        var oldMode = DemodulationMode.AM
        var oldPage = "Radio"
        var oldUsbBandwidth: Int? = null
        try {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReceiverViewModel::class.java]
                first = model; oldSource = model.online.source
                oldPage = model.online.page
                oldUsbBandwidth = (activity.application as RadiolaApplication).receiver.bandwidths[DemodulationMode.USB]
                oldFrequency = model.frequencyHz; oldMode = model.demodulationMode
                model.online.selectSource(ReceiverSource.USB)
                model.presentation.selectFtx(FtxMode.FT8)
                model.requestFrequency(7_074_000)
                model.presentation.step(1_000)
                assertEquals(7_075_000L, model.presentation.state.frequencyHz)
                assertEquals(3000, model.presentation.state.bandwidthHz)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReceiverViewModel::class.java]
                assertSame(first, model)
                assertEquals(FtxMode.FT8, model.presentation.ftxMode)
                assertEquals(7_075_000L, model.frequencyHz)
                model.presentation.changeMode(DemodulationMode.CW)
                assertNull(model.presentation.ftxMode)
                assertEquals(DemodulationMode.CW, model.presentation.state.mode)
            }
        } finally {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[ReceiverViewModel::class.java]
                model.presentation.changeMode(oldMode)
                model.requestFrequency(oldFrequency)
                (activity.application as RadiolaApplication).receiver.setBandwidth(DemodulationMode.USB, oldUsbBandwidth)
                model.online.selectSource(oldSource)
                model.online.selectPage(oldPage)
            }
            scenario.close()
        }
    }
}
