package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.GameLauncher
import com.psdk.ruby.vm.PsdkInterpreter

class EntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PsdkInterpreter.initialize()

        val mode = AppModeDetector.detect(this)
        val target = when (mode) {
            AppModeDetector.AppMode.CLASSIC_USER -> classicTarget()
            AppModeDetector.AppMode.SUPER_USER -> RootActivity.hubIntent(this)
        } ?: return

        startActivity(target)
        finish()
    }

    /**
     * Pick where to send a classic-mode user, or return null after we've
     * handled it inline (game launch path doesn't need a separate
     * activity start). The state machine:
     *
     *   first launch / cleared data → STATE_NEEDS_SETUP → wizard (with
     *       classic-bootstrap so it auto-stages the bundled .epsa)
     *   wizard finished successfully → STATE_SETUP_COMPLETE → flip to
     *       STATE_READY here, then launch the game
     *   subsequent launches      → STATE_READY → launch the game directly
     *
     * An APK upgrade (new versionCode) forces back to NEEDS_SETUP so the
     * fresh .epsa gets re-compiled.
     */
    private fun classicTarget(): Intent? {
        val prefs = getSharedPreferences(ClassicModeState.PREFS, MODE_PRIVATE)
        val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val storedVersionCode = prefs.getLong(ClassicModeState.VERSION_CODE_KEY, -1L)
        if (storedVersionCode != currentVersionCode) {
            prefs.edit()
                .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_NEEDS_SETUP)
                .apply()
        }

        return when (prefs.getString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_NEEDS_SETUP)) {
            ClassicModeState.STATE_READY -> {
                GameLauncher.launchDirect(this, applicationInfo.dataDir)
                finish()
                null
            }
            ClassicModeState.STATE_SETUP_COMPLETE -> {
                // First launch after a successful wizard run — flip to
                // READY for next time, then go straight into the game.
                prefs.edit()
                    .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_READY)
                    .apply()
                GameLauncher.launchDirect(this, applicationInfo.dataDir)
                finish()
                null
            }
            else -> RootActivity.classicWizardIntent(this, applicationInfo.dataDir)
        }
    }
}
