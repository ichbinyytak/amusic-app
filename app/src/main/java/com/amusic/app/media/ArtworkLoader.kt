package com.amusic.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌曲封面异步拉取与防爆缩放
 * 复用 Context 单例 ImageLoader，严格限制最大尺寸以规避 Android IPC TransactionTooLargeException 崩溃
 */
class ArtworkLoader(private val context: Context) {

    suspend fun loadArtwork(url: String): Bitmap? {
        if (url.isBlank() || url.startsWith("data:")) return null

        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(512, 512)
                    .scale(Scale.FIT)
                    .build()

                val result = context.imageLoader.execute(request).drawable
                if (result is BitmapDrawable) {
                    result.bitmap
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
