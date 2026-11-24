package com.amusic.app.resolver

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
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
    val costMs: Long,
    val lyrics: JSONArray? = null
)

/**
 * 手机端原生快速寻链中枢
 * 1. 街声 (StreetVoice) 双域名并发竞速直通 + 歌词内存缓存
 * 2. 国内并发快速搜曲 (酷我/酷狗/网易云) + ID 优先模板提取 + 1.2MB 试听残卷拦截
 * 3. 手机端侧真实音源测速引擎
 */
class LocalResolver(private val context: Context) {

    companion object {
        private const val TAG = "LocalResolver"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val LYRIC_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MIN_FULL_AUDIO_BYTES = 1_200_000L
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val syncManager = SourceSyncManager(context)
    private var endpoints: List<SourceEndpoint> = emptyList()

    // 街声歌词内存缓存: songId -> (timestampMs, lyricsJsonArrayStr)
    private val lyricCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private var cachedRankingJson: String? = null
    private var lastRankingTime: Long = 0L

    init {
        endpoints = syncManager.loadEndpoints()
        LocalAudioProxy.start()
    }

    suspend fun syncSources(force: Boolean = false) {
        val updated = syncManager.syncIfNeeded(force)
        if (updated) {
            endpoints = syncManager.loadEndpoints()
            cachedRankingJson = null
            lastRankingTime = 0L
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
        val isStreetVoice = platform.equals("streetvoice", ignoreCase = true) ||
            platform.equals("sv", ignoreCase = true)

        // 0. 街声独立直通分支（不再落入商业曲库空转）
        if (isStreetVoice) {
            if (songId.isBlank()) return@withContext null
            val svRes = resolveStreetVoice(songId) ?: return@withContext null
            val proxiedUrl = if (!svRes.url.isNullOrBlank()) {
                LocalAudioProxy.buildProxyUrl(svRes.url)
            } else svRes.url
            return@withContext svRes.copy(url = proxiedUrl)
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
                Log.d(TAG, "未在各平台搜到最佳匹配歌曲: $title -$artist")
                return@withContext null
            }

            // 2. 优先使用 ID 精准直取接口，其次按实测延迟排序
            for (cand in candidates) {
                val matchedEndpoints = endpoints
                    .filter { it.platform == cand.platform }
                    .sortedWith(compareBy({ it.matchMode != "id" }, { it.sampleLatencyMs }))

                for (ep in matchedEndpoints) {
                    val directUrl = extractStreamUrl(ep, cand)
                    if (!directUrl.isNullOrBlank() && verifyAudioStream(directUrl)) {
                        val cost = System.currentTimeMillis() - t0
                        val proxiedUrl = LocalAudioProxy.buildProxyUrl(directUrl)
                        Log.i(TAG, "✓ 端侧直链解析成功 (${cost}ms) [${cand.platform}]: $directUrl -> $proxiedUrl")
                        return@withContext ResolveResult(
                            success = true,
                            url = proxiedUrl,
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
     * 街声双域名并发竞速（Fastest-Wins）：
     * 同时请求 www.streetvoice.cn 与 streetvoice.com，任一节点率先返回有效 .m3u8 即刻胜出并取消另一路
     */
    private suspend fun resolveStreetVoice(songId: String): ResolveResult? = coroutineScope {
        val domains = listOf("www.streetvoice.cn", "streetvoice.com")
        val resultChannel = Channel<ResolveResult?>(domains.size)

        val jobs = domains.map { domain ->
            launch(Dispatchers.IO) {
                val res = fetchStreetVoiceFromDomain(domain, songId)
                resultChannel.send(res)
            }
        }

        var winner: ResolveResult? = null
        repeat(domains.size) {
            val candidate = resultChannel.receive()
            if (candidate != null && winner == null) {
                winner = candidate
                jobs.forEach { it.cancel() }
                return@coroutineScope winner
            }
        }
        winner
    }

    private suspend fun fetchStreetVoiceFromDomain(domain: String, songId: String): ResolveResult? = coroutineScope {
        val t0 = System.currentTimeMillis()
        try {
            val hlsUrl = "https://$domain/api/v5/song/$songId/hls/file/"

            val hlsDeferred = async(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(hlsUrl)
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .header("User-Agent", UA_DESKTOP)
                    .header("Referer", "[https://streetvoice.com/](https://streetvoice.com/)")
                    .header("Origin", "[https://streetvoice.com](https://streetvoice.com)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        JSONObject(body).optString("file")
                    } else null
                }
            }

            val detailDeferred = async(Dispatchers.IO) {
                fetchLyricsArrayFromDomain(domain, songId)
            }

            val file = hlsDeferred.await()
            val lyricsArray = detailDeferred.await()

            if (!file.isNullOrBlank() && file.contains(".m3u8")) {
                if (lyricsArray != null && lyricsArray.length() > 0) {
                    lyricCache[songId] = System.currentTimeMillis() to lyricsArray.toString()
                }
                val cost = System.currentTimeMillis() - t0
                val lyricCount = lyricsArray?.length() ?: 0
                val isDomestic = domain.contains(".cn")
                val qualityTag = if (isDomestic) "96k" else "192k"
                val sourceName = if (isDomestic) "街声官方 (国内节点·$qualityTag)" else "街声官方 (国际节点·$qualityTag)"
                Log.i(TAG, "✓ 街声并发竞速胜出 ($sourceName, ${cost}ms, 歌词: ${lyricCount}行)")
                return@coroutineScope ResolveResult(
                    success = true,
                    url = file,
                    sourceName = sourceName,
                    costMs = cost,
                    lyrics = lyricsArray
                )
            }
        } catch (_: Exception) {
        }
        null
    }

    private fun fetchLyricsArrayFromDomain(domain: String, songId: String): JSONArray? {
        return try {
            val url = "https://$domain/api/v5/song/$songId/"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", "[https://streetvoice.com/](https://streetvoice.com/)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val rawLyrics = json.optString("lyrics")
                val isLrc = json.optBoolean("lyrics_is_lrc", false)
                parseStreetVoiceLyrics(rawLyrics, isLrc)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 单独获取街声歌词（优先 0ms 命中内存缓存，未命中则双域名并发竞速）
     */
    suspend fun fetchStreetVoiceLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        val cached = lyricCache[songId]
        if (cached != null && (System.currentTimeMillis() - cached.first) < LYRIC_CACHE_TTL_MS) {
            Log.d(TAG, "✓ 街声歌词命中端侧缓存: $songId")
            return@withContext cached.second
        }

        val domains = listOf("www.streetvoice.cn", "streetvoice.com")
        val resultChannel = Channel<JSONArray?>(domains.size)

        val jobs = domains.map { domain ->
            launch(Dispatchers.IO) {
                val arr = fetchLyricsArrayFromDomain(domain, songId)
                resultChannel.send(arr)
            }
        }

        repeat(domains.size) {
            val candidate = resultChannel.receive()
            if (candidate != null) {
                jobs.forEach { it.cancel() }
                val jsonStr = candidate.toString()
                if (candidate.length() > 0) {
                    lyricCache[songId] = System.currentTimeMillis() to jsonStr
                }
                return@withContext jsonStr
            }
        }
        null
    }

    /**
     * 支持单行多时间戳展开（如 [00:15.20][01:32.10]副歌）与纯文本无时间轴歌词
     */
    private fun parseStreetVoiceLyrics(rawLyrics: String, isLrc: Boolean): JSONArray {
        val arr = JSONArray()
        if (rawLyrics.isBlank()) return arr

        val lrcTagRegex = Regex("""\[(\d{1,3}):(\d{2}(?:\.\d+)?)\]""")
        val metaTagRegex = Regex("""^\[(ti\vert{}ar\vert{}al\vert{}by\vert{}offset):.*\]$""", RegexOption.IGNORE_CASE)
        val hasLrcTag = lrcTagRegex.containsMatchIn(rawLyrics)

        if (isLrc || hasLrcTag) {
            val timedEntries = mutableListOf<Pair<Double, String>>()
            for (rawLine in rawLyrics.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank() || metaTagRegex.matches(trimmed)) continue

                val matches = lrcTagRegex.findAll(trimmed).toList()
                if (matches.isNotEmpty()) {
                    val cleanText = trimmed.replace(lrcTagRegex, "").trim()
                    if (cleanText.isNotBlank()) {
                        for (m in matches) {
                            val min = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            val sec = m.groupValues[2].toDoubleOrNull() ?: 0.0
                            timedEntries.add((min * 60.0 + sec) to cleanText)
                        }
                    }
                }
            }

            if (timedEntries.isNotEmpty()) {
                timedEntries.sortBy { it.first }
                for ((timeSec, text) in timedEntries) {
                    arr.put(JSONObject().apply {
                        put("time", timeSec)
                        put("text", text)
                    })
                }
                return arr
            }
        }

        // 纯文本歌词处理（逐行输出 untimed: true）
        for (line in rawLyrics.lines()) {
            val clean = line.replace(lrcTagRegex, "").trim()
            if (clean.isNotBlank() && !metaTagRegex.matches(clean)) {
                arr.put(JSONObject().apply {
                    put("time", 0)
                    put("text", clean)
                    put("untimed", true)
                })
            }
        }
        return arr
    }

    /**
     * 为前端提供手机端本机实测的音源健康度与延迟清单
     */
    suspend fun getSourcesRanking(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedRankingJson != null && (now - lastRankingTime) < 30_000L) {
            return@withContext cachedRankingJson!!
        }

        val list = mutableListOf<JSONObject>()

        val svLatency = measureStreetVoiceLatency()
        val roundedSvLat = kotlin.math.round(svLatency).toLong()
        list.add(JSONObject().apply {
            put("id", "sv_official")
            put("name", "街声官方双线竞速")
            put("platform", "streetvoice")
            put("status", if (svLatency < 5000) "ok" else "failed")
            put("latency_ms", roundedSvLat)
            put("success_count", if (svLatency < 5000) 100 else 0)
        })

        val endpointsToTest = endpoints.take(12)
        coroutineScope {
            val deferreds = endpointsToTest.map { ep ->
                async {
                    val lat = measureEndpointLatency(ep)
                    val roundedLat = kotlin.math.round(lat).toLong()
                    JSONObject().apply {
                        put("id", ep.id)
                        put("name", ep.name)
                        put("platform", ep.platform)
                        put("status", if (lat < 5000) "ok" else "failed")
                        put("latency_ms", roundedLat)
                        put("success_count", if (lat < 5000) 100 else 0)
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

    private suspend fun measureStreetVoiceLatency(): Double = coroutineScope {
        val domains = listOf("www.streetvoice.cn", "streetvoice.com")
        val ch = Channel<Double?>(domains.size)
        val jobs = domains.map { domain ->
            launch(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    val req = Request.Builder()
                        .url("https://$domain/api/v5/song/123688/hls/file/")
                        .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                        .header("User-Agent", UA_DESKTOP)
                        .header("Referer", "[https://streetvoice.com/](https://streetvoice.com/)")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            ch.send((System.currentTimeMillis() - t0).toDouble())
                            return@launch
                        }
                    }
                } catch (_: Exception) {
                }
                ch.send(null)
            }
        }
        repeat(domains.size) {
            val lat = ch.receive()
            if (lat != null) {
                jobs.forEach { it.cancel() }
                return@coroutineScope lat
            }
        }
        9999.0
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
        } catch (_: Exception) {
        }
        return ep.sampleLatencyMs.toDouble()
    }

    private suspend fun searchCandidates(title: String, artist: String, duration: Int): List<CandidateSong> = coroutineScope {
        val cleanTitle = cleanSearchKeyword(title)
        val query = "$cleanTitle$artist".trim()

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
            val url = "[https://search.kuwo.cn/r.s?client=kt&all=$encodedQuery&pn=0&rn=5&uid=794762528&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newsearch=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1](https://search.kuwo.cn/r.s?client=kt&all=$encodedQuery&pn=0&rn=5&uid=794762528&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newsearch=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1)"
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
        } catch (_: Exception) {
        }
        return null
    }

    private fun searchKugou(query: String, targetTitle: String, targetArtist: String, targetDuration: Int): CandidateSong? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "[https://songsearch.kugou.com/song_search_v2?keyword=$encodedQuery&page=1&pagesize=5&clientver=&platform=WebFilter](https://songsearch.kugou.com/song_search_v2?keyword=$encodedQuery&page=1&pagesize=5&clientver=&platform=WebFilter)"
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
        } catch (_: Exception) {
        }
        return null
    }

    private fun searchNetease(query: String, targetTitle: String, targetArtist: String, targetDuration: Int): CandidateSong? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "[https://music.163.com/api/search/get/web?s=$encodedQuery&type=1&offset=0&total=true&limit=5](https://music.163.com/api/search/get/web?s=$encodedQuery&type=1&offset=0&total=true&limit=5)"
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
        } catch (_: Exception) {
        }
        return null
    }

    private fun isSongMatch(
        name: String,
        singer: String,
        dur: Int,
        targetTitle: String,
        targetArtist: String,
        targetDuration: Int
    ): Boolean {
        val n1 = name.lowercase().replace(" ", "")
        val t1 = targetTitle.lowercase().replace(" ", "")
        if (!n1.contains(t1) && !t1.contains(n1)) return false

        if (targetArtist.isNotBlank() && singer.isNotBlank()) {
            val s1 = singer.lowercase().replace(" ", "")
            val a1 = targetArtist.lowercase().replace(" ", "")
            val artistMatched = s1.contains(a1) || a1.contains(s1) ||
                a1.split("&", ",", "、", "/", "feat.", "ft.").any { part ->
                    val p = part.trim()
                    p.isNotEmpty() && s1.contains(p)
                } ||
                s1.split("&", ",", "、", "/", "feat.", "ft.").any { part ->
                    val p = part.trim()
                    p.isNotEmpty() && a1.contains(p)
                }
            if (!artistMatched) return false
        }

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
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
        }
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
                    val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                    if (contentType.contains("text/html") || contentType.contains("application/json")) {
                        return false
                    }

                    val contentRange = resp.header("Content-Range")
                    val totalLen = if (!contentRange.isNullOrBlank()) {
                        val match = Regex("""bytes\s+\d+-\d+/(\d+)""").find(contentRange)
                        match?.groupValues?.get(1)?.toLongOrNull()
                    } else null

                    val len = totalLen ?: resp.header("Content-Length")?.toLongOrNull() ?: 0L

                    // 拦截 80KB/180KB 空音频及 < 1.2MB 的 30 秒 VIP 试听残卷（排除 .m3u8 索引）
                    if (len == 81920L || len == 184320L) return false
                    if (!url.contains(".m3u8", ignoreCase = true) && len in 1 until MIN_FULL_AUDIO_BYTES) {
                        return false
                    }

                    return true
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun cleanSearchKeyword(title: String): String {
        val cleaned = title
            .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】|（.*?）"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifBlank { title.trim() }
    }
}