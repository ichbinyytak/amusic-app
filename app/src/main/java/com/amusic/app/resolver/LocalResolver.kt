package com.amusic.app.resolver

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class CandidateSong(
    val platform: String,
    val id: String,
    val hash: String = "",
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int
)

data class ResolveResult(
    val success: Boolean,
    val url: String?,
    val sourceName: String,
    val costMs: Long
)

/**
 * 手机端原生快速寻链中枢
 * 1. 在国内网络直连并发搜索（酷我/酷狗/网易云）
 * 2. 匹配最佳真实曲目 ID/Hash
 * 3. 消费 yy-lx-hub 每日最新排班端点直接提取音频流
 */
class LocalResolver(private val context: Context) {

    companion object {
        private const val TAG = "LocalResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val syncManager = SourceSyncManager(context)
    private var endpoints: List<SourceEndpoint> = emptyList()

    init {
        endpoints = syncManager.loadEndpoints()
    }

    suspend fun syncSources(force: Boolean = false) {
        val updated = syncManager.syncIfNeeded(force)
        if (updated) {
            endpoints = syncManager.loadEndpoints()
        }
    }

    suspend fun resolve(
        title: String,
        artist: String,
        album: String,
        duration: Int
    ): ResolveResult? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (title.isBlank()) return@withContext null

        try {
            // 确保端点列表已载入
            if (endpoints.isEmpty()) {
                endpoints = syncManager.loadEndpoints()
            }
            if (endpoints.isEmpty()) return@withContext null

            // 1. 国内并发快速搜曲
            val candidates = searchCandidates(title, artist, duration)
            if (candidates.isEmpty()) {
                Log.d(TAG, "未在各平台搜到最佳匹配歌曲: $title - $artist")
                return@withContext null
            }

            // 2. 挑选评分最高或优先平台的候选歌曲
            for (cand in candidates) {
                val matchedEndpoints = endpoints
                    .filter { it.platform == cand.platform }
                    .sortedBy { it.sampleLatencyMs }

                for (ep in matchedEndpoints) {
                    val directUrl = extractStreamUrl(ep, cand)
                    if (!directUrl.isNullOrBlank() && verifyAudioStream(directUrl)) {
                        val cost = System.currentTimeMillis() - t0
                        Log.i(TAG, "✓ 端侧直链解析成功 (${cost}ms) [${cand.platform}]: $directUrl")
                        return@withContext ResolveResult(
                            success = true,
                            url = directUrl,
                            sourceName = "端侧极速 · ${ep.name}",
                            costMs = cost
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "本地解析异常，转入云端兜底: ${e.message}")
        }

        return@withContext null
    }

    private suspend fun searchCandidates(title: String, artist: String, duration: Int): List<CandidateSong> = coroutineScope {
        val cleanTitle = cleanSearchKeyword(title)
        val query = "$cleanTitle $artist".trim()

        val kwDeferred = async { searchKuwo(query, title, artist, duration) }
        val kgDeferred = async { searchKugou(query, title, artist, duration) }
        val wyDeferred = async { searchNetease(query, title, artist, duration) }

        val res = mutableListOf<CandidateSong>()
        kwDeferred.await()?.let { res.add(it) }
        kgDeferred.await()?.let { res.add(it) }
        wyDeferred.await()?.let { res.add(it) }
        res
    }

    private fun searchKuwo(query: String, targetTitle: String, targetArtist: String, targetDuration: Int): CandidateSong? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://search.kuwo.cn/r.s?client=kt&all=$encodedQuery&pn=0&rn=5&uid=794762528&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newsearch=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1"
            val req = Request.Builder().url(url).header("User-Agent", "okhttp/3.10.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val list = json.optJSONArray("abslist") ?: return null
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val name = item.optString("SONGNAME")
                    val singer = item.optString("ARTIST")
                    val album = item.optString("ALBUM")
                    val dur = item.optInt("DURATION", 0)
                    val id = item.optString("MUSICRID").replace("MUSIC_", "")
                    if (id.isNotBlank() && isSongMatch(name, singer, dur, targetTitle, targetArtist, targetDuration)) {
                        return CandidateSong("kw", id, "", name, singer, album, dur)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun searchKugou(query: String, targetTitle: String, targetArtist: String, targetDuration: Int): CandidateSong? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=$encodedQuery&page=1&pagesize=5&clientver=&platform=WebFilter"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val list = json.optJSONObject("data")?.optJSONArray("lists") ?: return null
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val name = item.optString("SongName")
                    val singer = item.optString("SingerName")
                    val album = item.optString("AlbumName")
                    val dur = item.optInt("Duration", 0)
                    val hash = item.optString("FileHash")
                    val id = item.optString("Audioid")
                    if (hash.isNotBlank() && isSongMatch(name, singer, dur, targetTitle, targetArtist, targetDuration)) {
                        return CandidateSong("kg", id, hash, name, singer, album, dur)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun searchNetease(query: String, targetTitle: String, targetArtist: String, targetDuration: Int): CandidateSong? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$encodedQuery&type=1&offset=0&total=true&limit=5"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val list = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val name = item.optString("name")
                    val singer = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                    val album = item.optJSONObject("album")?.optString("name") ?: ""
                    val dur = item.optInt("duration", 0) / 1000
                    val id = item.optLong("id", 0).toString()
                    if (id != "0" && isSongMatch(name, singer, dur, targetTitle, targetArtist, targetDuration)) {
                        return CandidateSong("wy", id, "", name, singer, album, dur)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isSongMatch(name: String, singer: String, dur: Int, targetTitle: String, targetArtist: String, targetDuration: Int): Boolean {
        val n1 = name.lowercase().replace(" ", "")
        val t1 = targetTitle.lowercase().replace(" ", "")
        if (!n1.contains(t1) && !t1.contains(n1)) return false

        if (targetDuration > 0 && dur > 0 && abs(dur - targetDuration) > 12) {
            return false // 时长偏差大于 12 秒，过滤伴奏或不同版本
        }
        return true
    }

    private fun extractStreamUrl(endpoint: SourceEndpoint, song: CandidateSong): String? {
        try {
            val encName = URLEncoder.encode(song.title, "UTF-8")
            val encSinger = URLEncoder.encode(song.artist, "UTF-8")
            val encAlbum = URLEncoder.encode(song.album, "UTF-8")
            val interval = "${song.duration / 60}:${(song.duration % 60).toString().padStart(2, '0')}"

            val finalUrl = endpoint.urlTemplate
                .replace("{id}", song.id)
                .replace("{songmid}", song.id)
                .replace("{hash}", song.hash)
                .replace("{name}", encName)
                .replace("{singer}", encSinger)
                .replace("{album}", encAlbum)
                .replace("{interval}", interval)

            val reqBuilder = Request.Builder().url(finalUrl)
            endpoint.headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            if (endpoint.method == "POST") {
                val bodyStr = (endpoint.bodyTemplate ?: "")
                    .replace("{id}", song.id)
                    .replace("{songmid}", song.id)
                    .replace("{hash}", song.hash)
                    .replace("{name}", encName)
                    .replace("{singer}", encSinger)
                    .replace("{album}", encAlbum)
                    .replace("{interval}", interval)
                reqBuilder.post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                return parseUrlFromJson(body, endpoint.responseExtractor)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseUrlFromJson(jsonStr: String, extractor: String): String? {
        try {
            val root = JSONObject(jsonStr)
            if (extractor == "data.url") {
                return root.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") }
            }
            val direct = root.optString("url")
            if (direct.startsWith("http")) return direct

            val dataObj = root.optJSONObject("data")
            if (dataObj != null) {
                val u = dataObj.optString("url")
                if (u.startsWith("http")) return u
            }
        } catch (_: Exception) {}
        return null
    }

    private fun verifyAudioStream(url: String): Boolean {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1024")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code == 200 || code == 206) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                    // 拦截 81KB / 181KB 下架假语音
                    if (len == 81920L || len == 184320L) return false
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun cleanSearchKeyword(title: String): String {
        return title
            .replace(Regex("""\(.*?\)|\[.*?]|【.*?】|（.*?）"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
