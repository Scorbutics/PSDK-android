package com.psdk.ui

import android.app.Activity
import com.psdk.R
import java.io.File

/**
 * The suspend save (lib/suspend_save.rb): the game is written to [file] when
 * the app goes to the background, where Android may kill it without warning,
 * and the file deleted when it comes back. Still there when the game
 * starts, the game was killed in the background: the player chooses to
 * resume from it. It is apart from the player's saves, never touched.
 */
object GameSuspend {
    /** The game's suspend file (start.rb: RGSS_SUSPEND_FILE) */
    fun file(executionLocation: String): File = File(executionLocation, "game.suspend")

    /**
     * Asks whether to resume when the game was killed in the background,
     * then calls [then] with the answer (false at once, without a file).
     * Starting normally deletes the file.
     */
    fun askResume(activity: Activity, executionLocation: String, then: (resume: Boolean) -> Unit) {
        val file = file(executionLocation)
        if (!file.isFile) return then(false)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Psdk_Dialog)
            .setTitle("Resume your game?")
            .setMessage(
                "The game was closed by the system while it was in the background. " +
                    "Resume where you left off? Your saves are not changed either way."
            )
            .setPositiveButton("Resume") { _, _ -> then(true) }
            .setNegativeButton("Start normally") { _, _ ->
                file.delete()
                then(false)
            }
            .setCancelable(false)
            .show()
            .also(DialogStyle::callToAction)
    }
}
