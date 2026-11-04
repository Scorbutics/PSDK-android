package com.psdk.ui

import android.app.Activity
import android.os.SystemClock
import com.psdk.R
import java.io.File

/**
 * Tell the player why a game stopped, instead of silently leaving it: the
 * "The game stopped" dialog, with the Error.log PSDK wrote (Release/ for a
 * compiled game, the execution location for debug-uncompiled).
 *
 * - The hub (RootActivity) shows it when GameActivity returns abnormally.
 * - A classic-mode game (an exported APK, no hub behind it) shows it from
 *   GameActivity itself before closing, when an error ended the game.
 * - A native crash kills the `:game` process before anything can show it:
 *   a classic game leaves a [crash marker][markRunning] while it is in the
 *   foreground, and the next launch (EntryActivity) reports it.
 */
internal object GameExitReport {
    /** File mtimes can be coarser than the clock. */
    private const val MTIME_SLACK_MS = 2_000L
    private const val RUNNING_MARKER = ".game_running"

    /** How the player closed the dialog */
    enum class Choice { OK, DETAILS, CANCELLED }

    /**
     * Show the dialog.
     * @param startedAtMs when the game session started: an older Error.log is another session's
     * @param crashed the process died without a result (no exit code)
     * @param onDone once the dialog closes, any way (DETAILS: the Logs screen
     *   opened over the caller; CANCELLED: Back, or a tap outside)
     */
    fun show(
        activity: Activity,
        executionLocation: String,
        startedAtMs: Long,
        crashed: Boolean,
        exitCode: Int?,
        onDone: (Choice) -> Unit = {},
    ) {
        val errorLog = freshErrorLog(GameLogs.lastErrors(executionLocation), startedAtMs)
        val details = errorLog?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
        var choice = Choice.CANCELLED
        var done = false
        val finish = { if (!done) { done = true; onDone(choice) } }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Psdk_Dialog)
            .setTitle("The game stopped")
            .setMessage(summary(details, crashed, exitCode))
            .setPositiveButton(android.R.string.ok) { _, _ -> choice = Choice.OK }
            .setOnDismissListener { finish() }
        // The output alone is worth showing after a crash (no Error.log)
        if (details != null || crashed) {
            val errors = errorLog ?: GameLogs.errors(executionLocation, GameMode.RELEASE)
            dialog.setNeutralButton("Details") { _, _ ->
                choice = Choice.DETAILS
                activity.startActivity(GameLogs.intent(activity, executionLocation, errors, errorsFirst = details != null))
            }
        }
        dialog.show()
    }

    /** The Error.log of this session, null when it is older (another session's) */
    fun freshErrorLog(errorLog: File?, sinceMs: Long): File? =
        errorLog?.takeIf { it.isFile && it.lastModified() >= sinceMs - MTIME_SLACK_MS }

    /** What the dialog says: the Error.log's error, else why there is none. */
    fun summary(errorLog: String?, crashed: Boolean, exitCode: Int?): String =
        errorLog?.let(::summarizeErrorLog)
            ?: if (crashed) "The game closed unexpectedly and did not write an error report."
            else "The game stopped with an error (exit code $exitCode)."

    /**
     * "Type: message" from a PSDK Error.log. Handles both layouts PSDK
     * writes: "Error type : X" / "Error message : Y" on one line each, and
     * the in-game report with "Message :" followed by the text on the next
     * line. Falls back to the first meaningful lines.
     */
    fun summarizeErrorLog(log: String): String {
        val lines = log.lines()
        var type: String? = null
        var message: String? = null
        val field = Regex("""^\s*(?:Error\s+)?(type|message)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
        lines.forEachIndexed { i, line ->
            val m = field.find(line) ?: return@forEachIndexed
            val value = m.groupValues[2].trim().ifEmpty {
                lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }
            if (m.groupValues[1].equals("type", ignoreCase = true)) {
                if (type == null) type = value
            } else if (message == null) {
                message = value
            }
        }
        val summary = listOfNotNull(type, message).filter { it.isNotEmpty() }.joinToString(": ")
        return summary.ifEmpty {
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("=") }.take(4).joinToString("\n")
        }.take(400)
    }

    // A classic game in the foreground holds the marker: it is gone once the
    // game is in the background (the player or Android may then kill it
    // without a crash) or closed. Left behind, the game died in the
    // foreground: a crash. Unless the device was turned off since (no
    // onStop then): a marker older than the last boot is no crash.

    fun markRunning(executionLocation: String) {
        runCatching { File(executionLocation, RUNNING_MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    fun clearRunning(executionLocation: String) {
        runCatching { File(executionLocation, RUNNING_MARKER).delete() }
    }

    /**
     * The crash a marker left, consumed.
     * @param bootedAtMs when the device last started (wall clock)
     * @return when the game last came to the foreground before it, null when
     *   it ended normally, or the device was turned off since
     */
    fun takeCrash(
        executionLocation: String,
        bootedAtMs: Long = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
    ): Long? {
        val marker = File(executionLocation, RUNNING_MARKER)
        if (!marker.isFile) return null
        val markedAt = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: marker.lastModified()
        marker.delete()
        return markedAt.takeIf { it >= bootedAtMs }
    }
}
