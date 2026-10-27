package com.example.spotifydesktop

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Menyimpan file statis web player (JS, CSS, font, gambar) di penyimpanan app, jadi tidak diunduh ulang
 * di tiap refresh dan tiap buka app. Audio tidak pernah disimpan. Kalau ada yang gagal, WebView
 * memuat dari internet seperti biasa.
 */
class AssetCache(ctx: Context, private val ua: String) {
    private val dir = File(ctx.filesDir, "assets").apply { mkdirs() }
    private val maxBytes = 200L * 1024 * 1024
    private val maxAgeMs = 14L * 24 * 3600 * 1000
    private var writes = 0

    private val types = mapOf(
        "js" to "application/javascript", "css" to "text/css", "woff2" to "font/woff2",
        "woff" to "font/woff", "ttf" to "font/ttf", "svg" to "image/svg+xml", "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif",
        "ico" to "image/x-icon"
    )

    init {
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** Video loop "Canvas" boros kuota, jadi tidak dimuat. */
    fun skip(u: Uri) = u.host == "canvaz.scdn.co"

    fun eligible(r: WebResourceRequest): Boolean {
        val u = r.url
        val host = u.host ?: return false
        if (r.method != "GET" || u.scheme != "https" || r.requestHeaders.containsKey("Range")) return false
        if (!host.endsWith(".scdn.co") && !host.endsWith(".spotifycdn.com")) return false
        return ext(u) in types || (u.path ?: "").startsWith("/image/")
    }

    private fun ext(u: Uri) = (u.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()

    fun serve(r: WebResourceRequest): WebResourceResponse? {
        try {
            val url = r.url.toString()
            val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val f = File(dir, name)
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", ua)
                if (c.responseCode != 200) { c.disconnect(); return null }
                val tmp = File.createTempFile("dl", ".tmp", dir)
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                c.disconnect()
                if (!tmp.renameTo(f)) { tmp.delete(); return null }
                if (++writes % 50 == 0) trim()
            }
            val e = ext(r.url)
            val charset = if (e == "js" || e == "css" || e == "svg") "utf-8" else null
            val headers = mapOf(
                "Access-Control-Allow-Origin" to (r.requestHeaders["Origin"] ?: "*"),
                "Cache-Control" to "public, max-age=86400"
            )
            return WebResourceResponse(types[e] ?: "image/jpeg", charset, 200, "OK", headers, FileInputStream(f))
        } catch (e: Exception) {
            return null
        }
    }

    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files) {
            if (total <= maxBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }
}
