package com.example.spotifydesktop

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Menjaga proses app tetap hidup (notifikasi + wake lock) agar musik jalan saat app di latar belakang. */
class PlaybackService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("bg", "Pemutaran musik", NotificationManager.IMPORTANCE_LOW)
        )
        val pf = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pf)
        val quit = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).setAction("QUIT"), pf
        )
        val n = Notification.Builder(this, "bg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Spotify Desktop")
            .setContentText("Musik berjalan di latar belakang")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Keluar", quit
                ).build()
            )
            .build()

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
