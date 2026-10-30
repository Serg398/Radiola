package com.example.sdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.sdr.platform.PlaybackNotification
import com.example.sdr.sources.usb.ReceiverSnapshot
import com.example.sdr.sources.usb.ReceiverState

class ReceiverService : Service() {
    private val runtime get() = (application as RadiolaApplication).receiver
    private lateinit var playback: PlaybackNotification
    private var lastNotificationAt = 0L
    private var lastState = ReceiverState.Idle
    private var ownedSessionId = -1L
    private val observer: (ReceiverSnapshot) -> Unit = { value ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (value.state in listOf(ReceiverState.Starting, ReceiverState.Running, ReceiverState.Stopping) &&
            (value.state != lastState || now - lastNotificationAt >= 1000)) {
            getSystemService(NotificationManager::class.java).notify(1, notification(value))
            lastNotificationAt = now; lastState = value.state
        }
    }
    override fun onCreate() {
        super.onCreate()
        playback = PlaybackNotification(this, "USB receiver") { runtime.stop() }
        runtime.serviceOwner = this
        ownedSessionId = runtime.session.snapshot().sessionId
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("receiver", "Receiver playback", NotificationManager.IMPORTANCE_LOW))
        runtime.onIdle = { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        runtime.observe(observer)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(runtime.snapshot), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, notification(runtime.snapshot))
        when (intent?.action) {
            STOP -> runtime.stop()
            START -> { ownedSessionId = runtime.session.snapshot().sessionId; runtime.startFromService() }
        }
        if (runtime.pendingStart == null && runtime.session.snapshot().state in listOf(ReceiverState.Idle, ReceiverState.Error)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(value: ReceiverSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = getString(R.string.app_name)
        val text = "%.3f MHz  %s  %s".format(java.util.Locale.US, value.requestedHz/1_000_000.0, runtime.mode.label, value.state.name)
        val builder = Notification.Builder(this, "receiver").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        return playback.decorate(builder, title, text, open, stop, value.state == ReceiverState.Running)
    }
    override fun onDestroy() {
        playback.release()
        runtime.removeObserver(observer)
        if (runtime.serviceOwner === this) {
            runtime.serviceOwner = null
            runtime.onIdle = null
            val current = runtime.session.snapshot()
            if (current.sessionId == ownedSessionId && current.state in listOf(ReceiverState.Starting, ReceiverState.Running))
                runtime.stop("Receiver service stopped")
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "com.example.sdr.START"
        const val STOP = "com.example.sdr.STOP"
        const val REFRESH = "com.example.sdr.REFRESH"
    }
}
