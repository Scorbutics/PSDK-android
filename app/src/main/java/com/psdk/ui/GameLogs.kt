package com.psdk.ui

import android.content.Context
import android.content.Intent
import com.psdk.logs.LogFile
import com.psdk.logs.LogsActivity
import java.io.File

/**
 * A game's logs, in its execution location: its output (start.rb's
 * RGSS_LOG_FILE), and the Error.log PSDK writes next to whichever Game.rb ran:
 *   RELEASE          → <exec>/Release/Error.log  (PSDK's traditional spot)
 *   DEBUG_UNCOMPILED → <exec>/Error.log          (start.rb chdirs to <exec>)
 */
internal object GameLogs {
    fun output(executionLocation: String) = File(executionLocation, "last_stdout.log")

    fun errors(executionLocation: String, mode: GameMode) =
        File(executionLocation, if (mode == GameMode.DEBUG_UNCOMPILED) "Error.log" else "Release/Error.log")

    /** The Error.log written last, of either mode; null when there is none. */
    fun lastErrors(executionLocation: String): File? =
        GameMode.entries.map { errors(executionLocation, it) }.filter { it.isFile }.maxByOrNull { it.lastModified() }

    /**
     * The Logs screen on a game's logs: its output, from the end, and an
     * Error.log, from its start (its message).
     * @param errors by default the one written last
     */
    fun intent(
        context: Context,
        executionLocation: String,
        errors: File = lastErrors(executionLocation) ?: errors(executionLocation, GameMode.RELEASE),
        errorsFirst: Boolean = false,
    ): Intent {
        val files = listOf(
            LogFile("Output", output(executionLocation).path),
            LogFile("Errors", errors.path, fromEnd = false),
        )
        return LogsActivity.intent(context, "Game logs", files, selected = if (errorsFirst) files.lastIndex else 0)
    }
}
