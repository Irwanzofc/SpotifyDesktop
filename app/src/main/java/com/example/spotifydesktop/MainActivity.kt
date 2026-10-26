package com.example.spotifydesktop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*

private val BG = Color.parseColor("#121212")
private val PANEL = Color.parseColor("#1E1E1E")
private val GREEN = Color.parseColor("#1DB954")

/** WebView yang tidak pernah diberi tahu jendelanya tersembunyi, supaya audio tidak dijeda di latar belakang. */
class BgWebView(c: Context) : WebView(c) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var search: EditText
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniArt: ImageView
    private lateinit var playBtn: ImageButton
    private val widths = intArrayOf(768, 1024, 1280)
    private var wi = 0

    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    inner class Bridge {
        @JavascriptInterface
        fun update(t: String, a: String, art: String, p: Boolean) {
            runOnUiThread { NowPlaying.update(this@MainActivity, t, a, art, p) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(22).toFloat() }

    private fun iconBtn(res: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(12)); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        search = EditText(this).apply {
            hint = "Cari lagu, artis, album…"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 15f
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = round(Color.parseColor("#2A2A2A")); setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { v, _, _ ->
                val q = v.text.toString().trim()
                if (q.isNotEmpty()) go("/search/" + Uri.encode(q))
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        val zoom = TextView(this).apply {
            text = "Zoom"; setTextColor(GREEN); gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener {
                wi = (wi + 1) % widths.size
                web.evaluateJavascript("window.__vpw=" + widths[wi], null)
                Toast.makeText(this@MainActivity, "Lebar halaman: ${widths[wi]}", Toast.LENGTH_SHORT).show()
            }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(zoom, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        }

        miniArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
        miniTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            text = "Belum ada lagu diputar"
        }
        miniArtist = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 12f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        }
        playBtn = iconBtn(android.R.drawable.ic_media_play) { click("control-button-playpause") }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0)
            addView(miniTitle); addView(miniArtist)
        }
        val mini = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(6), dp(6)); setBackgroundColor(PANEL)
            addView(miniArt, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(iconBtn(android.R.drawable.ic_media_previous) { click("control-button-skip-back") })
            addView(playBtn)
            addView(iconBtn(android.R.drawable.ic_media_next) { click("control-button-skip-forward") })
        }

        val nav = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(navBtn("Beranda") { go("/") })
            addView(navBtn("Cari") {
                search.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
            })
            addView(navBtn("Koleksi") { go("/collection/playlists") })
        }

        root.addView(top)
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(mini)
        root.addView(nav)
        return root
    }

    private fun refreshMini() {
        miniTitle.text = NowPlaying.title.ifEmpty { "Belum ada lagu diputar" }
        miniArtist.text = NowPlaying.artist
        miniArt.setImageBitmap(NowPlaying.art)
        playBtn.setImageResource(
            if (NowPlaying.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    // Saat lagu sedang jalan, pindah halaman lewat JS agar player tidak di-reload (musik tidak putus)
    private fun go(path: String) {
        if (NowPlaying.playing) web.evaluateJavascript(
            "(function(p){history.pushState({},'',p);dispatchEvent(new PopStateEvent('popstate',{state:{}}));})('$path')", null
        ) else web.loadUrl("https://open.spotify.com$path")
    }

    private fun click(id: String) = web.evaluateJavascript(
        "(function(){var b=document.querySelector('[data-testid=\"$id\"]');if(b)b.click();})()", null
    )

    private fun inject(v: WebView) =
        v.evaluateJavascript("window.__vpw=" + widths[wi] + ";" + PlayerScript.SCRIPT, null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, PlaybackService::class.java))

        web = BgWebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = desktopUA
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
        web.addJavascriptInterface(Bridge(), "SpotifyBridge")

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                val ok = r.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ok.isNotEmpty()) r.grant(ok.toTypedArray()) else r.deny()
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                if (AdBlocker.shouldBlock(r.url)) AdBlocker.blocked() else null

            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { inject(v) }
            override fun onPageFinished(v: WebView, url: String) { inject(v) }
        }

        setContentView(buildUi())

        NowPlaying.command = { c ->
            runOnUiThread {
                when (c) {
                    "next" -> click("control-button-skip-forward")
                    "prev" -> click("control-button-skip-back")
                    else -> click("control-button-playpause")
                }
            }
        }
        NowPlaying.onChange = { refreshMini() }

        web.loadUrl("https://open.spotify.com")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == "QUIT") finishAndRemoveTask()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        NowPlaying.release()
        stopService(Intent(this, PlaybackService::class.java))
        web.destroy()
        super.onDestroy()
    }
}
