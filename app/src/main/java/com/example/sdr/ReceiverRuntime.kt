package com.example.sdr

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class RadiolaApplication : Application() {
    val receiver by lazy { ReceiverRuntime(this) }
}

/** One process-wide hardware owner, independent of Activity and ViewModel recreation. */
class ReceiverRuntime(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val usb = context.getSystemService(UsbManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val observers = mutableSetOf<(ReceiverSnapshot) -> Unit>()
    var snapshot by mutableStateOf(ReceiverSnapshot())
        private set
    var mode = DemodulationMode.WFM
    var gain = DisplaySettings(context).receiverGain()
    var stereo = DisplaySettings(context).wfmStereoEnabled()
    var squelch = DisplaySettings(context).receiverSquelch()
    var pendingStart: UsbDevice? = null
        private set
    private var visible = 0
    private var focusHeld = false
    private var lastMetricsAt = 0L
    var onIdle: (() -> Unit)? = null
    internal var serviceOwner: Any? = null
    val session: ReceiverSession = ReceiverSession(usb, { value -> handler.post { accept(value) } }, { message, id ->
        handler.post {
            if (id == session.snapshot().sessionId && session.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running))
                stop("Audio output failed: $message")
        }
    })
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                stop("Audio focus lost")
            // Android handles ducking; loss requiring a pause stops reception explicitly.
        }, handler).build()
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                (detached?.deviceName == pendingStart?.deviceName || !session.deviceAvailable())) stop("USB device disconnected")
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                session.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running)) stop("Headphones disconnected")
        }
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (session.snapshot().state == ReceiverState.Running) {
                val stats = session.stats()
                if (SystemClock.elapsedRealtime() - lastMetricsAt > 5000) {
                    val output = session.audioMetrics()
                    Log.d("ReceiverAudio", "ring_frames=${stats[11]} trimmed_samples=${stats[10]} overflow_samples=${stats[5]} track_frames=${output.queuedFrames} route=${output.route}")
                    lastMetricsAt = SystemClock.elapsedRealtime()
                }
                if (stats[2] != 1L) stop("Stream stopped: ${stats[3]}")
                else handler.postDelayed(this, 500)
            }
        }
    }
    init {
        ContextCompat.registerReceiver(context, usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        NativeSdr.setWfmStereo(stereo)
        NativeSdr.setSquelch(squelch.enabled, squelch.thresholdDbfs)
    }
    fun observe(observer: (ReceiverSnapshot) -> Unit) { observers += observer; observer(snapshot) }
    fun removeObserver(observer: (ReceiverSnapshot) -> Unit) { observers -= observer }
    fun visible(enabled: Boolean) {
        visible = (visible + if (enabled) 1 else -1).coerceAtLeast(0)
        if (visible > 0) session.prepareAudio() else session.releaseIdleAudio()
    }
    fun requestStart(device: UsbDevice) {
        if (session.snapshot().state in listOf(ReceiverState.Starting, ReceiverState.Running, ReceiverState.Stopping) || pendingStart != null) return
        pendingStart = device
        session.prepareStart(device, session.snapshot().requestedHz, mode, gain)
        try { context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ReceiverService.START)) }
        catch (error: Exception) { pendingStart = null; session.stop("Cannot start receiver service: ${error.message}") }
    }
    fun startFromService() {
        val device = pendingStart ?: return
        pendingStart = null
        if (usb.deviceList[device.deviceName] == null || !usb.hasPermission(device)) { stop("USB device disconnected"); return }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop("Audio focus unavailable"); return
        }
        focusHeld = true
        session.activateStart()
    }
    fun stop(error: String? = null) {
        pendingStart = null
        session.stop(error)
    }
    private fun accept(value: ReceiverSnapshot) {
        if (value.version < session.snapshot().version || value.version <= snapshot.version) return
        val previous = snapshot.state
        snapshot = value
        if (value.state != previous) {
            handler.removeCallbacks(monitor)
            if (value.state == ReceiverState.Running) handler.postDelayed(monitor, 500)
        }
        if (value.state == ReceiverState.Idle || value.state == ReceiverState.Error) {
            if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
            onIdle?.invoke()
        }
        observers.toList().forEach { it(value) }
    }
}
