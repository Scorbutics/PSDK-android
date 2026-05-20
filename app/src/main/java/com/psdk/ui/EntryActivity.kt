package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.litergss.NativeSurface

class EntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PsdkInterpreter.initialize()

        // Process-wide: every cgss::DisplayWindow constructed in this
        // process adopts the preserved (process-shared) sf::RenderWindow.
        // Set ONCE here (the very first activity to run) and never reset.
        //
        // With the unified-Activity model (RootActivity hosts both the
        // splash and the game in-process via RubyWorkloadController),
        // this opt-in is less critical than it used to be — the splash
        // and game now share the same WindowImplAndroid + EglContext
        // by construction, since neither activity nor render thread
        // changes between them. Kept on as a defensive default so any
        // future code that destroys/recreates a DisplayWindow mid-
        // session inherits the preserved shared window instead of
        // accidentally falling into owned-mode teardown.
        NativeSurface.setReuseSharedWindow(true)

        val mode = AppModeDetector.detect(this)
        val target = when (mode) {
            AppModeDetector.AppMode.CLASSIC_USER -> classicTarget()
            AppModeDetector.AppMode.SUPER_USER -> RootActivity.hubIntent(this)
        } ?: return

        startActivity(target)
        finish()
    }

    /**
     * Pick which RootActivity intent a classic-mode user should be
     * dispatched to. The state machine:
     *
     *   first launch / cleared data → STATE_NEEDS_SETUP → wizard (with
     *       classic-bootstrap so it auto-stages the bundled .epsa)
     *   wizard finished successfully → STATE_SETUP_COMPLETE → flip to
     *       STATE_READY here, then go straight into the game
     *   subsequent launches      → STATE_READY → straight into the game
     *
     * All three branches return a RootActivity intent; EntryActivity's
     * caller does the startActivity + finish. The legacy direct-launch
     * via GameActivity is gone (Phase D unification).
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
                // Unified-Activity model: RootActivity hosts the game
                // in-process via TARGET_DIRECT_GAME (no splash, no
                // intermediate fragment).
                RootActivity.directGameIntent(this, applicationInfo.dataDir)
            }
            ClassicModeState.STATE_SETUP_COMPLETE -> {
                // First launch after a successful wizard run — flip to
                // READY for next time, then go straight into the game.
                prefs.edit()
                    .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_READY)
                    .apply()
                RootActivity.directGameIntent(this, applicationInfo.dataDir)
            }
            else -> RootActivity.classicWizardIntent(this, applicationInfo.dataDir)
        }
    }
}
