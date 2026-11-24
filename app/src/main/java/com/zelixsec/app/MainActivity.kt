package com.zelixsec.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.zelixsec.app.bridge.ZelixBridge
import com.zelixsec.app.runtime.ZelixRuntime
import com.zelixsec.app.vpn.ZelixVpnService

/**
 * ZelixGate - Ana etkinlik
 * Renderer (index-android.html) https://appassets.androidplatform.net/assets
 * adresinden yüklenir; Electron preload API'si Kotlin köprüsüne bağlanır
 * (zelix-android-bridge.js -> AndroidZelix.invoke -> runtime -> __zelixResult).
 */
class MainActivity : Activity() {

    companion object {
        private const val ASSET_URL =
            "https://appassets.androidplatform.net/assets/web/index-android.html"
        private const val REQUEST_PREPARE_VPN = 7717
    }

    private lateinit var webView: WebView
    private var assetLoader: WebViewAssetLoader? = null
    private lateinit var mainHandler: Handler

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ZelixRuntime.init(this)
        mainHandler = Handler(Looper.getMainLooper())

        val root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        root.addView(webView)
        setContentView(root)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
        }
        webView.setBackgroundColor(0xFF070B14.toInt())
        webView.addJavascriptInterface(ZelixBridge(), "AndroidZelix")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return assetLoader?.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                // Harici bağlantılar sistem tarayıcısında açılır (main.js ile aynı).
                if (!url.startsWith(ASSET_URL)) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: Exception) {
                        }
                    }
                    return true
                }
                return false
            }
        }

        // Çalışma zamanı bağları (her etkinlik örneği için yenilenir).
        ZelixRuntime.resultSink = { id, _name, json -> deliverResult(id, json) }
        ZelixRuntime.webSink = { channel, payload -> deliverEvent(channel, payload) }
        ZelixRuntime.uiTask = { action -> runOnUiThread { handleUiTask(action) } }

        webView.loadUrl(ASSET_URL)
    }

    /* --------------------------------------------------------- geri ileti oku */

    /** runtime -> window.__zelixResult("$id", json) */
    private fun deliverResult(id: String, json: String) {
        if (isFinishing || isDestroyed) return
        if (id.any { !it.isLetterOrDigit() && it != '_' }) return
        webView.evaluateJavascript(
            "window.__zelixResult && window.__zelixResult(\"$id\", $json); void 0;",
            null,
        )
    }

    /** runtime -> window.__zelixEvent("channel", json) */
    private fun deliverEvent(channel: String, payload: String) {
        if (isFinishing || isDestroyed) return
        val safeChannel = channel.filter { it.isLetterOrDigit() || it == ':' || it == '-' }
        webView.evaluateJavascript(
            "window.__zelixEvent && window.__zelixEvent(\"$safeChannel\", $payload); void 0;",
            null,
        )
    }

    /* ------------------------------------------------------------- UI görevleri */

    private fun handleUiTask(action: String) {
        when (action) {
            "app:quit" -> {
                // Motor/tünel zaten runtime tarafında kapatılır; etkinliği bitir.
                try {
                    stopService(Intent(this, ZelixVpnService::class.java))
                } catch (_: Exception) {
                }
                finishAffinity()
            }
            "window:hide" -> moveTaskToBack(true)
            "vpn-consent" -> prepareVpn()
        }
    }

    /* ---------------------------------------------------------------- VPN izni */

    fun prepareVpn() {
        try {
            val consent = VpnService.prepare(this)
            if (consent == null) {
                startVpnService()
            } else {
                startActivityForResult(consent, REQUEST_PREPARE_VPN)
            }
        } catch (err: Exception) {
            ZelixRuntime.pushLog("warn", "VPN izni istenemedi: ${err.message ?: "bilinmeyen"}")
            ZelixRuntime.onVpnConsent(false)
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, ZelixVpnService::class.java)
        intent.action = ZelixVpnService.ACTION_START
        try {
            startForegroundService(intent)
        } catch (err: Exception) {
            ZelixRuntime.pushLog("error", "Tünel servisi başlatılamadı: ${err.message ?: "bilinmeyen"}")
            ZelixRuntime.onVpnConsent(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PREPARE_VPN) {
            ZelixRuntime.onVpnConsent(resultCode == RESULT_OK)
            if (resultCode == RESULT_OK) startVpnService()
        }
    }

    /* ----------------------------------------------------------------- yaşam */

    override fun onResume() {
        super.onResume()
        // "Bilinmeyen uygulamalar" izni verilip geri dönüldüyse bekleyen
        // güncelleme kurulumunu otomatik aç.
        ZelixRuntime.onActivityResumed()
    }

    override fun onBackPressed() {
        // Masaüstündeki tray'i kestirme davranışı: arka plana in, kapatma.
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        ZelixRuntime.resultSink = null
        ZelixRuntime.webSink = null
        ZelixRuntime.uiTask = null
        super.onDestroy()
    }
}
