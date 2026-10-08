package com.example.sdr

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class ReceiverViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as RadiolaApplication).receiver
    var device by mutableStateOf<UsbDevice?>(null)
    var hasUsbPermission by mutableStateOf(false)
    var usbCheckResult by mutableStateOf<String?>(null)
    var receiverSnapshot by mutableStateOf(runtime.snapshot)
    var receiverMessage by mutableStateOf<String?>(null)
    var inputError by mutableStateOf<String?>(null)
    var frequencyInput by mutableStateOf("%.3f".format(java.util.Locale.US, runtime.session.snapshot().requestedHz / 1_000_000.0))
    var frequencyHz by mutableStateOf(runtime.session.snapshot().requestedHz)
    val spectrumState = mutableStateOf(FloatArray(0))
    val waterfallState = mutableStateOf(FloatArray(0))
    var spectrumCenterFrequencyHz by mutableStateOf(0L)
    var waterfallFrameRate by mutableStateOf(WaterfallFrameRate.FPS_30)
    var encoderFeedback by mutableStateOf(EncoderFeedback.LIGHT)
    var tuningStep by mutableStateOf(TuningStep.KHZ_100)
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
}
