package com.amusic.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 音频焦点智能管理
 * 遵循“随播随申，不用即还”的第一性原则，避免在应用刚启动未播放时滥用抢占其他 App 声音。
 */
class AudioFocusHelper(
    context: Context,
    private val onAudioFocusLost: (isTransient: Boolean) -> Unit,
    private val onAudioFocusGained: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                onAudioFocusLost(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                onAudioFocusLost(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onAudioFocusGained()
            }
        }
    }

    fun requestFocus(): Boolean {
        if (hasFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasFocus
    }

    fun abandonFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasFocus = false
    }
}
