package com.example.sdr.sources.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.sdr.DemodulationMode
import com.example.sdr.NativeSdr
import com.example.sdr.SpectrumFrame
import com.example.sdr.audio.AudioOutput
import com.example.sdr.decoders.cw.CwDecodeWorker
import com.example.sdr.decoders.ftx.FtxDecodeWorker
import com.example.sdr.receiver.ReceiverGain
import java.util.concurrent.Executors

/** USB/native/audio ownership stays on one worker; control state is independently cancellable. */
class ReceiverSession(private val usbManager: UsbManager, listener: (ReceiverSnapshot) -> Unit,
    onAudioError: (String, Long) -> Unit = { _, _ -> }, context: android.content.Context? = null) {
    private val executor = Executors.newSingleThreadExecutor()
    private var connection: UsbDeviceConnection? = null
    @Volatile private var requestedDevice: UsbDevice? = null
    internal val faxDecoder = com.example.sdr.decoders.fax.FaxDecodeWorker()
    internal val cwDecoder = CwDecodeWorker(context)
    internal val ftxDecoder = FtxDecodeWorker()
    @Volatile private var cwMode = DemodulationMode.WFM
    private var cwFrequency = 0L
    private val plugins = (context?.applicationContext as? com.example.sdr.RadiolaApplication)?.plugins
    private val audio = AudioOutput(onSamples = { samples, count ->
        ftxDecoder.offer(samples, count, 48000, 2)
        plugins?.observePcm(samples, count, 48000, 2)
    },
        noiseModel = { com.example.sdr.audio.HamNoiseSettings.model(cwMode) },
        needsPlayedPcm = { cwDecoder.acceptsAudio() || faxDecoder.acceptsAudio() },
        onPlayedPcm = { samples ->
            cwDecoder.offer(samples, samples.size, 48000, 2)
            faxDecoder.offer(samples, samples.size, 48000, 2)
        }) {
        message -> onAudioError(message, controller.snapshot().sessionId)
    }
    private var audioWarmupEnabled = false
    private var lastLoggedState = ReceiverState.Idle
    private val controller: ReceiverController = ReceiverController(executor, object : ReceiverController.Backend {
        override fun available(device: String): Boolean {
            val expected = requestedDevice ?: return false
            val current = usbManager.deviceList[device] ?: return false
            return current.deviceId == expected.deviceId && current.vendorId == expected.vendorId &&
                current.productId == expected.productId && usbManager.hasPermission(current)
        }
        override fun start(device: String, hz: Long, mode: DemodulationMode, gain: ReceiverGain): Int {
            val target = requestedDevice ?: return -101
            if (!available(device)) return -101
            val opened = usbManager.openDevice(target) ?: return -101
            connection = opened // Always owned, including exceptions and cancelled START.
            NativeSdr.setDemodulation(mode.nativeId)
            val controls = NativeSdr.setGainControls(gain.rfAgc, gain.gainTenthsDb, gain.audioAgc, gain.biasT)
            if (controls != 0) return controls
            val result = NativeSdr.startStream(opened.fileDescriptor, hz)
            if (result != 0) return result
            cwMode = mode; cwFrequency = hz
            cwDecoder.configure(mode == DemodulationMode.CW, hz)
            if (!available(device)) return -101
            if (!audio.start()) return -102
            audio.setReceiving(true)
            return 0
        }
        override fun stop() {
            cwDecoder.configure(false, cwFrequency)
            ftxDecoder.configure(null, cwFrequency)
            audio.setReceiving(false)
            try { NativeSdr.stopStream() } finally {
                val owned = connection
                connection = null
                try { owned?.close() } finally { if (!audioWarmupEnabled) audio.stop() }
            }
        }
        override fun tune(hz: Long): Int {
            val result = NativeSdr.setFrequency(hz)
            if (result == 0) { cwFrequency = hz; cwDecoder.configure(cwMode == DemodulationMode.CW, hz) }
            return result
        }
        override fun mode(mode: DemodulationMode) {
            NativeSdr.setDemodulation(mode.nativeId)
            audio.flushForModeChange()
            cwMode = mode
            cwDecoder.configure(mode == DemodulationMode.CW, cwFrequency)
        }
        override fun gain(gain: ReceiverGain) = NativeSdr.setGainControls(gain.rfAgc, gain.gainTenthsDb, gain.audioAgc, gain.biasT)
        override fun warmAudio(enabled: Boolean) {
            audioWarmupEnabled = enabled
            if (enabled) audio.start() else if (connection == null) audio.stop()
        }
        override fun close() { try { audio.stop() } finally { cwDecoder.close(); ftxDecoder.close(); faxDecoder.close(); executor.shutdown() } }
    }) { value ->
        if (value.state != lastLoggedState) {
            Log.d("ReceiverControl", "state=${value.state} session=${value.sessionId} version=${value.version}")
            lastLoggedState = value.state
        }
        listener(value)
    }

    fun snapshot() = controller.snapshot()
    fun deviceAvailable(): Boolean {
        val expected = requestedDevice ?: return false
        val current = usbManager.deviceList[expected.deviceName] ?: return false
        return current.deviceId == expected.deviceId && usbManager.hasPermission(current)
    }
    fun prepareAudio() = controller.warmAudio(true)
    fun releaseIdleAudio() = controller.warmAudio(false)
    fun start(device: UsbDevice, frequencyHz: Long, mode: DemodulationMode, gain: ReceiverGain) {
        requestedDevice = device
        controller.start(device.deviceName, frequencyHz, mode, gain)
    }
    fun prepareStart(device: UsbDevice, frequencyHz: Long, mode: DemodulationMode, gain: ReceiverGain) {
        requestedDevice = device
        controller.start(device.deviceName, frequencyHz, mode, gain, deferred = true)
    }
    fun activateStart() = controller.activateStart()
    fun stop(error: String? = null) = controller.stop(error)
    fun setMode(mode: DemodulationMode) = controller.mode(mode)
    fun setGain(gain: ReceiverGain) = controller.gain(gain)
    fun tune(frequencyHz: Long) = controller.tune(frequencyHz)
    fun close() = controller.close()
    fun stats(): LongArray = NativeSdr.streamStats()
    fun audioMetrics() = audio.metrics
    fun spectrum(): SpectrumFrame = NativeSdr.spectrum()
    fun spectrumGeneration(): Long = NativeSdr.spectrumGeneration()
}
