package com.example.spotifydesktop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
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

    private val handler = Handler(Looper.getMainLooper())
    private var lastUser = 0L          // terakhir kali Anda menyentuh layar / menekan kontrol
    private var lastBeat = SystemClock.elapsedRealtime()   // terakhir kali halaman web "hidup"
    private var pagePlaying = false
    private var tries = 0
    private var autoRefresh = true
    private var lastKey = ""
    private var lastRefresh = 0L
    private lateinit var cache: AssetCache

    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    inner class Bridge {
        @JavascriptInterface
        fun update(t: String, a: String, art: String, p: Boolean) {
            runOnUiThread {
                val now = SystemClock.elapsedRealtime()
                lastBeat = now; pagePlaying = p
                if (p) { NowPlaying.wantPlay = true; tries = 0 }
                else if (now - lastUser < 6000) NowPlaying.wantPlay = false   // Anda yang menjeda
                NowPlaying.update(this@MainActivity, t, a, art, p)
            }
        }

        @JavascriptInterface
        fun beat(p: Boolean, pos: Int, ad: Boolean) {
            runOnUiThread {
                lastBeat = SystemClock.elapsedRealtime(); pagePlaying = p
                checkRefresh(pos, ad)
            }
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
        val auto = TextView(this).apply {
            fun label() { text = if (autoRefresh) "Refresh: ON" else "Refresh: OFF" }
            label(); setTextColor(GREEN); textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener {
                autoRefresh = !autoRefresh; label()
                getSharedPreferences("p", Context.MODE_PRIVATE).edit().putBoolean("auto", autoRefresh).apply()
            }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(auto, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
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

    // Lanjutkan hanya jika sedang berhenti (tidak akan menjeda lagu yang sudah jalan)
    private fun resume() = web.evaluateJavascript(
        "(function(){var b=document.querySelector('[data-testid=\"control-button-playpause\"]');" +
            "if(b&&!/pause|jeda/i.test(b.getAttribute('aria-label')||''))b.click();})()", null
    )

    private fun inject(v: WebView) =
        v.evaluateJavascript("window.__vpw=" + widths[wi] + ";" + PlayerScript.SCRIPT, null)

    // Refresh otomatis: setiap lagu (atau iklan) baru mulai, halaman dimuat ulang lalu lagu diputar lagi.
    // Lagu yang Anda pilih sendiri tidak memicu refresh.
    private fun checkRefresh(pos: Int, ad: Boolean) {
        if (!autoRefresh || !pagePlaying) return
        val key = if (ad) "ad" else if (NowPlaying.title.isEmpty()) "" else NowPlaying.title + "|" + NowPlaying.artist
        if (key.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val changed = lastKey.isNotEmpty() && key != lastKey && pos < 8 &&
            now - lastRefresh > 20_000 && now - lastUser > 6_000
        lastKey = key
        if (changed) {
            lastRefresh = now; lastKey = ""
            NowPlaying.wantPlay = true; pagePlaying = false; tries = 0
            web.reload()
        }
    }

    // Setelah halaman dimuat, ketuk play berulang kali sampai player siap
    private fun resumeLoop(n: Int = 0) {
        if (n > 20 || !NowPlaying.wantPlay || pagePlaying) return
        resume()
        handler.postDelayed({ resumeLoop(n + 1) }, 1500)
    }

    // Pengawas: lagu berhenti sendiri -> coba lanjutkan; halaman beku -> muat ulang
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (NowPlaying.wantPlay) {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val busy = am.mode != AudioManager.MODE_NORMAL || (am.isMusicActive && !pagePlaying)
                if (now - lastBeat > 120_000) {
                    lastBeat = now
                    web.reload()
                } else if (!pagePlaying && !busy && now - lastUser > 10_000 && tries < 4) {
                    tries++
                    if (tries == 3) web.reload() else resume()
                }
            }
            handler.postDelayed(this, 15_000)
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askBatteryExemption()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastUser = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        cache = AssetCache(this, desktopUA)
        autoRefresh = getSharedPreferences("p", Context.MODE_PRIVATE).getBoolean("auto", true)

        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        else askBatteryExemption()
        startForegroundService(Intent(this, PlaybackService::class.java))

        web = BgWebView(this)
        // Jaga proses renderer web tetap berprioritas tinggi walau app di latar belakang
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
                if (AdBlocker.shouldBlock(r.url) || cache.skip(r.url)) AdBlocker.blocked()
                else if (cache.eligible(r)) cache.serve(r)
                else null

            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { inject(v) }

            override fun onPageFinished(v: WebView, url: String) {
                inject(v)
                if (NowPlaying.wantPlay) handler.postDelayed({ resumeLoop() }, 2500)
            }

            // Proses renderer web dimatikan sistem: buat ulang layar lalu lanjutkan musik
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }

        setContentView(buildUi())

        NowPlaying.command = { c ->
            runOnUiThread {
                lastUser = SystemClock.elapsedRealtime()
                when (c) {
                    "next" -> click("control-button-skip-forward")
                    "prev" -> click("control-button-skip-back")
                    else -> click("control-button-playpause")
                }
            }
        }
        NowPlaying.onChange = { refreshMini() }

        web.loadUrl("https://open.spotify.com")
        handler.postDelayed(watchdog, 15_000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == "QUIT") {
            NowPlaying.wantPlay = false
            finishAndRemoveTask()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        NowPlaying.release()
        if (isFinishing) {
            NowPlaying.wantPlay = false
            stopService(Intent(this, PlaybackService::class.java))
        }
        web.destroy()
        super.onDestroy()
    }
}
