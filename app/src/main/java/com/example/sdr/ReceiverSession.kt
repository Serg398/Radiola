package com.example.sdr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.util.concurrent.Executors
import android.util.Log

/** Owns the USB connection for one continuous native receiver stream. */
class ReceiverSession(private val usbManager: UsbManager) {
    private val executor = Executors.newSingleThreadExecutor()
    private var connection: UsbDeviceConnection? = null
    private val audio = AudioOutput()
    private val tuneLock = Any()
    private var pendingTune: Pair<Long, (Int) -> Unit>? = null
    private var tuneWorkerScheduled = false
    private val gainLock = Any()
    private var pendingGain: Pair<ReceiverGain, (Int) -> Unit>? = null
    private var gainWorkerScheduled = false
    private var audioWarmupEnabled = false

    fun prepareAudio() {
        executor.execute {
            audioWarmupEnabled = true
            audio.start()
        }
    }

    fun releaseIdleAudio() {
        executor.execute {
            audioWarmupEnabled = false
            if (connection == null) audio.stop()
        }
    }

    fun start(device: UsbDevice, frequencyHz: Long, mode: DemodulationMode, gain: ReceiverGain, onResult: (Int) -> Unit) {
        executor.execute {
            if (connection != null) {
                onResult(-100)
                return@execute
            }
            val opened = usbManager.openDevice(device)
            if (opened == null) {
                onResult(-101)
                return@execute
            }
            NativeSdr.setDemodulation(mode.nativeId)
            NativeSdr.setGainControls(gain.rfAgc, gain.gainTenthsDb, gain.audioAgc, gain.biasT)
            var result = NativeSdr.startStream(opened.fileDescriptor, frequencyHz)
            if (result == 0 && !audio.start()) {
                NativeSdr.stopStream()
                result = -102
            }
            if (result == 0) {
                audio.setReceiving(true)
                connection = opened
            } else {
                opened.close()
            }
            onResult(result)
        }
    }

    fun stop(onDone: () -> Unit = {}) {
        executor.execute {
            audio.setReceiving(false)
            val stats = NativeSdr.streamStats()
            Log.d("SdrTuning", "hardware_tunes=${stats[8]} digital_tunes=${stats[9]}")
            NativeSdr.stopStream()
            connection?.close()
            connection = null
            if (!audioWarmupEnabled) audio.stop()
            onDone()
        }
    }

    fun stats(): LongArray = NativeSdr.streamStats()
    fun spectrum(): SpectrumFrame = NativeSdr.spectrum()
    fun spectrumGeneration(): Long = NativeSdr.spectrumGeneration()

    fun setMode(mode: DemodulationMode) {
        executor.execute {
            NativeSdr.setDemodulation(mode.nativeId)
            audio.flushForModeChange()
        }
    }

    fun setGain(gain: ReceiverGain, onResult: (Int) -> Unit) {
        synchronized(gainLock) {
            pendingGain = gain to onResult
            if (gainWorkerScheduled) return
            gainWorkerScheduled = true
        }
        executor.execute {
            while (true) {
                val request = synchronized(gainLock) {
                    val next = pendingGain
                    pendingGain = null
                    if (next == null) gainWorkerScheduled = false
                    next
                } ?: break
                val value = request.first
                request.second(NativeSdr.setGainControls(value.rfAgc, value.gainTenthsDb, value.audioAgc, value.biasT))
            }
        }
    }

    fun tune(frequencyHz: Long, onResult: (Int) -> Unit) {
        synchronized(tuneLock) {
            pendingTune = frequencyHz to onResult
            if (tuneWorkerScheduled) return
            tuneWorkerScheduled = true
        }
        executor.execute {
            while (true) {
                val request = synchronized(tuneLock) {
                    val next = pendingTune
                    pendingTune = null
                    if (next == null) tuneWorkerScheduled = false
                    next
                } ?: break
                val result = NativeSdr.setFrequency(request.first)
                request.second(result)
            }
        }
    }

    fun close() {
        stop()
        executor.execute { audio.stop() }
        executor.shutdown()
    }
}
