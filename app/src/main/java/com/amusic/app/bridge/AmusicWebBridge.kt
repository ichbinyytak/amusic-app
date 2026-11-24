package com.amusic.app.bridge

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 容器桥接管理
 * 负责 WebView 配置、JS 注入、状态同步与外设指令派发，以及端侧寻链、测速与歌词拦截
 */
class AmusicWebBridge(
    private val webView: WebView,
    private val callbacks: BridgeCallbacks
) {

    interface BridgeCallbacks {
        fun onProgressChanged(progress: Int)
        fun onPageStarted()
        fun onPageFinished()
        fun onReceivedError(description: String)
        fun onTrackChanged(title: String, artist: String, album: String, artworkUrl: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onOpenSettings()
        fun onResolveSong(reqId: String, payloadJson: String)
        fun onGetSourcesRanking(reqId: String)
        fun onGetStreetVoiceLyrics(reqId: String, songId: String)
    }

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.textZoom = 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.allowFileAccess = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        val defaultUserAgent = settings.userAgentString
        settings.userAgentString = "$defaultUserAgent AmusicAndroidApp/1.0"

        webView.addJavascriptInterface(
            WebAppInterface(
                onTrackChangedCallback = callbacks::onTrackChanged,
                onPlaybackStateChangedCallback = callbacks::onPlaybackStateChanged,
                onOpenSettingsCallback = callbacks::onOpenSettings,
                onResolveSongCallback = callbacks::onResolveSong,
                onGetSourcesRankingCallback = callbacks::onGetSourcesRanking,
                onGetStreetVoiceLyricsCallback = callbacks::onGetStreetVoiceLyrics
            ),
            "AmusicNative"
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChanged(newProgress)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                if (url.contains("hls.min.js") || url.contains("hls.js")) {
                    try {
                        val input = webView.context.assets.open("hls.min.js")
                        val response = WebResourceResponse("application/javascript", "UTF-8", input)
                        response.responseHeaders = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "public, max-age=31536000"
                        )
                        Log.i("AmusicNative", "✓ 端侧 0ms 本地拦截命中 hls.min.js")
                        return response
                    } catch (e: Exception) {
                        Log.w("AmusicNative", "从本地 assets 加载 hls.min.js 失败", e)
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                callbacks.onPageStarted()
                injectNativeBridge(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                callbacks.onPageFinished()
                injectNativeBridge(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    callbacks.onReceivedError(error?.description?.toString() ?: "网络连接异常")
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                val url = error?.url ?: ""
                if (isLocalOrPrivateHost(url)) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                callbacks.onReceivedError("页面渲染进程已重置")
                return true
            }
        }
    }

    private fun isLocalOrPrivateHost(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))
    }

    private fun injectNativeBridge(view: WebView?) {
        val bridgeJs = """
            (function() {
                if (window.__amusic_bridge_injected) return;
                window.__amusic_bridge_injected = true;
                window.__amusic_sv_lyrics_cache = window.__amusic_sv_lyrics_cache || {};

                // 0. 监听 <audio> 的 error 事件，打印具体错误码与地址
                function reportAudioError(audio, evt) {
                    try {
                        var err = audio ? audio.error : null;
                        var errCode = err ? err.code : 'unknown';
                        var errMsg = err ? (err.message || '') : '';
                        var src = (audio && (audio.currentSrc || audio.src)) || '';
                        var codeMap = {
                            1: 'MEDIA_ERR_ABORTED',
                            2: 'MEDIA_ERR_NETWORK',
                            3: 'MEDIA_ERR_DECODE',
                            4: 'MEDIA_ERR_SRC_NOT_SUPPORTED'
                        };
                        var codeLabel = codeMap[errCode] ? (' (' + codeMap[errCode] + ')') : '';
                        var logMsg = "[AudioError] 播放失败: code=" + errCode + codeLabel + ", message=" + errMsg + ", src=" + src;
                        if (window.AmusicNative && typeof window.AmusicNative.log === 'function') {
                            window.AmusicNative.log(logMsg);
                        } else {
                            console.error(logMsg);
                        }
                    } catch(ex) {
                        console.error("[AmusicNative] reportAudioError error:", ex);
                    }
                }

                document.addEventListener('error', function(e) {
                    var target = e.target;
                    if (target) {
                        if (target.tagName && target.tagName.toLowerCase() === 'audio') {
                            reportAudioError(target, e);
                        } else if (target.tagName && target.tagName.toLowerCase() === 'source' && target.parentElement && target.parentElement.tagName.toLowerCase() === 'audio') {
                            reportAudioError(target.parentElement, e);
                        }
                    }
                }, true);

                // 1. 提供给原生调用的全局控制派发器 (严格幂等控制)
                window.__amusic_dispatch = function(action) {
                    try {
                        if (window.player) {
                            if (action === 'togglePlay') {
                                window.player.togglePlay();
                            } else if (action === 'play') {
                                if (window.player.audio && window.player.audio.paused) {
                                    window.player.togglePlay();
                                }
                            } else if (action === 'pause') {
                                if (window.player.audio && !window.player.audio.paused) {
                                    window.player.togglePlay();
                                }
                            } else if (action === 'next') {
                                window.player.next();
                            } else if (action === 'prev') {
                                window.player.prev();
                            }
                            return;
                        }

                        if (action === 'togglePlay') {
                            var playBtn = document.querySelector('#btnPlayToggle') || document.querySelector('.btn-play');
                            if (playBtn) playBtn.click();
                        } else if (action === 'next') {
                            var nextBtn = document.querySelector('#btnNext') || document.querySelector('.btn-next');
                            if (nextBtn) nextBtn.click();
                        } else if (action === 'prev') {
                            var prevBtn = document.querySelector('#btnPrev') || document.querySelector('.btn-prev');
                            if (prevBtn) prevBtn.click();
                        }
                    } catch(e) {
                        console.error("[AmusicNative] dispatch error:", e);
                    }
                };

                // 2. 获取真实歌曲对象
                function getCurrentSong() {
                    if (!window.player) return null;
                    if (window.player.playlist && window.player.currentIndex >= 0 && window.player.currentIndex < window.player.playlist.length) {
                        return window.player.playlist[window.player.currentIndex];
                    }
                    return null;
                }

                var lastSentKey = "";
                var lastSentState = null;

                function syncState() {
                    try {
                        var s = getCurrentSong();
                        if (s) {
                            var title = s.title || s.name || "未知曲目";
                            var artist = s.artist || "Apple Music";
                            var album = s.album || "Amusic";
                            var artwork = s.artwork || s.cover || s.picUrl || "";
                            var songKey = title + "|" + artist + "|" + album + "|" + artwork;
                            if (songKey !== lastSentKey && window.AmusicNative) {
                                lastSentKey = songKey;
                                window.AmusicNative.onTrackChanged(title, artist, album, artwork);
                            }
                        }

                        var isPlaying = (window.player && window.player.isPlaying) || (window.player && window.player.audio && !window.player.audio.paused) || false;
                        if (isPlaying !== lastSentState && window.AmusicNative) {
                            lastSentState = isPlaying;
                            window.AmusicNative.onPlaybackStateChanged(isPlaying);
                        }

                        if (window.player && window.player.audio && !window.player.audio.__amusic_error_hooked) {
                            window.player.audio.__amusic_error_hooked = true;
                            window.player.audio.addEventListener('error', function(e) {
                                reportAudioError(this, e);
                            });
                        }
                    } catch(e) {
                        console.error("[AmusicNative] sync error:", e);
                    }
                }

                document.addEventListener('play', function() { syncState(); }, true);
                document.addEventListener('pause', function() { syncState(); }, true);

                if (window.player && typeof window.player.on === 'function') {
                    window.player.on('stateChange', function() { syncState(); });
                    window.player.on('playlistChange', function() { syncState(); });
                }

                syncState();
                setInterval(syncState, 2500);

                // 3. 关键拦截：劫持 fetch 请求
                if (!window.__amusic_fetch_hooked) {
                    window.__amusic_fetch_hooked = true;
                    var _origFetch = window.fetch;
                    window.fetch = async function(input, init) {
                        var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                        var signal = init && init.signal;
                        if (signal && signal.aborted) {
                            var abortErr = new Error("The user aborted a request.");
                            abortErr.name = "AbortError";
                            throw abortErr;
                        }

                        // A. 拦截 /api/resolve: 端侧极速直连与街声双线竞速
                        if (url.indexOf('/api/resolve') !== -1 && init && init.method === 'POST') {
                            try {
                                if (window.AmusicNative && typeof window.AmusicNative.resolveSong === 'function') {
                                    var payloadStr = typeof init.body === 'string' ? init.body : JSON.stringify(init.body || {});
                                    var parsedReq = {};
                                    try { parsedReq = JSON.parse(payloadStr); } catch(_) {}
                                    var reqSongId = String(parsedReq.id || parsedReq.songId || parsedReq.song_id || parsedReq.mid || '');

                                    var localRes = await new Promise(function(resolve, reject) {
                                        var reqId = 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                                        window.__amusic_resolve_callbacks = window.__amusic_resolve_callbacks || {};
                                        window.__amusic_resolve_callbacks[reqId] = resolve;

                                        if (signal) {
                                            signal.addEventListener('abort', function() {
                                                if (window.__amusic_resolve_callbacks[reqId]) {
                                                    delete window.__amusic_resolve_callbacks[reqId];
                                                }
                                                var err = new Error("The user aborted a request.");
                                                err.name = "AbortError";
                                                reject(err);
                                            }, { once: true });
                                        }

                                        setTimeout(function() {
                                            if (window.__amusic_resolve_callbacks[reqId]) {
                                                delete window.__amusic_resolve_callbacks[reqId];
                                                resolve(null);
                                            }
                                        }, 5500);
                                        window.AmusicNative.resolveSong(reqId, payloadStr);
                                    });

                                    if (localRes && localRes.success && localRes.url) {
                                        if (reqSongId && Array.isArray(localRes.lyrics) && localRes.lyrics.length > 0) {
                                            window.__amusic_sv_lyrics_cache[reqSongId] = localRes.lyrics;
                                        }
                                        return new Response(JSON.stringify(localRes), {
                                            status: 200,
                                            headers: { 'Content-Type': 'application/json' }
                                        });
                                    }
                                }
                            } catch(e) {
                                if (e && e.name === 'AbortError') throw e;
                                console.warn("[AmusicNative] 端侧寻链异常，回退云端:", e);
                            }
                        }

                        // B. 拦截 /api/lyric: 街声歌词优先命中 JS 缓存，其次双线并发拉取
                        if (url.indexOf('/api/lyric') !== -1 && (url.indexOf('platform=streetvoice') !== -1 || url.indexOf('platform=sv') !== -1)) {
                            try {
                                var queryStr = url.split('?')[1] || '';
                                var match = queryStr.match(/(?:^|&)(?:id|songId|song_id)=([^&]+)/);
                                var sid = match ? decodeURIComponent(match[1]) : null;
                                if (sid) {
                                    if (window.__amusic_sv_lyrics_cache[sid]) {
                                        return new Response(JSON.stringify(window.__amusic_sv_lyrics_cache[sid]), {
                                            status: 200,
                                            headers: { 'Content-Type': 'application/json' }
                                        });
                                    }
                                    if (window.AmusicNative && typeof window.AmusicNative.getStreetVoiceLyrics === 'function') {
                                        var lrcRes = await new Promise(function(resolve) {
                                            var reqId = 'lrc_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                                            window.__amusic_lrc_callbacks = window.__amusic_lrc_callbacks || {};
                                            window.__amusic_lrc_callbacks[reqId] = resolve;
                                            setTimeout(function() {
                                                if (window.__amusic_lrc_callbacks[reqId]) {
                                                    delete window.__amusic_lrc_callbacks[reqId];
                                                    resolve(null);
                                                }
                                            }, 4500);
                                            window.AmusicNative.getStreetVoiceLyrics(reqId, sid);
                                        });
                                        if (lrcRes && Array.isArray(lrcRes)) {
                                            if (lrcRes.length > 0) {
                                                window.__amusic_sv_lyrics_cache[sid] = lrcRes;
                                            }
                                            return new Response(JSON.stringify(lrcRes), {
                                                status: 200,
                                                headers: { 'Content-Type': 'application/json' }
                                            });
                                        }
                                    }
                                }
                            } catch(e) {
                                console.warn("[AmusicNative] 端侧街声歌词异常，回退云端:", e);
                            }
                        }

                        // C. 拦截 /api/sources: 替换为手机端本机的实测延迟清单
                        if (url.indexOf('/api/sources') !== -1 && (!init || !init.method || init.method === 'GET')) {
                            try {
                                if (window.AmusicNative && typeof window.AmusicNative.getSourcesRanking === 'function') {
                                    var localRanking = await new Promise(function(resolve) {
                                        var reqId = 'rank_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                                        window.__amusic_rank_callbacks = window.__amusic_rank_callbacks || {};
                                        window.__amusic_rank_callbacks[reqId] = resolve;
                                        setTimeout(function() {
                                            if (window.__amusic_rank_callbacks[reqId]) {
                                                delete window.__amusic_rank_callbacks[reqId];
                                                resolve(null);
                                            }
                                        }, 4500);
                                        window.AmusicNative.getSourcesRanking(reqId);
                                    });

                                    if (localRanking && localRanking.sources) {
                                        return new Response(JSON.stringify(localRanking), {
                                            status: 200,
                                            headers: { 'Content-Type': 'application/json' }
                                        });
                                    }
                                }
                            } catch(e) {
                                console.warn("[AmusicNative] 获取端侧音源测速异常，回退云端:", e);
                            }
                        }

                        return _origFetch.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(bridgeJs, null)
    }

    fun sendResolveResult(reqId: String, resultJson: String?) {
        val payload = resultJson ?: "null"
        val js = "(function(){ if(window.__amusic_resolve_callbacks && window.__amusic_resolve_callbacks['$reqId']) { window.__amusic_resolve_callbacks['$reqId']($payload); delete window.__amusic_resolve_callbacks['$reqId']; } })();"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun sendRankingResult(reqId: String, resultJson: String?) {
        val payload = resultJson ?: "null"
        val js = "(function(){ if(window.__amusic_rank_callbacks && window.__amusic_rank_callbacks['$reqId']) { window.__amusic_rank_callbacks['$reqId']($payload); delete window.__amusic_rank_callbacks['$reqId']; } })();"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun sendLyricResult(reqId: String, resultJson: String?) {
        val payload = resultJson ?: "null"
        val js = "(function(){ if(window.__amusic_lrc_callbacks && window.__amusic_lrc_callbacks['$reqId']) { window.__amusic_lrc_callbacks['$reqId']($payload); delete window.__amusic_lrc_callbacks['$reqId']; } })();"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun dispatchAction(action: String) {
        webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('$action');", null)
    }

    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    fun reload() {
        webView.reload()
    }

    fun canGoBack(): Boolean = webView.canGoBack()

    fun goBack() {
        webView.goBack()
    }
}