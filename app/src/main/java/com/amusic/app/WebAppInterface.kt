package com.amusic.app

import android.webkit.JavascriptInterface

class WebAppInterface(
    private val onTrackChangedCallback: (title: String, artist: String, album: String, artworkUrl: String) -> Unit,
    private val onPlaybackStateChangedCallback: (isPlaying: Boolean) -> Unit,
    private val onOpenSettingsCallback: () -> Unit
) {

    @JavascriptInterface
    fun onTrackChanged(title: String?, artist: String?, album: String?, artworkUrl: String?) {
        onTrackChangedCallback(
            title ?: "未知曲目",
            artist ?: "未知歌手",
            album ?: "Amusic",
            artworkUrl ?: ""
        )
    }

    @JavascriptInterface
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        onPlaybackStateChangedCallback(isPlaying)
    }

    @JavascriptInterface
    fun openServerSettings() {
        onOpenSettingsCallback()
    }

    @JavascriptInterface
    fun log(message: String?) {
        android.util.Log.d("AmusicWeb", message ?: "")
    }
}
