package com.amusic.app.bridge

import android.webkit.JavascriptInterface

/**
 * 原生与前端 JS 交互契约
 */
class WebAppInterface(
    private val onTrackChangedCallback: (title: String, artist: String, album: String, artworkUrl: String) -> Unit,
    private val onPlaybackStateChangedCallback: (isPlaying: Boolean) -> Unit,
    private val onOpenSettingsCallback: () -> Unit,
    private val onResolveSongCallback: (reqId: String, payloadJson: String) -> Unit
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
    fun resolveSong(reqId: String?, payloadJson: String?) {
        if (!reqId.isNullOrBlank() && !payloadJson.isNullOrBlank()) {
            onResolveSongCallback(reqId, payloadJson)
        }
    }

    @JavascriptInterface
    fun log(message: String?) {
        android.util.Log.d("AmusicWeb", message ?: "")
    }
}
