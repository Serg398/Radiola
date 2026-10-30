package com.example.sdr.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.example.sdr.R

/** A live receiver has no seek position; pausing stops reception. */
internal class PlaybackNotification(context: Context, tag: String, onStop: () -> Unit) {
    private val session = MediaSession(context, tag).apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPause() = onStop()
            override fun onStop() = onStop()
        })
        isActive = true
    }

    fun decorate(builder: Notification.Builder, title: String, text: String,
                 open: PendingIntent, stop: PendingIntent, playing: Boolean): Notification {
        session.setSessionActivity(open)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_BUFFERING,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        return builder.setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "STOP", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    fun release() { session.isActive = false; session.release() }
}
