package com.example.sdr.ui.receiver

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.sdr.DemodulationMode
import com.example.sdr.RadiolaApplication
import com.example.sdr.SpectrumFrame
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.platform.DisplaySettings
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.ui.receiver.LocalReceiverState
import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.ui.receiver.ReceiverPresentation
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.TuningStep
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.sources.usb.ReceiverState
import kotlin.math.roundToLong

class ReceiverViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val runtime = (application as RadiolaApplication).receiver
    internal val online = (application as RadiolaApplication).webSdr
    private val settings = DisplaySettings(application)
    internal val presentation by lazy {
        ReceiverPresentation(online, runtime, viewModelScope,
            favorites = com.example.sdr.favorites.FavoritesRepository(application),
            local = { LocalReceiverState(frequencyHz, demodulationMode,
                receiverSnapshot.state == ReceiverState.Running, runtime.bandwidth(demodulationMode)) },
            usbStep = ::stepFrequency, usbMode = ::changeDemodulationMode,
            usbBandwidth = { runtime.setBandwidth(demodulationMode, it) },
            initialFtx = savedState.get<String>("ftxMode")?.let { name -> FtxMode.entries.firstOrNull { it.name == name } },
            onFtxChange = { savedState["ftxMode"] = it?.name })
    }
    var device by mutableStateOf<UsbDevice?>(null)
    var hasUsbPermission by mutableStateOf(false)
    var usbCheckResult by mutableStateOf<String?>(null)
    var receiverSnapshot by mutableStateOf(runtime.snapshot)
    var receiverMessage by mutableStateOf<String?>(null)
    var inputError by mutableStateOf<String?>(null)
    var frequencyInput by mutableStateOf("%.3f".format(java.util.Locale.US, runtime.session.snapshot().requestedHz / 1_000_000.0))
    var frequencyHz by mutableStateOf(runtime.session.snapshot().requestedHz)
    val waterfallHistory = WaterfallHistory()
    val spectrumState = mutableStateOf<SpectrumFrame?>(null)
    val waterfallState = mutableStateOf<SpectrumFrame?>(null)
    var waterfallFrameRate by mutableStateOf(WaterfallFrameRate.FPS_30)
    var encoderFeedback by mutableStateOf(EncoderFeedback.LIGHT)
    var tuningStep by mutableStateOf(TuningStep.fromStored(null))
    var demodulationMode by mutableStateOf(runtime.mode)
    var receiverGain by mutableStateOf(runtime.gain)
    var gainSteps by mutableStateOf(ReceiverGain.defaultSteps)
    var actualSpectrumFps by mutableStateOf(0)
    var actualWaterfallFps by mutableStateOf(0)
    var wfmStereoEnabled by mutableStateOf(false)
    var wfmStereoLocked by mutableStateOf(false)
    var receiverSquelch by mutableStateOf(ReceiverSquelch())
    var squelchOpen by mutableStateOf(true)
    var signalDbfs by mutableStateOf(-90f)
    var batteryPercent by mutableStateOf(-1)
    var batteryCharging by mutableStateOf(false)
    fun parseFrequency(): Long? {
        val mhz = frequencyInput.replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 0.5..1766.0) {
            usbCheckResult = "Введите частоту от 0,5 до 1766 МГц"
            return null
        }
        return (mhz * 1_000_000.0).roundToLong()
    }

    fun applyFrequency() {
        parseFrequency()?.let(::requestFrequency)
    }

    fun requestFrequency(hz: Long) {
        inputError = null
        usbCheckResult = null
        // Display the request immediately; only the controller tracks hardware acknowledgements.
        frequencyHz = hz
        frequencyInput = "%.3f".format(java.util.Locale.US, hz / 1_000_000.0)
        runtime.session.tune(hz)
    }

    fun changeDemodulationMode(mode: DemodulationMode) {
        if (mode == demodulationMode) return
        demodulationMode = mode
        runtime.mode = mode
        runtime.session.setMode(mode)
    }

    fun changeReceiverGain(value: ReceiverGain) {
        if (value == receiverGain) return
        receiverGain = value
        runtime.gain = value
        settings.saveReceiverGain(value)
        runtime.session.setGain(value)
    }

    fun stepFrequency(deltaHz: Long) {
        requestFrequency((frequencyHz + deltaHz).coerceIn(500_000L, 1_766_000_000L))
    }

}
