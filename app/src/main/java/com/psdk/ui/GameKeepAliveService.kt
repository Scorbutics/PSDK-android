package com.psdk.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.psdk.R
import com.psdk.background.KeepAlive

/**
 * Keeps a game's process running in the background: a foreground service
 * (its notification shows while a game is open) puts the `:game` process at
 * a priority Android, and a phone's memory optimiser, kill much less often.
 * Not a guarantee: when it still happens, the suspend save (GameSuspend)
 * offers to resume. The player turns it off in the game's Settings
 * (KeepAlive). Runs in the game's process, started and stopped by
 * GameActivity.
 */
class GameKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val notification = notification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Killed with its process: the game is gone, nothing to restart
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a game is open, to keep it running in the background"
                    setShowBadge(false)
                }
            )
        }
        // Back to the game, as it was
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                      else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.logo)
            .setContentTitle(title)
            .setContentText("Game in progress")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "game_in_progress"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "TITLE"

        /** Running while a game plays, if the player keeps that on (KeepAlive) */
        fun update(context: Context, playing: Boolean, title: String?) {
            val intent = Intent(context, GameKeepAliveService::class.java).putExtra(EXTRA_TITLE, title)
            if (playing && KeepAlive.enabled(context)) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                }.onFailure { android.util.Log.w("GameKeepAlive", "not started: ${it.message}") }
            } else {
                context.stopService(intent)
            }
        }
    }
}
