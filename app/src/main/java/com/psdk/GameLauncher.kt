package com.psdk

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.psdk.ui.GameActivity

/**
 * Launches the in-process [GameActivity] (formerly an
 * `android.app.NativeActivity`). Env-var publishing, log-file rotation and
 * remote-listener arming all moved into [GameActivity.onCreate] / the
 * [com.psdk.ruby.vm.PsdkInterpreter] it spawns — this object now exists
 * only to encapsulate the Intent shape for the two callers
 * ([com.psdk.ui.CompileWizardFragment] with an ActivityResult, and
 * [com.psdk.ui.EntryActivity] / [com.psdk.ui.ProjectHubFragment] without).
 */
object GameLauncher {

    fun launch(
        activity: ComponentActivity,
        executionLocation: String,
        @Suppress("UNUSED_PARAMETER") releaseLocation: String,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        launcher.launch(GameActivity.intent(activity, executionLocation))
    }

    fun launchDirect(activity: ComponentActivity, executionLocation: String) {
        activity.startActivity(GameActivity.intent(activity, executionLocation))
    }
}
