package com.amusic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amusic.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), MusicService.MusicControlListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var musicService: MusicService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.LocalBinder
            musicService = binder.getService()
            musicService?.setControlListener(this@MainActivity)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService?.setControlListener(null)
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("amusic_prefs", Context.MODE_PRIVATE)

        bindMusicService()
        checkNotificationPermission()
        setupWebView()
        setupUIListeners()
        setupBackNavigation()

        loadCurrentServerUrl()
    }

    private fun bindMusicService() {
        val serviceIntent = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        val defaultUserAgent = settings.userAgentString
        settings.userAgentString = "$defaultUserAgent AmusicAndroidApp/1.0"

        webView.addJavascriptInterface(
            WebAppInterface(
                onTrackChangedCallback = { title, artist, album, artworkUrl ->
                    runOnUiThread {
                        musicService?.updateTrackMetadata(title, artist, album, artworkUrl)
                    }
                },
                onPlaybackStateChangedCallback = { isPlaying ->
                    runOnUiThread {
                        musicService?.updatePlaybackState(isPlaying)
                    }
                },
                onOpenSettingsCallback = {
                    runOnUiThread {
                        showServerConfigDialog()
                    }
                }
            ),
            "AmusicNative"
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.loadProgressBar.progress = newProgress
                if (newProgress in 1..99) {
                    binding.loadProgressBar.visibility = View.VISIBLE
                } else {
                    binding.loadProgressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.errorView.visibility = View.GONE
                injectNativeBridge(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    showError(error?.description?.toString() ?: "连接失败")
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }
    }

    private fun injectNativeBridge(webView: WebView?) {
        val bridgeJs = """
            (function() {
                if (window.__amusic_bridge_injected) return;
                window.__amusic_bridge_injected = true;

                console.log("[AmusicNative] Injected into page.");

                // 提供给原生调用的全局控制派发器
                window.__amusic_dispatch = function(action) {
                    console.log("[AmusicNative] Received action:", action);
                    try {
                        if (window.player) {
                            if (action === 'togglePlay' || action === 'play' || action === 'pause') {
                                window.player.togglePlay();
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

                // 监听原生 MediaSession 或底层 Audio 状态同步给 App
                function syncState() {
                    try {
                        if (window.player && window.player.currentSong) {
                            var s = window.player.currentSong;
                            var title = s.title || s.name || "未知曲目";
                            var artist = s.artist || "Apple Music";
                            var album = s.album || "Amusic";
                            var artwork = s.artwork || s.cover || s.picUrl || "";
                            var isPlaying = window.player.isPlaying || false;

                            if (window.AmusicNative) {
                                window.AmusicNative.onTrackChanged(title, artist, album, artwork);
                                window.AmusicNative.onPlaybackStateChanged(isPlaying);
                            }
                        }
                    } catch(e) {}
                }

                // 挂钩已有的 navigator.mediaSession
                if ('mediaSession' in navigator) {
                    var originalMetadata = Object.getOwnPropertyDescriptor(Navigator.prototype, 'mediaSession') ||
                                           Object.getOwnPropertyDescriptor(navigator, 'mediaSession');
                    // 周期性与事件性同步
                    setInterval(syncState, 1500);
                }

                // 挂钩 audio 标签播放事件
                document.addEventListener('play', function() {
                    if (window.AmusicNative) window.AmusicNative.onPlaybackStateChanged(true);
                    syncState();
                }, true);

                document.addEventListener('pause', function() {
                    if (window.AmusicNative) window.AmusicNative.onPlaybackStateChanged(false);
                    syncState();
                }, true);

            })();
        """.trimIndent()

        webView?.evaluateJavascript(bridgeJs, null)
    }

    private fun setupUIListeners() {
        binding.btnRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.webView.reload()
        }

        binding.btnChangeUrl.setOnClickListener {
            showServerConfigDialog()
        }

        // 长按根布局可快捷唤起更换服务器弹窗
        binding.rootContainer.setOnLongClickListener {
            showServerConfigDialog()
            true
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackTime = 0L

            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackTime < 2000) {
                        moveTaskToBack(true)
                    } else {
                        lastBackTime = currentTime
                        Toast.makeText(this@MainActivity, "再按一次退出到后台", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun getServerUrl(): String {
        return prefs.getString("server_url", getString(R.string.default_server_url))
            ?: getString(R.string.default_server_url)
    }

    private fun loadCurrentServerUrl() {
        val url = getServerUrl()
        binding.tvCurrentUrl.text = url
        binding.webView.loadUrl(url)
    }

    private fun showError(description: String) {
        binding.tvErrorMsg.text = "无法访问 Amusic\n($description)"
        binding.tvCurrentUrl.text = getServerUrl()
        binding.errorView.visibility = View.VISIBLE
    }

    private fun showServerConfigDialog() {
        val current = getServerUrl()
        val input = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            hint = "http://192.168.x.x:8088 或域名"
        }

        AlertDialog.Builder(this)
            .setTitle("设置 Amusic 服务器地址")
            .setView(input)
            .setPositiveButton("保存并刷新") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    val formattedUrl = if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        "http://$newUrl"
                    } else {
                        newUrl
                    }
                    prefs.edit().putString("server_url", formattedUrl).apply()
                    binding.tvCurrentUrl.text = formattedUrl
                    binding.webView.loadUrl(formattedUrl)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --- MusicControlListener 回调 (来自通知栏/锁屏/耳机按键) ---

    override fun onCommandPlay() {
        runOnUiThread {
            binding.webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('play');", null)
        }
    }

    override fun onCommandPause() {
        runOnUiThread {
            binding.webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('pause');", null)
        }
    }

    override fun onCommandTogglePlay() {
        runOnUiThread {
            binding.webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('togglePlay');", null)
        }
    }

    override fun onCommandPrev() {
        runOnUiThread {
            binding.webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('prev');", null)
        }
    }

    override fun onCommandNext() {
        runOnUiThread {
            binding.webView.evaluateJavascript("window.__amusic_dispatch && window.__amusic_dispatch('next');", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
