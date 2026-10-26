package com.example.spotifydesktop

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import java.net.URL

/** Status "sedang diputar" + MediaSession supaya muncul di lock screen / control center HP. */
object NowPlaying {
    var title = ""
    var artist = ""
    var art: Bitmap? = null
    var playing = false
    var command: ((String) -> Unit)? = null   // "playpause" | "next" | "prev"
    var onChange: (() -> Unit)? = null
    private var artUrl = ""
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())

    private fun ensureSession(ctx: Context): MediaSession =
        session ?: MediaSession(ctx.applicationContext, "SpotifyDesktop").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (!playing) command?.invoke("playpause") }
                override fun onPause() { if (playing) command?.invoke("playpause") }
                override fun onSkipToNext() { command?.invoke("next") }
                override fun onSkipToPrevious() { command?.invoke("prev") }
            })
            s.isActive = true
            session = s
        }

    fun update(ctx: Context, t: String, a: String, artwork: String, p: Boolean) {
        title = t; artist = a; playing = p
        if (artwork != artUrl) {
            artUrl = artwork; art = null
            val u = Uri.parse(artwork)
            if (u.scheme == "https" && (u.host ?: "").endsWith("scdn.co")) Thread {
                val b = try { BitmapFactory.decodeStream(URL(artwork).openStream()) } catch (e: Exception) { null }
                main.post { if (artUrl == artwork) { art = b; push(ctx) } }
            }.start()
        }
        push(ctx)
    }

    private fun push(ctx: Context) {
        val s = ensureSession(ctx)
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        art?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        s.setMetadata(md.build())
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
        ctx.getSystemService(NotificationManager::class.java).notify(1, build(ctx))
        onChange?.invoke()
    }

    fun build(ctx: Context): Notification {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("bg", "Pemutaran musik", NotificationManager.IMPORTANCE_LOW)
        )
        val pf = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), pf)
        val quit = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).setAction("QUIT"), pf
        )
        val b = Notification.Builder(ctx, "bg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifEmpty { "Spotify Desktop" })
            .setContentText(if (title.isEmpty()) "Musik berjalan di latar belakang" else artist)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "Keluar", quit
                ).build()
            )
        art?.let { b.setLargeIcon(it) }
        session?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken)) }
        return b.build()
    }

    fun release() {
        session?.release(); session = null
        command = null; onChange = null
        title = ""; artist = ""; art = null; playing = false; artUrl = ""
    }
}

/** Menjaga proses app tetap hidup (notifikasi + wake lock) agar musik jalan di latar belakang. */
class PlaybackService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NowPlaying.build(this)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else
            startForeground(1, n)

        if (cpu == null) {
            cpu = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spd:cpu").apply { acquire() }
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spd:wifi").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cpu?.release()
        wifi?.release()
        super.onDestroy()
    }
}
