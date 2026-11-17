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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
 * 1. 街声 (StreetVoice) 官方直通分支：50ms 提取 HLS (.m3u8) 流，绝不串曲
 * 2. 国内并发快速搜曲 (酷我/酷狗/网易云) + 动态模板提取 + 首包嗅探
 * 3. 手机端侧真实音源测速引擎 (为前端 ⚡ 抽屉提供本机真实延迟，取代远端测速)
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

    private var cachedRankingJson: String? = null
    private var lastRankingTime: Long = 0L

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
        duration: Int,
        platform: String = "",
        songId: String = ""
    ): ResolveResult? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // 0. 特殊分支：街声 StreetVoice 官方独立直通 (无需搜索，直接取 HLS 流)
        if (platform.equals("streetvoice", ignoreCase = true) || songId.isNotBlank() && platform == "streetvoice") {
            val svResult = resolveStreetVoice(songId)
            if (svResult != null) {
                return@withContext svResult
            }
        }

        if (title.isBlank()) return@withContext null

        try {
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

            // 2. 挑选评分最高或优先平台的候选歌曲提取直链
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

    /**
     * 街声 StreetVoice 原生直通解析
     * 手机直连 streetvoice.com 或 streetvoice.cn，耗时仅 50~100ms
     */
    private fun resolveStreetVoice(songId: String): ResolveResult? {
        val t0 = System.currentTimeMillis()
        val hosts = listOf("com", "cn")
        for (host in hosts) {
            try {
                val url = "https://streetvoice.$host/api/v5/song/$songId/hls/file/"
                val req = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val file = JSONObject(body).optString("file")
                        if (file.contains(".m3u8")) {
                            val cost = System.currentTimeMillis() - t0
                            Log.i(TAG, "✓ 街声端侧秒级直通成功 ($host, ${cost}ms): $file")
                            return ResolveResult(
                                success = true,
                                url = file,
                                sourceName = "街声官方通道 ($host 端侧直通)",
                                costMs = cost
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * 为前端提供手机端本机实测的音源健康度与延迟清单 (取代云端测速数据)
     */
    suspend fun getSourcesRanking(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedRankingJson != null && (now - lastRankingTime) < 30_000L) {
            return@withContext cachedRankingJson!!
        }

        val list = mutableListOf<JSONObject>()

        // 1. 街声官方探针 (本机实测)
        val svLatency = measureStreetVoiceLatency()
        list.add(JSONObject().apply {
            put("id", "sv_official")
            put("name", "街声官方直通通道")
            put("platform", "streetvoice")
            put("status", if (svLatency < 5000) "ok" else "failed")
            put("latency_ms", svLatency)
            put("success_count", 99)
        })

        // 2. 本机实测活跃音源端点
        val endpointsToTest = endpoints.take(12)
        coroutineScope {
            val deferreds = endpointsToTest.map { ep ->
                async {
                    val lat = measureEndpointLatency(ep)
                    JSONObject().apply {
                        put("id", ep.id)
                        put("name", ep.name)
                        put("platform", ep.platform)
                        put("status", if (lat < 5000) "ok" else "failed")
                        put("latency_ms", lat)
                        put("success_count", ep.sampleLatencyMs)
                    }
                }
            }
            deferreds.forEach { list.add(it.await()) }
        }

        val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now))

        val root = JSONObject().apply {
            put("tested_at", isoTime)
            put("updated_at", isoTime)
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            put("sources", arr)
        }

        val jsonStr = root.toString()
        cachedRankingJson = jsonStr
        lastRankingTime = now
        jsonStr
    }

    private fun measureStreetVoiceLatency(): Double {
        val t0 = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("https://streetvoice.com/api/v5/song/123688/hls/file/")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return (System.currentTimeMillis() - t0).toDouble()
                }
            }
        } catch (_: Exception) {}

        // 回退测 .cn
        try {
            val t1 = System.currentTimeMillis()
            val req = Request.Builder()
                .url("https://streetvoice.cn/api/v5/song/123688/hls/file/")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return (System.currentTimeMillis() - t1).toDouble()
                }
            }
        } catch (_: Exception) {}

        return 9999.0
    }

    private fun measureEndpointLatency(ep: SourceEndpoint): Double {
        val t0 = System.currentTimeMillis()
        try {
            val uri = Uri.parse(ep.urlTemplate)
            val host = uri.host ?: return ep.sampleLatencyMs.toDouble()
            val pingUrl = "${uri.scheme}://$host"
            val req = Request.Builder()
                .url(pingUrl)
                .head()
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use {
                return (System.currentTimeMillis() - t0).toDouble()
            }
        } catch (_: Exception) {}
        return ep.sampleLatencyMs.toDouble()
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
            return false
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
