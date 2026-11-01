package com.youtubedownloader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service (type dataSync, required on Android 14+). It does no work itself: it only
 * keeps the app process alive and visible while the ViewModel runs yt-dlp.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROGRESS,
            Notifications.progress(this, "Starting download...", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_NOT_STICKY
    }

    /** Android 15+ limits dataSync services; stop cleanly instead of being killed by the system. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
                // Download still runs while the app is in the foreground.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DownloadService::class.java))
        }
    }
}

object Notifications {
    private const val CH_PROGRESS = "progress"
    private const val CH_RESULT = "result"
    const val ID_PROGRESS = 1
    private const val ID_RESULT = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Download results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** percent == null -> indeterminate bar. */
    fun progress(ctx: Context, text: String, percent: Int?): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YouTube Downloader")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setProgress(100, percent ?: 0, percent == null)
            .build()

    fun updateProgress(ctx: Context, text: String, percent: Int?) {
        ctx.getSystemService(NotificationManager::class.java)
            .notify(ID_PROGRESS, progress(ctx, text, percent))
    }

    fun result(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RESULT, n)
    }
}
