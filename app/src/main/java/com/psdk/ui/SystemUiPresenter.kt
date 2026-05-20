package com.psdk.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Encapsulates the per-mode window chrome decisions: immersive
 * fullscreen vs windowed-with-system-bars, keep-screen-on flag, and
 * the optional shell-content visibility toggle (hide fragment container
 * while the game is running, show it otherwise).
 *
 * Kept separate from the Activity so the window-flag fiddling lives in
 * one place — easier to evolve into different UI modes without
 * littering window.addFlags / WindowInsetsControllerCompat calls
 * across the rest of the codebase.
 *
 * The [shellContent] view is whatever the host uses as the
 * fragment-container / app-shell-UI overlay; pass null if the host
 * doesn't have a separate shell layer (e.g. a pure-game Activity).
 */
class SystemUiPresenter(
    private val window: Window,
    private val shellContent: View?,
) {

    private val controller: WindowInsetsControllerCompat by lazy {
        WindowInsetsControllerCompat(window, window.decorView)
    }

    /**
     * Game mode: immersive fullscreen, system bars hidden (swipe-to-show),
     * screen stays on, shell content hidden so the GL surface fills the
     * window without anything drawn on top.
     */
    fun enterGameMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        shellContent?.visibility = View.GONE
    }

    /**
     * Shell mode: system bars visible (the user can see status / nav),
     * screen-on flag dropped, shell content visible so fragments
     * (hub, wizard) appear above the GL surface.
     *
     * Used for the splash/loader phase where the surface is just a
     * decorative background under the app UI.
     */
    fun enterShellMode() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.show(WindowInsetsCompat.Type.systemBars())
        shellContent?.visibility = View.VISIBLE
    }
}
