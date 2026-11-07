package com.example.sdr

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.example.sdr.platform.EncoderFeedback
import com.example.sdr.platform.EncoderHaptics
import com.example.sdr.receiver.offsetForBandwidth
import com.example.sdr.sources.usb.ReceiverSnapshot
import com.example.sdr.sources.usb.ReceiverState
import com.example.sdr.telemetry.RenderTimings
import com.example.sdr.ui.receiver.ReceiverScreen
import com.example.sdr.ui.receiver.ReceiverViewModel
import com.example.sdr.ui.receiver.WaterfallFrameRate
import com.example.sdr.ui.theme.RadiolaTheme
import kotlin.math.roundToInt

private const val USB_PERMISSION_ACTION = "com.example.sdr.USB_PERMISSION"

private class FrameGate(private var framesPerSecond: Int) {
    private var previousVsyncNanos = 0L
    private var accumulatedNanos = 0L

    fun setRate(value: Int) {
        framesPerSecond = value
        reset()
    }

    fun reset() {
        previousVsyncNanos = 0L
        accumulatedNanos = 0L
    }

    fun isDue(vsyncNanos: Long): Boolean {
        if (previousVsyncNanos == 0L) {
            previousVsyncNanos = vsyncNanos
            return true
        }
        accumulatedNanos += (vsyncNanos - previousVsyncNanos).coerceIn(0L, 100_000_000L)
        previousVsyncNanos = vsyncNanos
        val interval = 1_000_000_000L / framesPerSecond
        if (accumulatedNanos < interval) return false
        accumulatedNanos %= interval
        return true
    }
}

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
    private var usbCheckResult
        get() = model.usbCheckResult
        set(value) { model.usbCheckResult = value }
    private var receiverSnapshot
        get() = model.receiverSnapshot
        set(value) { model.receiverSnapshot = value }
    private val streaming get() = receiverSnapshot.state == ReceiverState.Running
    private val streamPending get() = receiverSnapshot.state == ReceiverState.Starting || receiverSnapshot.state == ReceiverState.Stopping
    private var receiverMessage
        get() = model.receiverMessage
        set(value) { model.receiverMessage = value }
    private var inputError
        get() = model.inputError
        set(value) { model.inputError = value }
    private var destroyed = false
    private var frequencyInput
        get() = model.frequencyInput
        set(value) { model.frequencyInput = value }
    private var frequencyHz
        get() = model.frequencyHz
        set(value) { model.frequencyHz = value }
    private val spectrumState get() = model.spectrumState
    private val waterfallState get() = model.waterfallState
    private var lastSpectrumGeneration = 0L
    private var lastWaterfallGeneration = 0L
    private var waterfallFrameRate
        get() = model.waterfallFrameRate
        set(value) { model.waterfallFrameRate = value }
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
    private var actualSpectrumFps
        get() = model.actualSpectrumFps
        set(value) { model.actualSpectrumFps = value }
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
    private val spectrumGate = FrameGate(30)
    private val waterfallGate = FrameGate(30)
    private var fpsWindowStartNanos = 0L
    private var spectrumFramesInWindow = 0
    private var waterfallFramesInWindow = 0
    private val receiverSession get() = runtime.session
    private val receiverRates by lazy { NativeSdr.receiverRates() }
    private val displaySettings by lazy { DisplaySettings(this) }
    private var analogSignalMeter by mutableStateOf(true)
    private var screenAlwaysOn by mutableStateOf(true)
    private fun applyScreenAlwaysOn(enabled: Boolean) {
        screenAlwaysOn = enabled
        window.setFlags(if (enabled) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer by lazy { Choreographer.getInstance() }
    private var lastStreamBytes = 0L
    private var lastStreamTime = 0L
    private val streamPoll = object : Runnable {
        override fun run() {
            if (!streaming) return
            val stats = receiverSession.stats()
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastStreamTime).coerceAtLeast(1)
            val samplesPerSecond = (stats[0] - lastStreamBytes) * 500L / elapsed
            lastStreamBytes = stats[0]
            lastStreamTime = now
            receiverMessage = if (stats[2] == 1L) {
                "IQ: %.2f млн отсчётов/с · %s: %.1f тыс. аудиоотсчётов".format(
                    samplesPerSecond / 1_000_000.0, demodulationMode.label, stats[4] / 1_000.0
                )
            } else {
                "Поток остановился: код ${stats[3]}"
            }
            if (stats[2] == 1L) mainHandler.postDelayed(this, 500)
            else runtime.stop("Stream stopped: ${stats[3]}")
        }
    }
    private val spectrumFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!streaming) return
            val updateSpectrum = spectrumGate.isDue(frameTimeNanos)
            val updateWaterfall = waterfallGate.isDue(frameTimeNanos)
            if (updateSpectrum || updateWaterfall) {
                val generation = receiverSession.spectrumGeneration()
                val spectrumChanged = updateSpectrum && generation != lastSpectrumGeneration
                val waterfallChanged = updateWaterfall && generation != lastWaterfallGeneration
                if (spectrumChanged || waterfallChanged) {
                    val frame = receiverSession.spectrum()
                    if (frame.timestampNanos > 0) RenderTimings.frameAge.record((System.nanoTime() - frame.timestampNanos).coerceAtLeast(0))
                    if (spectrumChanged) {
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
                        lastSpectrumGeneration = frame.generation
                        spectrumState.value = frame
                        spectrumFramesInWindow++
                    }
                    if (waterfallChanged) {
                        lastWaterfallGeneration = frame.generation
                        model.waterfallHistory.append(frame)
                        waterfallState.value = frame
                        waterfallFramesInWindow++
                    }
                }
            }
            if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = frameTimeNanos
            val windowDuration = frameTimeNanos - fpsWindowStartNanos
            if (windowDuration >= 1_000_000_000L) {
                actualSpectrumFps = (spectrumFramesInWindow * 1_000_000_000.0 / windowDuration).roundToInt()
                actualWaterfallFps = (waterfallFramesInWindow * 1_000_000_000.0 / windowDuration).roundToInt()
                fpsWindowStartNanos = frameTimeNanos
                spectrumFramesInWindow = 0
                waterfallFramesInWindow = 0
            }
            choreographer.postFrameCallback(this)
        }
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
        waterfallFrameRate = displaySettings.waterfallFrameRate()
        tuningStep = displaySettings.tuningStep()
        encoderFeedback = displaySettings.encoderFeedback()
        receiverGain = displaySettings.receiverGain()
        wfmStereoEnabled = displaySettings.wfmStereoEnabled()
        NativeSdr.setWfmStereo(wfmStereoEnabled)
        runtime.stereo = wfmStereoEnabled
        receiverSquelch = displaySettings.receiverSquelch()
        NativeSdr.setSquelch(receiverSquelch.enabled, receiverSquelch.thresholdDbfs)
        waterfallGate.setRate(waterfallFrameRate.framesPerSecond)
        analogSignalMeter = displaySettings.analogSignalMeter()
        applyScreenAlwaysOn(displaySettings.screenAlwaysOn())
        setContent {
            RadiolaTheme {
            ReceiverScreen(
                presentation = model.presentation,
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
                deviceName = device?.productName ?: "RTL-SDR V4",
                connected = device != null,
                permitted = hasUsbPermission,
                usbCheckResult = inputError ?: receiverSnapshot.error ?: usbCheckResult ?: receiverMessage,
                streaming = streaming,
                streamPending = streamPending,
                starting = receiverSnapshot.state == ReceiverState.Starting,
                receiverState = receiverSnapshot.state,
                frequencyInput = frequencyInput,
                spectrumSpanHz = receiverRates[0],
                bandwidthDefault = runtime.bandwidths[demodulationMode] == null,
                rdsInformation = runtime.rds,
                receiverGain = receiverGain,
                gainSteps = gainSteps,
                spectrumState = spectrumState,
                waterfallState = waterfallState,
                waterfallHistory = model.waterfallHistory,
                waterfallFrameRate = waterfallFrameRate,
                tuningStep = tuningStep,
                encoderFeedback = encoderFeedback,
                onEncoderTick = { encoderHaptics.tick(encoderFeedback) },
                onEncoderFeedbackChange = {
                    encoderFeedback = it
                    displaySettings.saveEncoderFeedback(it)
                    if (it == EncoderFeedback.OFF) encoderHaptics.cancel()
                    else encoderHaptics.tick(it)
                },
                actualSpectrumFps = actualSpectrumFps,
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
                onCheckConnection = ::checkUsbConnection,
                onCaptureIq = ::captureIq,
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
                onWaterfallFrameRateChange = ::changeWaterfallFrameRate
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
            resetFrameTiming()
            lastStreamBytes = receiverSession.stats()[0]
            lastStreamTime = SystemClock.elapsedRealtime()
            mainHandler.post(streamPoll)
            choreographer.postFrameCallback(spectrumFrameCallback)
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
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        unregisterReceiver(usbReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        destroyed = true
        runtime.removeObserver(receiverObserver)
        super.onDestroy()
    }

    private fun refreshDevice() {
        val previousName = device?.deviceName
        device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x0bda && it.productId == 0x2838
        }
        hasUsbPermission = device?.let(usbManager::hasPermission) == true
        if (device?.deviceName != previousName) usbCheckResult = null
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

    private fun checkUsbConnection() {
        val currentDevice = device ?: return
        val session = receiverSession.snapshot()
        if (session.state !in listOf(ReceiverState.Idle, ReceiverState.Error)) return
        if (!usbManager.hasPermission(currentDevice)) return
        usbCheckResult = "Проверка USB-связи…"
        Thread {
            val result = try {
                val connection = usbManager.openDevice(currentDevice)
                    ?: error("Не удалось открыть USB-устройство")
                try {
                    check(NativeSdr.isUsbDescriptorOpen(connection.fileDescriptor)) {
                        "Нативный модуль не получил USB-дескриптор"
                    }
                    val descriptor = ByteArray(18)
                    val count = connection.controlTransfer(
                        UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                        0x06, 0x0100, 0, descriptor, descriptor.size, 1000
                    )
                    val vendorId = (descriptor[9].toInt() and 0xff) shl 8 or
                        (descriptor[8].toInt() and 0xff)
                    val productId = (descriptor[11].toInt() and 0xff) shl 8 or
                        (descriptor[10].toInt() and 0xff)
                    if (count == 18 && vendorId == 0x0bda && productId == 0x2838) {
                        "USB-связь работает: 0BDA:2838"
                    } else {
                        "Ошибка USB-ответа: получено $count байт"
                    }
                } finally {
                    connection.close()
                }
            } catch (error: Exception) {
                "Ошибка USB-связи: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                if (!destroyed && device?.deviceName == currentDevice.deviceName &&
                    receiverSession.snapshot().sessionId == session.sessionId) usbCheckResult = result
            }
        }.start()
    }

    private fun captureIq() {
        val currentDevice = device ?: return
        val session = receiverSession.snapshot()
        if (session.state !in listOf(ReceiverState.Idle, ReceiverState.Error)) return
        if (!usbManager.hasPermission(currentDevice)) return
        usbCheckResult = "Чтение IQ на 100 МГц…"
        Thread {
            val result = try {
                val connection = usbManager.openDevice(currentDevice)
                    ?: error("Не удалось открыть USB-устройство")
                try {
                    NativeSdr.captureIq(connection.fileDescriptor, frequencyHz)
                } finally {
                    connection.close()
                }
            } catch (error: Exception) {
                "Ошибка приёма: ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                if (!destroyed && device?.deviceName == currentDevice.deviceName &&
                    receiverSession.snapshot().sessionId == session.sessionId) usbCheckResult = result
            }
        }.start()
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
            lastSpectrumGeneration = 0
            lastWaterfallGeneration = 0
            resetFrameTiming()
            lastStreamBytes = receiverSession.stats()[0]
            lastStreamTime = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(streamPoll)
            choreographer.removeFrameCallback(spectrumFrameCallback)
            mainHandler.postDelayed(streamPoll, 500)
            choreographer.postFrameCallback(spectrumFrameCallback)
        } else if (!streaming) {
            mainHandler.removeCallbacks(streamPoll)
            choreographer.removeFrameCallback(spectrumFrameCallback)
            actualSpectrumFps = 0
            actualWaterfallFps = 0
            receiverMessage = when (value.state) {
                ReceiverState.Starting -> "Starting receiver..."
                ReceiverState.Stopping -> "Stopping receiver..."
                else -> null
            }
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
        usbCheckResult = null
        requestNotificationPermission()
        runtime.mode = demodulationMode
        runtime.gain = receiverGain
        runtime.requestStart(currentDevice)
    }

    private fun stopStream() {
        runtime.stop()
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
    }

    private fun resetFrameTiming() {
        spectrumGate.reset()
        waterfallGate.reset()
        fpsWindowStartNanos = 0L
        spectrumFramesInWindow = 0
        waterfallFramesInWindow = 0
    }

    private fun changeWaterfallFrameRate(option: WaterfallFrameRate) {
        waterfallFrameRate = option
        waterfallGate.setRate(option.framesPerSecond)
        actualWaterfallFps = 0
        displaySettings.saveWaterfallFrameRate(option)
    }
}
