package com.amusic.app.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.amusic.app.MainActivity
import com.amusic.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 音乐前台常驻服务
 * 1. 在 onCreate 立即安全启动前台通知，彻底根除 Android 8.0+ 5秒启动超时 Crash
 * 2. 协调 MediaSessionManager、AudioFocusHelper 与 ArtworkLoader
 */
typealias MusicControlListener = MediaSessionManager.ControlListener

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

    private val binder = LocalBinder()
    private var controlListener: MusicControlListener? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var audioFocusHelper: AudioFocusHelper
    private lateinit var artworkLoader: ArtworkLoader

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
        artworkLoader = ArtworkLoader(this)

        createNotificationChannel()

        mediaSessionManager = MediaSessionManager(this) { controlListener }

        audioFocusHelper = AudioFocusHelper(
            context = this,
            onAudioFocusLost = { controlListener?.onCommandPause() },
            onAudioFocusGained = { /* 可根据产品逻辑自动续播或保持 */ }
        )

        // 关键防护：在 onCreate 立即启动前台服务，满足系统 5 秒契约
        startForegroundSafely(buildNotification())
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

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
                audioFocusHelper.abandonFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 彻底根除应用划卡后产生死锁通知栏及后台僵尸服务
        audioFocusHelper.abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun updatePlaybackState(playing: Boolean) {
        this.isPlaying = playing

        if (playing) {
            audioFocusHelper.requestFocus()
        } else {
            audioFocusHelper.abandonFocus()
        }

        mediaSessionManager.updatePlaybackState(playing)
        startForegroundSafely(buildNotification())
    }

    fun updateTrackMetadata(title: String, artist: String, album: String, artworkUrl: String) {
        this.currentTitle = title.ifEmpty { "Amusic" }
        this.currentArtist = artist.ifEmpty { "Apple Music" }
        this.currentAlbum = album.ifEmpty { "Amusic" }

        if (cachedArtwork != null && artworkUrl == currentArtworkUrl) {
            mediaSessionManager.updateMetadata(currentTitle, currentArtist, currentAlbum, cachedArtwork)
            startForegroundSafely(buildNotification())
            return
        }

        this.currentArtworkUrl = artworkUrl
        this.cachedArtwork = null // 关键重置：防止切歌时残留上一首歌曲的封面造成状态污染
        mediaSessionManager.updateMetadata(currentTitle, currentArtist, currentAlbum, null)
        startForegroundSafely(buildNotification())

        if (artworkUrl.isNotEmpty()) {
            imageLoadingJob?.cancel()
            imageLoadingJob = serviceScope.launch {
                val bitmap = artworkLoader.loadArtwork(artworkUrl)
                if (bitmap != null && artworkUrl == currentArtworkUrl) {
                    cachedArtwork = bitmap
                    mediaSessionManager.updateMetadata(currentTitle, currentArtist, currentAlbum, cachedArtwork)
                    startForegroundSafely(buildNotification())
                }
            }
        }
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
                    .setMediaSession(mediaSessionManager.mediaSession.sessionToken)
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
        serviceScope.cancel()
        audioFocusHelper.abandonFocus()
        mediaSessionManager.release()
    }
}
