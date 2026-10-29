package com.example.sdr

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import androidx.core.content.ContextCompat
import java.util.Locale

/** Online playback remains alive while the screen is hidden; STOP cancels retries too. */
class WebSdrService : Service() {
    private val runtime get() = (application as RadiolaApplication).webSdr
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var playback: PlaybackNotification
    private var lastText = ""
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { runtime.disconnect() }
    }
    private val monitor = object : Runnable {
        override fun run() {
            if (!runtime.pending && !runtime.session.active) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            }
            val text = description()
            if (text != lastText) { getSystemService(NotificationManager::class.java).notify(2, notification(text)); lastText = text }
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate(); runtime.serviceOwner = this
        playback = PlaybackNotification(this, "WebSDR receiver") { runtime.disconnect() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("websdr", "Online receiver", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = description()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(2, notification(text))
        if (intent?.action == START) runtime.startFromService()
        if (intent?.action == STOP) runtime.disconnect()
        handler.removeCallbacks(monitor); monitor.run()
        return START_NOT_STICKY
    }
    private fun description() = String.format(Locale.US, "%.3f MHz  %s  %s", runtime.session.frequencyHz / 1e6,
        runtime.session.mode.label, if (runtime.session.state == WebSdrConnectionState.Connected) "LIVE" else "CONNECTING")
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 3, Intent(this, WebSdrService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = runtime.selectedName.ifBlank { getString(R.string.app_name) }
        val builder = Notification.Builder(this, "websdr").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(runtime.selectedName.ifBlank { getString(R.string.app_name) }).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        return playback.decorate(builder, title, text, open, stop, runtime.session.state == WebSdrConnectionState.Connected)
    }
    override fun onDestroy() {
        playback.release()
        handler.removeCallbacksAndMessages(null); unregisterReceiver(noisy)
        if (runtime.serviceOwner === this) {
            runtime.serviceOwner = null
            if (!runtime.pending) runtime.disconnect()
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "com.example.sdr.WEBSDR_START"
        const val REFRESH = "com.example.sdr.WEBSDR_REFRESH"
        const val STOP = "com.example.sdr.WEBSDR_STOP"
    }
}
