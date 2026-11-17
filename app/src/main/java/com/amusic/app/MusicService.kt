package com.amusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "amusic_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.amusic.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.amusic.app.ACTION_PAUSE"
        const val ACTION_TOGGLE_PLAY = "com.amusic.app.ACTION_TOGGLE_PLAY"
        const val ACTION_PREV = "com.amusic.app.ACTION_PREV"
        const val ACTION_NEXT = "com.amusic.app.ACTION_NEXT"
        const val ACTION_STOP = "com.amusic.app.ACTION_STOP"
    }

    interface MusicControlListener {
        fun onCommandPlay()
        fun onCommandPause()
        fun onCommandTogglePlay()
        fun onCommandPrev()
        fun onCommandNext()
    }

    private val binder = LocalBinder()
    private var controlListener: MusicControlListener? = null

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    private var currentTitle: String = "Amusic"
    private var currentArtist: String = "Apple Music"
    private var currentAlbum: String = "Amusic"
    private var currentArtworkUrl: String = ""
    private var isPlaying: Boolean = false
    private var cachedArtwork: Bitmap? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var imageLoadingJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setControlListener(listener: MusicControlListener?) {
        this.controlListener = listener
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        initMediaSession()
        requestAudioFocus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AmusicMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    controlListener?.onCommandPlay()
                }

                override fun onPause() {
                    controlListener?.onCommandPause()
                }

                override fun onSkipToNext() {
                    controlListener?.onCommandNext()
                }

                override fun onSkipToPrevious() {
                    controlListener?.onCommandPrev()
                }

                override fun onStop() {
                    controlListener?.onCommandPause()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun requestAudioFocus() {
        val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    controlListener?.onCommandPause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 恢复播放（视需求而定）
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> controlListener?.onCommandPlay()
            ACTION_PAUSE -> controlListener?.onCommandPause()
            ACTION_TOGGLE_PLAY -> controlListener?.onCommandTogglePlay()
            ACTION_PREV -> controlListener?.onCommandPrev()
            ACTION_NEXT -> controlListener?.onCommandNext()
            ACTION_STOP -> {
                controlListener?.onCommandPause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun updatePlaybackState(playing: Boolean) {
        this.isPlaying = playing

        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
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
        updateNotification()
    }

    fun updateTrackMetadata(title: String, artist: String, album: String, artworkUrl: String) {
        this.currentTitle = title.ifEmpty { "Amusic" }
        this.currentArtist = artist.ifEmpty { "Apple Music" }
        this.currentAlbum = album.ifEmpty { "Amusic" }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)

        if (cachedArtwork != null && artworkUrl == currentArtworkUrl) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cachedArtwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cachedArtwork)
            mediaSession.setMetadata(metadataBuilder.build())
            updateNotification()
        } else {
            this.currentArtworkUrl = artworkUrl
            mediaSession.setMetadata(metadataBuilder.build())
            updateNotification()

            if (artworkUrl.isNotEmpty() && !artworkUrl.startsWith("data:")) {
                loadArtworkBitmap(artworkUrl)
            } else {
                cachedArtwork = null
            }
        }
    }

    private fun loadArtworkBitmap(url: String) {
        imageLoadingJob?.cancel()
        imageLoadingJob = serviceScope.launch {
            try {
                val loader = ImageLoader(this@MusicService)
                val request = ImageRequest.Builder(this@MusicService)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = withContext(Dispatchers.IO) {
                    loader.execute(request).drawable
                }
                if (result is BitmapDrawable) {
                    cachedArtwork = result.bitmap
                    val metadata = MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cachedArtwork)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cachedArtwork)
                        .build()
                    mediaSession.setMetadata(metadata)
                    updateNotification()
                }
            } catch (_: Exception) {
                // 加载封面失败时保持默认
            }
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPending = PendingIntent.getService(
            this, 1,
            Intent(this, MusicService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPausePending = PendingIntent.getService(
            this, 2,
            Intent(this, MusicService::class.java).apply {
                action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextPending = PendingIntent.getService(
            this, 3,
            Intent(this, MusicService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "暂停" else "播放"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText(currentAlbum)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingOpenApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_prev, "上一曲", prevPending)
            .addAction(playPauseIcon, playPauseTitle, playPausePending)
            .addAction(R.drawable.ic_next, "下一曲", nextPending)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (cachedArtwork != null) {
            builder.setLargeIcon(cachedArtwork)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        imageLoadingJob?.cancel()
        mediaSession.isActive = false
        mediaSession.release()
    }
}
