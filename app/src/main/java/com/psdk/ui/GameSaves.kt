package com.psdk.ui

import android.content.Context
import java.io.File

/** Where a game's saves are, and its name for a saves file (com.psdk.saves.SaveTransfer) */
object GameSaves {
    /**
     * PSDK's save root: the game's working directory (start.rb). Release/,
     * or the game's folder in debug mode: two separate sets of saves.
     */
    fun root(executionLocation: String, mode: GameMode): File =
        if (mode == GameMode.DEBUG_UNCOMPILED) File(executionLocation) else File(executionLocation, "Release")

    /** The app's name for a classic game (its title, by default), else its project's */
    fun title(context: Context, executionLocation: String, classicMode: Boolean): String {
        if (classicMode) return context.applicationInfo.loadLabel(context.packageManager).toString()
        val project = runCatching {
            com.psdk.db.AppDatabase.getInstance(context).projectDao().findByDirectory(executionLocation)
        }.getOrNull()
        return project?.name ?: File(executionLocation).name
    }
}
