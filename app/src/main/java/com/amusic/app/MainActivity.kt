package com.amusic.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amusic.app.bridge.AmusicWebBridge
import com.amusic.app.databinding.ActivityMainBinding
import com.amusic.app.media.MediaSessionManager
import com.amusic.app.media.MusicService
import com.amusic.app.resolver.LocalResolver
import com.amusic.app.ui.ServerConfigDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity(), MediaSessionManager.ControlListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var webBridge: AmusicWebBridge
    private lateinit var localResolver: LocalResolver

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
        localResolver = LocalResolver(this)

        bindMusicService()
        checkNotificationPermission()
        setupBridge()
        setupUIListeners()
        setupBackNavigation()

        loadCurrentServerUrl()
        triggerDailySourceSync()
    }

    private fun triggerDailySourceSync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                localResolver.syncSources(force = false)
            } catch (e: Exception) {
                Log.w(TAG, "每日音源静默同步重试: ${e.message}")
            }
        }
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

    private fun setupBridge() {
        webBridge = AmusicWebBridge(
            webView = binding.webView,
            callbacks = object : AmusicWebBridge.BridgeCallbacks {
                override fun onProgressChanged(progress: Int) {
                    binding.loadProgressBar.progress = progress
                    binding.loadProgressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onPageStarted() {
                    binding.errorView.visibility = View.GONE
                }

                override fun onPageFinished() {
                    binding.errorView.visibility = View.GONE
                }

                override fun onReceivedError(description: String) {
                    showError(description)
                }

                override fun onTrackChanged(
                    title: String,
                    artist: String,
                    album: String,
                    artworkUrl: String
                ) {
                    runOnUiThread {
                        musicService?.updateTrackMetadata(title, artist, album, artworkUrl)
                    }
                }

                override fun onPlaybackStateChanged(isPlaying: Boolean) {
                    runOnUiThread {
                        musicService?.updatePlaybackState(isPlaying)
                    }
                }

                override fun onOpenSettings() {
                    runOnUiThread {
                        showServerConfigDialog()
                    }
                }

                override fun onResolveSong(reqId: String, payloadJson: String) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val payload = JSONObject(payloadJson)
                            val title = payload.optString("title").ifBlank { payload.optString("name") }
                            val artist = payload.optString("artist")
                            val album = payload.optString("album")
                            val duration = payload.optInt("duration", 0)
                            val platform = payload.optString("platform")
                            val id = payload.optString("id")

                            val result = localResolver.resolve(title, artist, album, duration, platform, id)
                            if (result != null && result.success && !result.url.isNullOrBlank()) {
                                val resJson = JSONObject().apply {
                                    put("success", true)
                                    put("url", result.url)
                                    put("source_name", result.sourceName)
                                    put("costMs", result.costMs)
                                }.toString()
                                webBridge.sendResolveResult(reqId, resJson)
                            } else {
                                webBridge.sendResolveResult(reqId, null)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "端侧直链解析失败，回退云端: ${e.message}")
                            webBridge.sendResolveResult(reqId, null)
                        }
                    }
                }

                override fun onGetSourcesRanking(reqId: String) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val rankingJson = localResolver.getSourcesRanking()
                            webBridge.sendRankingResult(reqId, rankingJson)
                        } catch (e: Exception) {
                            Log.w(TAG, "获取端侧实测音源失败，回退云端: ${e.message}")
                            webBridge.sendRankingResult(reqId, null)
                        }
                    }
                }
            }
        )
    }

    private fun setupUIListeners() {
        binding.btnRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            webBridge.reload()
        }

        binding.btnChangeUrl.setOnClickListener {
            showServerConfigDialog()
        }

        binding.rootContainer.setOnLongClickListener {
            showServerConfigDialog()
            true
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackTime = 0L

            override fun handleOnBackPressed() {
                if (webBridge.canGoBack()) {
                    webBridge.goBack()
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
        webBridge.loadUrl(url)
    }

    private fun showError(description: String) {
        binding.tvErrorMsg.text = "无法访问 Amusic\n($description)"
        binding.tvCurrentUrl.text = getServerUrl()
        binding.errorView.visibility = View.VISIBLE
    }

    private fun showServerConfigDialog() {
        ServerConfigDialog.show(
            context = this,
            currentUrl = getServerUrl()
        ) { newUrl ->
            prefs.edit().putString("server_url", newUrl).apply()
            binding.tvCurrentUrl.text = newUrl
            webBridge.loadUrl(newUrl)
        }
    }

    // --- MediaSessionManager.ControlListener 回调 ---

    override fun onCommandPlay() {
        runOnUiThread {
            webBridge.dispatchAction("play")
        }
    }

    override fun onCommandPause() {
        runOnUiThread {
            webBridge.dispatchAction("pause")
        }
    }

    override fun onCommandTogglePlay() {
        runOnUiThread {
            webBridge.dispatchAction("togglePlay")
        }
    }

    override fun onCommandPrev() {
        runOnUiThread {
            webBridge.dispatchAction("prev")
        }
    }

    override fun onCommandNext() {
        runOnUiThread {
            webBridge.dispatchAction("next")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        musicService?.setControlListener(null)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
