package com.amusic.app.resolver

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 手机端侧 127.0.0.1 极简轻量音频流转发代理
 * 1. 监听 127.0.0.1 随机端口
 * 2. 接收前端 <audio> 的 /stream?url=... 请求
 * 3. 使用 OkHttp 转发真实音频流，不携带网页 Referer，使用通用 UA
 * 4. 完整透传 Range 请求头以及 Content-Type、Content-Length、Content-Range 响应头 (支持 206 Partial Content)
 * 5. 支持 m3u8 相对切片路径自动重写为绝对路径
 * 6. 解决鸿蒙/Android WebView 防盗链 403、Mixed Content 与媒体解码限制
 */
object LocalAudioProxy {

    private const val TAG = "LocalAudioProxy"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    private val threadPool = Executors.newCachedThreadPool()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 0 表示流式传输无读取超时
        .followRedirects(true)
        .build()

    val port: Int
        get() {
            ensureStarted()
            return serverSocket?.localPort ?: -1
        }

    @Synchronized
    fun start() {
        if (isRunning && serverSocket != null && !serverSocket!!.isClosed) return

        try {
            // 绑定 127.0.0.1 随机空闲端口
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            isRunning = true
            val assignedPort = serverSocket!!.localPort
            Log.i(TAG, "✓ 端侧本地音频转发代理已启动: http://127.0.0.1:$assignedPort")

            threadPool.execute {
                acceptLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动端侧本地音频转发代理失败", e)
        }
    }

    private fun ensureStarted() {
        if (!isRunning || serverSocket == null || serverSocket!!.isClosed) {
            start()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    /**
     * 将真实音频链接包装为本地代理链接
     */
    fun buildProxyUrl(realUrl: String): String {
        if (realUrl.isBlank()) return realUrl
        if (realUrl.startsWith("http://127.0.0.1:")) return realUrl

        ensureStarted()
        val currentPort = port
        if (currentPort <= 0) return realUrl

        return try {
            val encoded = URLEncoder.encode(realUrl, "UTF-8")
            "http://127.0.0.1:$currentPort/stream?url=$encoded"
        } catch (e: Exception) {
            realUrl
        }
    }

    private fun acceptLoop() {
        while (isRunning) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                threadPool.execute {
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val rawUri = parts[1]

                // 处理跨域预检请求 OPTIONS
                if (method == "OPTIONS") {
                    val preflight = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Access-Control-Max-Age: 86400\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(preflight.toByteArray(Charsets.US_ASCII))
                    output.flush()
                    return
                }

                if (method != "GET" && method != "HEAD") {
                    sendSimpleResponse(output, 405, "Method Not Allowed")
                    return
                }

                // 读取客户端请求头，精准提取 Range
                var rangeHeader: String? = null
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val lower = line.lowercase()
                    if (lower.startsWith("range:")) {
                        rangeHeader = line.substring(6).trim()
                    }
                    line = reader.readLine()
                }

                // 解析目标真实 URL
                val targetUrl = extractTargetUrl(rawUri)
                if (targetUrl.isNullOrBlank() || !targetUrl.startsWith("http")) {
                    sendSimpleResponse(output, 400, "Bad Request: invalid url")
                    return
                }

                forwardRequest(targetUrl, method, rangeHeader, output)
            }
        } catch (_: SocketException) {
            // 客户端主动断开连接（如用户切歌、拖动进度条取消请求），静默忽略
        } catch (e: Exception) {
            Log.w(TAG, "代理转发异常: ${e.message}")
        }
    }

    private fun extractTargetUrl(rawUri: String): String? {
        try {
            val uri = Uri.parse(rawUri)
            val param = uri.getQueryParameter("url")
            if (!param.isNullOrBlank()) return param

            val idx = rawUri.indexOf("url=")
            if (idx != -1) {
                val encodedPart = rawUri.substring(idx + 4).substringBefore("&")
                return URLDecoder.decode(encodedPart, "UTF-8")
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析代理目标 URL 失败: $rawUri", e)
        }
        return null
    }

    private fun forwardRequest(
        targetUrl: String,
        method: String,
        rangeHeader: String?,
        output: java.io.OutputStream
    ) {
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", DEFAULT_UA)

        // 绝不携带网页 Referer，彻底阻断第三方音乐 CDN 防盗链拦截
        // 支持完整透传 Range 请求头
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        if (method == "HEAD") {
            reqBuilder.head()
        }

        try {
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val statusText = if (resp.message.isNotBlank()) resp.message else {
                    if (code == 206) "Partial Content" else if (code == 200) "OK" else "Status $code"
                }

                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 $code $statusText\r\n")

                val contentType = resp.header("Content-Type") ?: "audio/mpeg"
                headerBuilder.append("Content-Type: $contentType\r\n")

                resp.header("Content-Length")?.let {
                    headerBuilder.append("Content-Length: $it\r\n")
                }
                resp.header("Content-Range")?.let {
                    headerBuilder.append("Content-Range: $it\r\n")
                }
                headerBuilder.append("Accept-Ranges: bytes\r\n")
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                headerBuilder.append("Access-Control-Allow-Headers: *\r\n")
                headerBuilder.append("Connection: close\r\n")
                headerBuilder.append("\r\n")

                output.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                output.flush()

                if (method == "HEAD") return

                val body = resp.body ?: return

                // 若为 m3u8 索引，将相对切片路径重写为绝对路径，防止 HLS 播放器解析失败
                if (targetUrl.contains(".m3u8") || contentType.contains("mpegurl")) {
                    val text = body.string()
                    val baseUri = URI(targetUrl)
                    val rewritten = text.lineSequence().map { l ->
                        val trimmed = l.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            try {
                                baseUri.resolve(trimmed).toString()
                            } catch (_: Exception) {
                                trimmed
                            }
                        } else {
                            l
                        }
                    }.joinToString("\n")
                    output.write(rewritten.toByteArray(Charsets.UTF_8))
                    output.flush()
                } else {
                    // 二进制音频流直通传输 (MP3 / FLAC / M4A / AAC 等)
                    val inputStream = body.byteStream()
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
        } catch (_: SocketException) {
            // 连接重置或管道破裂，正常断开
        } catch (e: Exception) {
            Log.w(TAG, "OkHttp 转发音频流失败: ${e.message}")
            sendSimpleResponse(output, 502, "Bad Gateway")
        }
    }

    private fun sendSimpleResponse(output: java.io.OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }
}
