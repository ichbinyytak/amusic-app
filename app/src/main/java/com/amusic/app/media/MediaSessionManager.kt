package com.amusic.app.media

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * 封装 MediaSessionCompat 契约，桥接系统锁屏、通知栏及外设（蓝牙耳机/线控）媒体按键事件
 */
class MediaSessionManager(
    context: Context,
    private val controlListenerProvider: () -> ControlListener?
) {
    interface ControlListener {
        fun onCommandPlay()
        fun onCommandPause()
        fun onCommandTogglePlay()
        fun onCommandPrev()
        fun onCommandNext()
    }

    val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "AmusicMediaSession").apply {
        setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                controlListenerProvider()?.onCommandPlay()
            }

            override fun onPause() {
                controlListenerProvider()?.onCommandPause()
            }

            override fun onSkipToNext() {
                controlListenerProvider()?.onCommandNext()
            }

            override fun onSkipToPrevious() {
                controlListenerProvider()?.onCommandPrev()
            }

            override fun onStop() {
                controlListenerProvider()?.onCommandPause()
            }
        })
        isActive = true
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    fun updateMetadata(title: String, artist: String, album: String, artwork: Bitmap?) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifEmpty { "Amusic" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist.ifEmpty { "Apple Music" })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album.ifEmpty { "Amusic" })

        if (artwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
    }
}
