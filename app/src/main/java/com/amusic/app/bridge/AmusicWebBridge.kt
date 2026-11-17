package com.amusic.app.bridge

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
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
 * 负责 WebView 配置、JS 注入、单一事实来源状态同步与外设指令派发，以及端侧寻链、测速与歌词拦截
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

                console.log("[AmusicNative] Injected into page.");

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

                        // 备用兜底：触发 DOM 播控按钮
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

                // 2. 获取真实歌曲对象：从底层 player.playlist[player.currentIndex] 获取
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
                    } catch(e) {
                        console.error("[AmusicNative] sync error:", e);
                    }
                }

                // 3. 监听 audio 播放与 player.js 事件
                document.addEventListener('play', function() { syncState(); }, true);
                document.addEventListener('pause', function() { syncState(); }, true);

                if (window.player && typeof window.player.on === 'function') {
                    window.player.on('stateChange', function() { syncState(); });
                    window.player.on('playlistChange', function() { syncState(); });
                }

                syncState();
                setInterval(syncState, 3000);

                // 4. 关键拦截：劫持 fetch 请求 (端侧极速直连、街声歌词与本机实时测速)
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

                        // A. 拦截 /api/resolve: 优先走端侧极速直连与街声直通 (含带回同源歌词)
                        if (url.indexOf('/api/resolve') !== -1 && init && init.method === 'POST') {
                            try {
                                if (window.AmusicNative && typeof window.AmusicNative.resolveSong === 'function') {
                                    var payloadStr = typeof init.body === 'string' ? init.body : JSON.stringify(init.body || {});
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
                                        }, 3500);
                                        window.AmusicNative.resolveSong(reqId, payloadStr);
                                    });

                                    if (localRes && localRes.success && localRes.url) {
                                        console.log("[AmusicNative] 端侧直链解析命中:", localRes.source_name);
                                        return new Response(JSON.stringify(localRes), {
                                            status: 200,
                                            headers: { 'Content-Type': 'application/json' }
                                        });
                                    }
                                }
                            } catch(e) {
                                console.warn("[AmusicNative] 端侧寻链异常，透明回退云端:", e);
                            }
                        }

                        // B. 拦截 /api/lyric: 若为街声，端侧直接返回官方抓取的歌词
                        if (url.indexOf('/api/lyric') !== -1 && url.indexOf('platform=streetvoice') !== -1) {
                            try {
                                if (window.AmusicNative && typeof window.AmusicNative.getStreetVoiceLyrics === 'function') {
                                    var queryStr = url.split('?')[1] || '';
                                    var match = queryStr.match(/(?:^|&)id=([^&]+)/);
                                    var sid = match ? decodeURIComponent(match[1]) : null;
                                    if (sid) {
                                        var lrcRes = await new Promise(function(resolve) {
                                            var reqId = 'lrc_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                                            window.__amusic_lrc_callbacks = window.__amusic_lrc_callbacks || {};
                                            window.__amusic_lrc_callbacks[reqId] = resolve;
                                            setTimeout(function() {
                                                if (window.__amusic_lrc_callbacks[reqId]) {
                                                    delete window.__amusic_lrc_callbacks[reqId];
                                                    resolve(null);
                                                }
                                            }, 3000);
                                            window.AmusicNative.getStreetVoiceLyrics(reqId, sid);
                                        });
                                        if (lrcRes && Array.isArray(lrcRes)) {
                                            console.log("[AmusicNative] 端侧街声歌词秒级命中:", lrcRes.length + " 行");
                                            return new Response(JSON.stringify(lrcRes), {
                                                status: 200,
                                                headers: { 'Content-Type': 'application/json' }
                                            });
                                        }
                                    }
                                }
                            } catch(e) {
                                console.warn("[AmusicNative] 端侧街声歌词异常，透明回退云端:", e);
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
                                        }, 3000);
                                        window.AmusicNative.getSourcesRanking(reqId);
                                    });

                                    if (localRanking && localRanking.sources) {
                                        console.log("[AmusicNative] 端侧本机实测音源列表就绪:", localRanking.sources.length + " 个音源");
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
