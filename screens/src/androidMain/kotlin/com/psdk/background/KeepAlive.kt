package com.psdk.background

import android.content.Context

/**
 * The player's choice to keep a game running in the background (a
 * foreground service while it plays, GameKeepAliveService), set in the
 * game's Settings (BackgroundSection). On by default; one choice for every
 * game of the app.
 */
object KeepAlive {
    private const val PREFS = "psdk_game_settings"
    private const val KEY = "keep_alive_in_background"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
