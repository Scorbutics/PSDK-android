package com.psdk.ui

import android.content.Context
import android.content.Intent
import com.psdk.logs.LogFile
import com.psdk.logs.LogsActivity
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File
import java.io.IOException

/**
 * A game's logs, in its execution location: its output ([outputWriter]), and
 * the Error.log PSDK writes next to whichever Game.rb ran:
 *   RELEASE          → <exec>/Release/Error.log  (PSDK's traditional spot)
 *   DEBUG_UNCOMPILED → <exec>/Error.log          (start.rb chdirs to <exec>)
 */
internal object GameLogs {
    private const val OUTPUT_MAX_BYTES = 4L * 1024 * 1024
    /** The terminal colors of the native logs */
    private val ANSI_COLOR = Regex("\u001B\\[[0-9;]*m")

    fun output(executionLocation: String) = File(executionLocation, "last_stdout.log")

    /**
     * The game's console: keeps its output in [output], in any game mode. A
     * line is flushed as it comes, so a crash keeps the last ones, and past
     * OUTPUT_MAX_BYTES the file moves to a ".1" one, so a long session keeps
     * the end of its output.
     */
    fun outputWriter(executionLocation: String): LogListener = object : LogListener {
        private val file = output(executionLocation)

        @Synchronized
        override fun onLogMessage(logMessage: LogMessage) {
            try {
                if (file.length() > OUTPUT_MAX_BYTES) file.renameTo(File("${file.path}.1"))
                file.appendText(logMessage.message.replace(ANSI_COLOR, "").trimEnd('\n') + "\n")
            } catch (e: IOException) {
                android.util.Log.w("GameLogs", "game output not kept: ${e.message}")
            }
        }
    }

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
