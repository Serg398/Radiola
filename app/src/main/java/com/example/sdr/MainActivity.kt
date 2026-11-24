package com.example.sdr

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.sdr.platform.DisplaySettings
import com.example.sdr.platform.SignalMeterInertia
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.platform.EncoderHaptics
import com.example.sdr.receiver.VisualFramePacer
import com.example.sdr.receiver.offsetForBandwidth
import com.example.sdr.sources.usb.ReceiverSnapshot
import com.example.sdr.sources.usb.ReceiverState
import com.example.sdr.telemetry.RenderTimings
import com.example.sdr.ui.receiver.ReceiverScreen
import com.example.sdr.ui.receiver.ReceiverViewModel
import com.example.sdr.ui.theme.RadiolaTheme
import kotlin.math.roundToInt

private const val USB_PERMISSION_ACTION = "com.example.sdr.USB_PERMISSION"

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && runtime.session.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running))
            startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.REFRESH))
        val online = (application as RadiolaApplication).webSdr
        if (granted && online.serviceOwner != null && (online.pending || online.session.active))
            startService(Intent(this, WebSdrService::class.java).setAction(WebSdrService.REFRESH))
    }
    private val model by lazy { ViewModelProvider(this)[ReceiverViewModel::class.java] }
    private val runtime get() = (application as RadiolaApplication).receiver
    private val receiverObserver: (ReceiverSnapshot) -> Unit = { acceptReceiverSnapshot(it) }
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private var device
        get() = model.device
        set(value) { model.device = value }
    private var hasUsbPermission
        get() = model.hasUsbPermission
        set(value) { model.hasUsbPermission = value }

    private var receiverSnapshot
        get() = model.receiverSnapshot
        set(value) { model.receiverSnapshot = value }
    private val streaming get() = receiverSnapshot.state == ReceiverState.Running
    private val streamPending get() = receiverSnapshot.state == ReceiverState.Starting || receiverSnapshot.state == ReceiverState.Stopping

    private var destroyed = false
    private var frequencyInput
        get() = model.frequencyInput
        set(value) { model.frequencyInput = value }
    private var frequencyHz
        get() = model.frequencyHz
        set(value) { model.frequencyHz = value }
    private val spectrumState get() = model.spectrumState
    private val visualFrames = VisualFramePacer()
    private val encoderHaptics by lazy { EncoderHaptics(this) }
    private var encoderFeedback
        get() = model.encoderFeedback
        set(value) { model.encoderFeedback = value }
    private var tuningStep
        get() = model.tuningStep
        set(value) { model.tuningStep = value }
    private var demodulationMode
        get() = model.demodulationMode
        set(value) { model.demodulationMode = value }
    private var receiverGain
        get() = model.receiverGain
        set(value) { model.receiverGain = value }
    private var gainSteps
        get() = model.gainSteps
        set(value) { model.gainSteps = value }

    private var actualWaterfallFps
        get() = model.actualWaterfallFps
        set(value) { model.actualWaterfallFps = value }
    private var wfmStereoEnabled
        get() = model.wfmStereoEnabled
        set(value) { model.wfmStereoEnabled = value }
    private var wfmStereoLocked
        get() = model.wfmStereoLocked
        set(value) { model.wfmStereoLocked = value }
    private var receiverSquelch
        get() = model.receiverSquelch
        set(value) { model.receiverSquelch = value }
    private var squelchOpen
        get() = model.squelchOpen
        set(value) { model.squelchOpen = value }
    private var signalDbfs
        get() = model.signalDbfs
        set(value) { model.signalDbfs = value }
    private var batteryPercent
        get() = model.batteryPercent
        set(value) { model.batteryPercent = value }
    private var batteryCharging
        get() = model.batteryCharging
        set(value) { model.batteryCharging = value }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPercent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
    private var fpsWindowStartNanos = 0L
    private var waterfallFramesInWindow = 0
    private val receiverSession get() = runtime.session
    private val receiverRates by lazy { NativeSdr.receiverRates() }
    private val displaySettings by lazy { DisplaySettings(this) }
    private var signalMeterInertia by mutableStateOf(SignalMeterInertia.MEDIUM)
    private var analogSignalMeter by mutableStateOf(true)
    private var screenAlwaysOn by mutableStateOf(true)
    private fun applyScreenAlwaysOn(enabled: Boolean) {
        screenAlwaysOn = enabled
        window.setFlags(if (enabled) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val choreographer by lazy { Choreographer.getInstance() }
    private val spectrumFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!streaming) return
            val generation = receiverSession.spectrumGeneration()
            if (generation != visualFrames.lastGeneration)
                visualFrames.offer(receiverSession.spectrum())
            visualFrames.poll(frameTimeNanos)?.let(::publishVisualFrame)
            if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = frameTimeNanos
            val windowDuration = frameTimeNanos - fpsWindowStartNanos
            if (windowDuration >= 1_000_000_000L) {
                actualWaterfallFps = (waterfallFramesInWindow * 1_000_000_000.0 / windowDuration).roundToInt()
                fpsWindowStartNanos = frameTimeNanos
                waterfallFramesInWindow = 0
            }
            choreographer.postFrameCallback(this)
        }
    }

    private fun publishVisualFrame(frame: SpectrumFrame) {
        if (frame.timestampNanos > 0)
            RenderTimings.frameAge.record((System.nanoTime() - frame.timestampNanos).coerceAtLeast(0))
        val bins = frame.bins
        var peak = -90f
        val centerBin = (bins.size / 2.0 + (frequencyHz + demodulationMode.offsetForBandwidth(runtime.bandwidth(demodulationMode)) - frame.centerFrequencyHz) * bins.size.toDouble() / frame.spanHz).roundToInt()
        val halfBins = maxOf(1, runtime.bandwidth(demodulationMode) * bins.size / (2 * frame.spanHz))
        for (index in maxOf(0, centerBin - halfBins)..minOf(bins.lastIndex, centerBin + halfBins)) {
            if (bins[index] > peak) peak = bins[index]
        }
        signalDbfs = peak
        squelchOpen = NativeSdr.squelchOpen()
        wfmStereoLocked = NativeSdr.wfmStereoLocked()
        spectrumState.value = frame
        model.waterfallHistory.append(frame)
        waterfallFramesInWindow++
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_PERMISSION_ACTION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                        detached?.deviceName == device?.deviceName &&
                        receiverSession.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running))
                        receiverSession.stop("USB device disconnected")
                    refreshDevice()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tuningStep = displaySettings.tuningStep()
        encoderFeedback = displaySettings.encoderFeedback()
        receiverGain = displaySettings.receiverGain()
        wfmStereoEnabled = displaySettings.wfmStereoEnabled()
        NativeSdr.setWfmStereo(wfmStereoEnabled)
        runtime.stereo = wfmStereoEnabled
        receiverSquelch = displaySettings.receiverSquelch()
        NativeSdr.setSquelch(receiverSquelch.enabled, receiverSquelch.thresholdDbfs)
        signalMeterInertia = displaySettings.signalMeterInertia()
        analogSignalMeter = displaySettings.analogSignalMeter()
        applyScreenAlwaysOn(displaySettings.screenAlwaysOn())
        setContent {
            RadiolaTheme {
            ReceiverScreen(
                presentation = model.presentation,
                signalMeterInertia = signalMeterInertia,
                onSignalMeterInertiaChange = {
                    signalMeterInertia = it
                    displaySettings.saveSignalMeterInertia(it)
                },
                analogSignalMeter = analogSignalMeter,
                onAnalogSignalMeterChange = {
                    analogSignalMeter = it
                    displaySettings.saveAnalogSignalMeter(it)
                },
                screenAlwaysOn = screenAlwaysOn,
                onScreenAlwaysOnChange = {
                    applyScreenAlwaysOn(it)
                    displaySettings.saveScreenAlwaysOn(it)
                },
                connected = device != null,
                permitted = hasUsbPermission,
                streaming = streaming,
                streamPending = streamPending,
                starting = receiverSnapshot.state == ReceiverState.Starting,
                frequencyInput = frequencyInput,
                spectrumSpanHz = receiverRates[0],
                bandwidthDefault = runtime.bandwidths[demodulationMode] == null,
                rdsInformation = runtime.rds,
                receiverGain = receiverGain,
                gainSteps = gainSteps,
                spectrumState = spectrumState,
                waterfallHistory = model.waterfallHistory,
                tuningStep = tuningStep,
                encoderFeedback = encoderFeedback,
                onEncoderTick = { encoderHaptics.tick(encoderFeedback) },
                onEncoderFeedbackChange = {
                    encoderFeedback = it
                    displaySettings.saveEncoderFeedback(it)
                    if (it == EncoderFeedback.OFF) encoderHaptics.cancel()
                    else encoderHaptics.tick(it)
                },
                actualWaterfallFps = actualWaterfallFps,
                signalDbfs = signalDbfs,
                wfmStereoEnabled = wfmStereoEnabled,
                wfmStereoLocked = streaming && wfmStereoLocked,
                onWfmStereoChange = {
                    wfmStereoEnabled = it
                    NativeSdr.setWfmStereo(it)
                    runtime.stereo = it
                    displaySettings.saveWfmStereo(it)
                },
                receiverSquelch = receiverSquelch,
                squelchOpen = streaming && squelchOpen,
                onSquelchChange = {
                    receiverSquelch = it
                    NativeSdr.setSquelch(it.enabled, it.thresholdDbfs)
                    runtime.squelch = it
                    displaySettings.saveReceiverSquelch(it)
                },
                batteryPercent = batteryPercent,
                batteryCharging = batteryCharging,
                onRequestPermission = ::requestUsbPermission,
                onStartStream = ::startStream,
                onPlaybackStart = ::requestNotificationPermission,
                onStopStream = ::stopStream,
                onFrequencyInput = { frequencyInput = it },
                onTune = model::applyFrequency,
                onReceiverGainChange = model::changeReceiverGain,
                onTuningStepChange = { option ->
                    tuningStep = option
                    displaySettings.saveTuningStep(option)
                },
            )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.visible(true)
        runtime.observe(receiverObserver)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this, usbReceiver,
            IntentFilter().apply {
                addAction(USB_PERMISSION_ACTION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshDevice()
        if (streaming) {
            startVisualFrames()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onStop() {
        encoderHaptics.cancel()
        unregisterReceiver(batteryReceiver)
        runtime.removeObserver(receiverObserver)
        runtime.visible(false)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        resetFrameTiming()
        unregisterReceiver(usbReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        choreographer.removeFrameCallback(spectrumFrameCallback)
        resetFrameTiming()
        destroyed = true
        runtime.removeObserver(receiverObserver)
        super.onDestroy()
    }

    private fun refreshDevice() {
        device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x0bda && it.productId == 0x2838
        }
        hasUsbPermission = device?.let(usbManager::hasPermission) == true
        if (receiverSession.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running) &&
            !receiverSession.deviceAvailable())
            receiverSession.stop("USB device disconnected")
    }

    private fun requestUsbPermission() {
        val currentDevice = device ?: return
        val intent = Intent(USB_PERMISSION_ACTION).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(currentDevice, pendingIntent)
    }

    private fun acceptReceiverSnapshot(value: ReceiverSnapshot) {
        if (destroyed || value.version < receiverSession.snapshot().version || value.version <= receiverSnapshot.version) return
        val wasRunning = streaming
        if (value.state == ReceiverState.Running && !wasRunning && !receiverSession.deviceAvailable()) {
            receiverSession.stop("USB device disconnected")
            return
        }
        receiverSnapshot = value
        if (streaming && !wasRunning) {
            NativeSdr.tunerGains().takeIf { it.isNotEmpty() }?.let { gainSteps = it.sorted() }
            startVisualFrames()
        } else if (!streaming) {
            choreographer.removeFrameCallback(spectrumFrameCallback)
            resetFrameTiming()

            actualWaterfallFps = 0
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startStream() {
        val currentDevice = device ?: return
        val state = receiverSession.snapshot().state
        if (state in listOf(ReceiverState.Starting, ReceiverState.Running, ReceiverState.Stopping) ||
            !usbManager.hasPermission(currentDevice)) return
        val requestedFrequency = model.parseFrequency() ?: return
        model.requestFrequency(requestedFrequency)
        requestNotificationPermission()
        runtime.mode = demodulationMode
        runtime.gain = receiverGain
        runtime.requestStart(currentDevice)
    }

    private fun stopStream() {
        runtime.stop()
        choreographer.removeFrameCallback(spectrumFrameCallback)
        resetFrameTiming()
    }

    private fun startVisualFrames() {
        choreographer.removeFrameCallback(spectrumFrameCallback)
        resetFrameTiming()
        choreographer.postFrameCallback(spectrumFrameCallback)
    }

    private fun resetFrameTiming() {
        visualFrames.clear()
        fpsWindowStartNanos = 0L
        waterfallFramesInWindow = 0
    }

}
