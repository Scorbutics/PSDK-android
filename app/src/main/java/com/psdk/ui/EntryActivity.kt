package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.GameLauncher
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.litergss.NativeSurface

class EntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PsdkInterpreter.initialize()

        // Process-wide: every cgss::DisplayWindow constructed in this
        // process adopts the preserved (process-shared) sf::RenderWindow.
        // Set ONCE here (the very first activity to run) and never reset,
        // so both the splash (RootActivity) and the game (GameActivity)
        // share the same WindowImplAndroid + EglContext.
        //
        // Why this matters: SFML's sf::Shader::getDefaultShader caches a
        // process-global `static Shader instance` whose GL program handle
        // is created against whichever EglContext was active first. If
        // the splash creates that context (owned mode) and then tears it
        // down, the default-shader handle goes stale — sf::Sprite renders
        // (and any PSDK custom shader that draws over a default-rendered
        // sprite) come out as blank rectangles in the subsequent game
        // session. Keeping the shared sf::RenderWindow open across the
        // splash teardown (cgss::DisplayWindow::stop's preservation
        // branch, [DisplayWindow.cpp:232-247]) keeps that EglContext —
        // and the default shaders' handles — alive.
        //
        // Trade-off (cosmetic): the preserved WindowImplAndroid keeps
        // the ALooper of whichever thread first constructed it
        // (the splash thread). When the game's render thread later calls
        // processEvents, ALooper_pollAll logs "No looper for this thread"
        // and returns -1. Not a crash; we don't use AInputQueue anyway
        // (events are injected via NativeSurface.inject*).
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
