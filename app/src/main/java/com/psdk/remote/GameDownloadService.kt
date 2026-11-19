package com.psdk.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.psdk.R

/**
 * Keeps the app's process alive while GameDownloader downloads a small
 * APK's game (hundreds of MB, minutes): the player can switch apps
 * meanwhile. Its notification says the game is downloading.
 */
class GameDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game download", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the game downloads"
                    setShowBadge(false)
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.logo)
            .setContentTitle(intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name))
            .setContentText("Downloading the game")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "game_download"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_TITLE = "TITLE"

        fun start(context: Context, title: String?) {
            val intent = Intent(context, GameDownloadService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { android.util.Log.w("GameDownload", "service not started: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GameDownloadService::class.java))
        }
    }
}
