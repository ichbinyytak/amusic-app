package com.amusic.app.resolver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class SourceEndpoint(
    val id: String,
    val name: String,
    val platform: String,
    val matchMode: String, // "id" 或 "search"
    val method: String,
    val urlTemplate: String,
    val bodyTemplate: String?,
    val headers: Map<String, String>,
    val responseExtractor: String,
    val sampleLatencyMs: Int
)

/**
 * 负责从 yy-lx-hub GitHub Release 同步最新音源规则并持久化到本地
 */
class SourceSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SourceSyncManager"
        private const val SYNC_PREFS = "amusic_source_sync"
        private const val KEY_LAST_SYNC_TS = "last_sync_timestamp"
        private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24小时同步一次

        private const val PRIMARY_URL = "https://github.com/ichbinyytak/yy-lx-hub/releases/download/amusic-sync/extracted_apis.enc"
        private const val MIRROR_URL = "https://ghfast.top/https://github.com/ichbinyytak/yy-lx-hub/releases/download/amusic-sync/extracted_apis.enc"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(context.filesDir, "extracted_apis.json")

    /**
     * 载入当前可用的音源端点列表（内存优先，次级本地缓存，兜底 assets 静态包）
     */
    fun loadEndpoints(): List<SourceEndpoint> {
        val jsonStr = try {
            if (cacheFile.exists()) {
                cacheFile.readText(Charsets.UTF_8)
            } else {
                // 首次冷启动：读取内置 assets/extracted_apis.enc 密文解密
                val encStr = context.assets.open("extracted_apis.enc").bufferedReader().use { it.readText() }
                val plain = ApiCrypto.decrypt(encStr)
                cacheFile.writeText(plain)
                plain
            }
        } catch (e: Exception) {
            Log.e(TAG, "载入音源缓存失败", e)
            return emptyList()
        }

        return parseEndpoints(jsonStr)
    }

    /**
     * 检查并尝试后台异步同步最新音源
     */
    suspend fun syncIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TS, 0L)
        val now = System.currentTimeMillis()

        if (!force && (now - lastSync) < SYNC_INTERVAL_MS && cacheFile.exists()) {
            Log.d(TAG, "音源仍在新近有效期内，跳过网络同步")
            return@withContext true
        }

        Log.i(TAG, "开始从 yy-lx-hub 同步每日音源更新...")
        val encContent = fetchEncryptedPackage() ?: return@withContext false

        try {
            val plain = ApiCrypto.decrypt(encContent)
            val endpoints = parseEndpoints(plain)
            if (endpoints.isNotEmpty()) {
                cacheFile.writeText(plain)
                prefs.edit().putLong(KEY_LAST_SYNC_TS, now).apply()
                Log.i(TAG, "音源包解密同步成功，已更新 ${endpoints.size} 个端点")
                return@withContext true
            } else {
                Log.w(TAG, "解密后的音源包端点为空，保留原有文件")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析远端密文失败: ${e.message}", e)
            return@withContext false
        }
    }

    private fun fetchEncryptedPackage(): String? {
        val urls = listOf(PRIMARY_URL, MIRROR_URL)
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AmusicAndroid/1.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            return body
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "尝试拉取 $url 失败: ${e.message}")
            }
        }
        return null
    }

    private fun parseEndpoints(jsonStr: String): List<SourceEndpoint> {
        val res = mutableListOf<SourceEndpoint>()
        try {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("endpoints") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val headersMap = mutableMapOf<String, String>()
                val headersObj = obj.optJSONObject("headers")
                headersObj?.keys()?.forEach { k ->
                    headersMap[k] = headersObj.getString(k)
                }

                res.add(
                    SourceEndpoint(
                        id = obj.optString("id", "src_$i"),
                        name = obj.optString("name", "自定义音源"),
                        platform = obj.optString("platform", "kw"),
                        matchMode = obj.optString("matchMode", "id"),
                        method = obj.optString("method", "GET").uppercase(),
                        urlTemplate = obj.getString("urlTemplate"),
                        bodyTemplate = obj.optString("bodyTemplate").takeIf { it.isNotBlank() },
                        headers = headersMap,
                        responseExtractor = obj.optString("responseExtractor", "url"),
                        sampleLatencyMs = obj.optInt("sampleLatencyMs", 999)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseEndpoints error", e)
        }
        return res
    }
}
