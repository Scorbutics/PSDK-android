package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
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
        // GameActivity does the same for the `:game` process.
        //
        // Kept on as a defensive default so any code that destroys /
        // recreates a DisplayWindow mid-session (loader restarts on the
        // persistent interpreter) inherits the preserved shared window
        // instead of accidentally falling into owned-mode teardown.
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
     * Pick which activity a classic-mode user should be dispatched to. The state machine:
     *
     *   first launch / cleared data → STATE_NEEDS_SETUP → wizard (with
     *       classic-bootstrap so it auto-stages the bundled .epsa)
     *   wizard finished successfully → STATE_SETUP_COMPLETE → flip to
     *       STATE_READY here, then go straight into the game
     *   subsequent launches      → STATE_READY → straight into the game
     *
     * The game branches go straight to [GameActivity] (its own `:game`
     * process; no splash, no shell). When the game exits the task is
     * empty and the app closes, which is what a classic single-game APK
     * should do. EntryActivity's caller does the startActivity + finish.
     *
     * An APK upgrade (new versionCode) forces back to NEEDS_SETUP so the
     * fresh .epsa gets re-compiled.
     *
     * The launch mode (release vs debug-uncompiled) is baked into the APK
     * at export time via the [META_DATA_CLASSIC_DEFAULT_MODE] manifest tag.
     * Debug-mode APKs skip the compile pipeline entirely and run Game.rb
     * straight out of the bundled .epsa.
     */
    private fun classicTarget(): Intent? {
        val prefs = getSharedPreferences(ClassicModeState.PREFS, MODE_PRIVATE)
        // PackageInfo.longVersionCode is API 28; minSdk is 26.
        val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        val storedVersionCode = prefs.getLong(ClassicModeState.VERSION_CODE_KEY, -1L)
        if (storedVersionCode != currentVersionCode) {
            prefs.edit()
                .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_NEEDS_SETUP)
                .apply()
        }

        val defaultMode = classicDefaultMode()

        return when (prefs.getString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_NEEDS_SETUP)) {
            ClassicModeState.STATE_READY -> {
                GameActivity.intent(this, applicationInfo.dataDir, defaultMode, classicMode = true)
            }
            ClassicModeState.STATE_SETUP_COMPLETE -> {
                // First launch after a successful wizard run — flip to
                // READY for next time, then go straight into the game.
                prefs.edit()
                    .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_READY)
                    .apply()
                GameActivity.intent(this, applicationInfo.dataDir, defaultMode, classicMode = true)
            }
            else -> {
                // Wizard path always uses RELEASE — debug-uncompiled mode is
                // post-setup. The wizard's job is to produce Release/, which
                // only matters for the release path; debug-mode APKs skip
                // the wizard's compile step at the next branch above (the
                // wizard's compilationSuccess listener writes STATE_SETUP_COMPLETE
                // which lands us on the GameActivity branch with the
                // manifest-supplied mode).
                RootActivity.classicWizardIntent(this, applicationInfo.dataDir)
            }
        }
    }

    /**
     * Resolve the classic-mode default launch mode from the APK manifest.
     * `export_android.rb` injects a `<meta-data>` tag during APK assembly when
     * the maker passes `--debug-mode`. Absent the tag, default to RELEASE.
     *
     * Manifest excerpt the export plugin produces:
     *   <meta-data android:name="com.psdk.CLASSIC_DEFAULT_MODE"
     *              android:value="debug_uncompiled"/>
     */
    private fun classicDefaultMode(): GameMode {
        val raw = runCatching {
            packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            ).metaData?.getString(META_DATA_CLASSIC_DEFAULT_MODE)
        }.getOrNull()
        return GameMode.fromEnvValue(raw)
    }

    companion object {
        /**
         * AndroidManifest `<meta-data>` key. Honored only in classic-user mode.
         * Value is one of [GameMode.envValue]: "release" (default) or
         * "debug_uncompiled". Anything else falls through to release.
         */
        const val META_DATA_CLASSIC_DEFAULT_MODE = "com.psdk.CLASSIC_DEFAULT_MODE"
    }
}
