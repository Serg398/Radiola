package com.example.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToLong
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
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private var device by mutableStateOf<UsbDevice?>(null)
    private var hasUsbPermission by mutableStateOf(false)
    private var usbCheckResult by mutableStateOf<String?>(null)
    private var streaming by mutableStateOf(false)
    private var streamPending by mutableStateOf(false)
    private var frequencyInput by mutableStateOf("100.000")
    private var frequencyHz by mutableStateOf(100_000_000L)
    private var requestedFrequencyHz: Long? = null
    private val spectrumState = mutableStateOf(FloatArray(0))
    private var spectrumCenterFrequencyHz by mutableStateOf(0L)
    private val waterfallState = mutableStateOf(FloatArray(0))
    private var lastSpectrumGeneration = 0L
    private var lastWaterfallGeneration = 0L
    private var waterfallFrameRate by mutableStateOf(WaterfallFrameRate.FPS_30)
    private var tuningStep by mutableStateOf(TuningStep.KHZ_100)
    private var demodulationMode by mutableStateOf(DemodulationMode.WFM)
    private var receiverGain by mutableStateOf(ReceiverGain())
    private var gainSteps by mutableStateOf(ReceiverGain.defaultSteps)
    private var actualSpectrumFps by mutableStateOf(0)
    private var actualWaterfallFps by mutableStateOf(0)
    private var wfmStereoEnabled by mutableStateOf(false)
    private var wfmStereoLocked by mutableStateOf(false)
    private var receiverSquelch by mutableStateOf(ReceiverSquelch())
    private var squelchOpen by mutableStateOf(true)
    private var signalDbfs by mutableStateOf(-90f)
    private var batteryPercent by mutableStateOf(-1)
    private var batteryCharging by mutableStateOf(false)
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
    private val receiverSession by lazy { ReceiverSession(usbManager) }
    private val receiverRates by lazy { NativeSdr.receiverRates() }
    private val displaySettings by lazy { DisplaySettings(this) }
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
            usbCheckResult = if (stats[2] == 1L) {
                "IQ: %.2f млн отсчётов/с · %s: %.1f тыс. аудиоотсчётов".format(
                    samplesPerSecond / 1_000_000.0, demodulationMode.label, stats[4] / 1_000.0
                )
            } else {
                "Поток остановился: код ${stats[3]}"
            }
            if (stats[2] == 1L) mainHandler.postDelayed(this, 500)
            else stopStream()
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
                    spectrumCenterFrequencyHz = frame.centerFrequencyHz
                    if (spectrumChanged) {
                        val bins = frame.bins
                        var peak = -90f
                        val centerBin = (bins.size / 2.0 + (frequencyHz + demodulationMode.passbandOffsetHz - frame.centerFrequencyHz) * bins.size.toDouble() / receiverRates[0]).roundToInt()
                        val halfBins = maxOf(1, demodulationMode.bandwidthHz * bins.size / (2 * receiverRates[0]))
                        for (index in maxOf(0, centerBin - halfBins)..minOf(bins.lastIndex, centerBin + halfBins)) {
                            if (bins[index] > peak) peak = bins[index]
                        }
                        signalDbfs = peak
                        squelchOpen = NativeSdr.squelchOpen()
                        wfmStereoLocked = NativeSdr.wfmStereoLocked()
                        lastSpectrumGeneration = frame.generation
                        spectrumState.value = frame.bins
                        spectrumFramesInWindow++
                    }
                    if (waterfallChanged) {
                        lastWaterfallGeneration = frame.generation
                        waterfallState.value = frame.bins
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
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDevice()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waterfallFrameRate = displaySettings.waterfallFrameRate()
        tuningStep = displaySettings.tuningStep()
        receiverGain = displaySettings.receiverGain()
        wfmStereoEnabled = displaySettings.wfmStereoEnabled()
        NativeSdr.setWfmStereo(wfmStereoEnabled)
        receiverSquelch = displaySettings.receiverSquelch()
        NativeSdr.setSquelch(receiverSquelch.enabled, receiverSquelch.thresholdDbfs)
        waterfallGate.setRate(waterfallFrameRate.framesPerSecond)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ReceiverScreen(
                deviceName = device?.productName ?: "RTL-SDR V4",
                connected = device != null,
                permitted = hasUsbPermission,
                usbCheckResult = usbCheckResult,
                streaming = streaming,
                streamPending = streamPending,
                frequencyInput = frequencyInput,
                currentFrequencyHz = frequencyHz,
                spectrumSpanHz = receiverRates[0],
                spectrumCenterFrequencyHz = spectrumCenterFrequencyHz.takeIf { it > 0 } ?: frequencyHz,
                receiveBandwidthHz = demodulationMode.bandwidthHz,
                demodulationMode = demodulationMode,
                receiverGain = receiverGain,
                gainSteps = gainSteps,
                spectrumState = spectrumState,
                waterfallState = waterfallState,
                waterfallFrameRate = waterfallFrameRate,
                tuningStep = tuningStep,
                actualSpectrumFps = actualSpectrumFps,
                actualWaterfallFps = actualWaterfallFps,
                signalDbfs = signalDbfs,
                wfmStereoEnabled = wfmStereoEnabled,
                wfmStereoLocked = streaming && wfmStereoLocked,
                onWfmStereoChange = {
                    wfmStereoEnabled = it
                    NativeSdr.setWfmStereo(it)
                    displaySettings.saveWfmStereo(it)
                },
                receiverSquelch = receiverSquelch,
                squelchOpen = streaming && squelchOpen,
                onSquelchChange = {
                    receiverSquelch = it
                    NativeSdr.setSquelch(it.enabled, it.thresholdDbfs)
                    displaySettings.saveReceiverSquelch(it)
                },
                batteryPercent = batteryPercent,
                batteryCharging = batteryCharging,
                onRequestPermission = ::requestUsbPermission,
                onCheckConnection = ::checkUsbConnection,
                onCaptureIq = ::captureIq,
                onStartStream = ::startStream,
                onStopStream = ::stopStream,
                onFrequencyInput = { frequencyInput = it },
                onTune = ::applyFrequency,
                onStep = ::stepFrequency,
                onDemodulationModeChange = ::changeDemodulationMode,
                onReceiverGainChange = ::changeReceiverGain,
                onTuningStepChange = { option ->
                    tuningStep = option
                    displaySettings.saveTuningStep(option)
                },
                onWaterfallFrameRateChange = ::changeWaterfallFrameRate
            )
        }
    }

    override fun onStart() {
        super.onStart()
        receiverSession.prepareAudio()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(USB_PERMISSION_ACTION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            RECEIVER_NOT_EXPORTED
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
        unregisterReceiver(batteryReceiver)
        receiverSession.releaseIdleAudio()
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        unregisterReceiver(usbReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        receiverSession.close()
        super.onDestroy()
    }

    private fun refreshDevice() {
        val previousName = device?.deviceName
        device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x0bda && it.productId == 0x2838
        }
        hasUsbPermission = device?.let(usbManager::hasPermission) == true
        if (device?.deviceName != previousName) usbCheckResult = null
        if (device == null && streaming) stopStream()
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
                if (device?.deviceName == currentDevice.deviceName) usbCheckResult = result
            }
        }.start()
    }

    private fun captureIq() {
        val currentDevice = device ?: return
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
                if (device?.deviceName == currentDevice.deviceName) usbCheckResult = result
            }
        }.start()
    }

    private fun startStream() {
        val currentDevice = device ?: return
        if (streaming || streamPending || !usbManager.hasPermission(currentDevice)) return
        val requestedFrequency = parseFrequency() ?: return
        streamPending = true
        usbCheckResult = "Запуск непрерывного приёма…"
        receiverSession.start(currentDevice, requestedFrequency, demodulationMode, receiverGain) { result ->
            runOnUiThread {
                streamPending = false
                if (result == 0) {
                    NativeSdr.tunerGains().takeIf { it.isNotEmpty() }?.let { gainSteps = it.sorted() }
                    frequencyHz = requestedFrequency
                    streaming = true
                    lastSpectrumGeneration = 0
                    lastWaterfallGeneration = 0
                    resetFrameTiming()
                    lastStreamBytes = 0
                    lastStreamTime = SystemClock.elapsedRealtime()
                    mainHandler.postDelayed(streamPoll, 500)
                    choreographer.postFrameCallback(spectrumFrameCallback)
                } else {
                    usbCheckResult = "Ошибка запуска потока: $result"
                }
            }
        }
    }

    private fun stopStream() {
        if (!streaming || streamPending) return
        streamPending = true
        mainHandler.removeCallbacks(streamPoll)
        choreographer.removeFrameCallback(spectrumFrameCallback)
        receiverSession.stop {
            runOnUiThread {
                streaming = false
                actualSpectrumFps = 0
                actualWaterfallFps = 0
                streamPending = false
                usbCheckResult = "Приём остановлен"
            }
        }
    }

    private fun parseFrequency(): Long? {
        val mhz = frequencyInput.replace(',', '.').toDoubleOrNull()
        if (mhz == null || mhz !in 0.5..1766.0) {
            usbCheckResult = "Введите частоту от 0,5 до 1766 МГц"
            return null
        }
        return (mhz * 1_000_000.0).roundToLong()
    }

    private fun applyFrequency() {
        val requestedFrequency = parseFrequency() ?: return
        if (!streaming) {
            frequencyHz = requestedFrequency
            usbCheckResult = "Частота установлена: ${frequencyInput} МГц"
            return
        }
        receiverSession.tune(requestedFrequency) { result ->
            runOnUiThread {
                if (result == 0) frequencyHz = requestedFrequency
                else usbCheckResult = "Ошибка настройки частоты: $result"
            }
        }
    }

    private fun changeDemodulationMode(mode: DemodulationMode) {
        if (mode == demodulationMode) return
        demodulationMode = mode
        receiverSession.setMode(mode)
    }

    private fun changeReceiverGain(value: ReceiverGain) {
        if (value == receiverGain) return
        receiverGain = value
        displaySettings.saveReceiverGain(value)
        receiverSession.setGain(value) { result ->
            if (result != 0) runOnUiThread { usbCheckResult = "Ошибка настройки усиления: $result" }
        }
    }

    private fun stepFrequency(deltaHz: Long) {
        val next = ((requestedFrequencyHz ?: frequencyHz) + deltaHz).coerceIn(500_000L, 1_766_000_000L)
        requestedFrequencyHz = next
        frequencyInput = "%.3f".format(java.util.Locale.US, next / 1_000_000.0)
        if (!streaming) {
            frequencyHz = next
            requestedFrequencyHz = null
            return
        }
        receiverSession.tune(next) { result ->
            runOnUiThread {
                if (result == 0) frequencyHz = next
                else usbCheckResult = "Ошибка настройки частоты: $result"
                if (requestedFrequencyHz == next) requestedFrequencyHz = null
            }
        }
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
