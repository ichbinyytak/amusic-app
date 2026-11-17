package com.amusic.app.bridge

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 容器桥接管理
 * 负责 WebView 配置、JS 注入、单一事实来源状态同步与外设指令派发
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
        settings.allowFileAccess = false // 收紧权限，避免不必要的文件访问
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
                onOpenSettingsCallback = callbacks::onOpenSettings
            ),
            "AmusicNative"
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChanged(newProgress)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                callbacks.onPageStarted()
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
                // 仅对局域网私有 IP/localhost 自签证书放行，避免公共互联网 MITM 劫持
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
                // 渲染进程异常终止时不闪退应用
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

                // 提供给原生调用的全局控制派发器 (严格幂等控制，杜绝盲目 toggle 导致状态倒错)
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

                // 获取真实歌曲对象：从底层 player.playlist[player.currentIndex] 获取
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

                // 1. 监听底层 audio 标签原生事件
                document.addEventListener('play', function() {
                    syncState();
                }, true);

                document.addEventListener('pause', function() {
                    syncState();
                }, true);

                // 2. 挂钩 player.js 的自定义状态与切歌事件
                if (window.player && typeof window.player.on === 'function') {
                    window.player.on('stateChange', function() {
                        syncState();
                    });
                    window.player.on('playlistChange', function() {
                        syncState();
                    });
                }

                // 3. 初始同步与低功耗保活轮询
                syncState();
                setInterval(syncState, 3000);
            })();
        """.trimIndent()

        view?.evaluateJavascript(bridgeJs, null)
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
