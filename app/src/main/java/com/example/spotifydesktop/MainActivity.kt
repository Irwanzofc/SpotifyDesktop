package com.example.spotifydesktop

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.*

class MainActivity : Activity() {
    private lateinit var web: WebView

    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = desktopUA          // tampilan desktop
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webChromeClient = object : WebChromeClient() {
            // Izinkan DRM (Widevine) agar musik bisa diputar
            override fun onPermissionRequest(r: PermissionRequest) {
                val ok = r.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ok.isNotEmpty()) r.grant(ok.toTypedArray()) else r.deny()
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                if (AdBlocker.shouldBlock(r.url)) AdBlocker.blocked() else null

            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                v.evaluateJavascript(AdBlocker.SCRIPT, null)
            }
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript(AdBlocker.SCRIPT, null)
            }
        }

        web.loadUrl("https://open.spotify.com")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
