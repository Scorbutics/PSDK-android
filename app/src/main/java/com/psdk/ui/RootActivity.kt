package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.psdk.R
import com.psdk.ruby.vm.RemoteListenerConfig
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogMessage
import java.io.FileWriter

/**
 * Single Activity for the whole app lifetime: hub, wizard, splash, and
 * the game itself all live here.
 *
 * Composition root for:
 *   - [RubyWorkloadController] — Ruby script lifecycle (loader vs game)
 *   - [SystemUiPresenter]      — window chrome (shell vs game mode)
 *   - [GameInputForwarder]     — hardware keys + gamepad (only active
 *                                while the game workload is running)
 *   - [ImeBridge]              — soft-keyboard bridge (idem)
 *
 * Fragments (hub, wizard) interact with the host through the
 * [LoaderHost] contract; they never touch the controller or components
 * directly.
 *
 * Why one Activity: collapsing what used to be `RootActivity` (splash)
 * and `GameActivity` (game) into a single host removes an entire class
 * of Android-lifecycle vs SurfaceView-lifecycle vs Ruby-VM-lifecycle
 * races we used to have to paper over (see SFML's `forwardEvent`
 * flag-cancellation, the `releaseHostedSurfaceNow` helper, and the
 * `setReuseSharedWindow` shared-context preservation flag — all
 * artefacts of the two-activity model). The single Activity keeps the
 * surface, the EGL context, and the render thread alive across the
 * splash→game transition; the only thing that swaps is which Ruby
 * script the controller is running.
 */
class RootActivity : AppCompatActivity(), LoaderHost {

    private lateinit var glSurface: LiteRgssSurfaceView
    private lateinit var imeSink: EditText
    private lateinit var fragmentContainer: View

    private lateinit var workloadController: RubyWorkloadController
    private lateinit var systemUi: SystemUiPresenter
    private lateinit var inputForwarder: GameInputForwarder
    private lateinit var imeBridge: ImeBridge

    /** Tracked under [stateLock]; the source-of-truth for the splash. */
    private val stateLock = Any()
    private var currentLoaderState: LoaderState = LoaderState.Default

    /** Latched true on the first READY_SENTINEL so subsequent restarts don't re-fade. */
    private var loaderFadedIn = false

    /**
     * Latched true on the first onSurfaceReady so we don't fire a
     * spurious GainedFocus on the very first onResume (before the
     * surface is bound).
     */
    private var firstSurfaceReadyDone = false

    private var loaderListener: LoaderListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw the GL surface edge-to-edge so the background fills the whole
        // screen, including behind the status and navigation bars. Fragment
        // content stays inside the system bars via insets applied below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root)

        glSurface = findViewById(R.id.glSurface)
        imeSink = findViewById(R.id.imeSink)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // SystemUI: shell mode by default (status/nav visible, fragment
        // container drawn). Game mode is entered explicitly in startGame.
        systemUi = SystemUiPresenter(window, shellContent = fragmentContainer)
        systemUi.enterShellMode()

        imeBridge = ImeBridge(this, imeSink, focusRestoreTarget = glSurface)
        inputForwarder = GameInputForwarder(this).apply {
            keyEventShouldConsume = { _ -> !imeBridge.isImeActive }
        }

        workloadController = RubyWorkloadController(
            context = this,
            assets = assets,
            onLog = { logMessage -> handleWorkloadLog(logMessage) },
            onError = { e -> android.util.Log.e(TAG, "workload error", e) },
            onWorkloadCompleted = { workload, exitCode ->
                handleWorkloadCompleted(workload, exitCode)
            },
        )

        // Always reset assets back to PSDK defaults at activity start.
        // A prior session may have copied a project's loader_assets into
        // this dir; without the reset the hub would render the previous
        // project's logo / title. Each fragment is expected to declare
        // its desired state via [setLoaderState] on resume / view-created
        // so the reset doesn't conflict with whichever fragment lands here.
        LoaderPaths.resetToDefaults(this)

        // Cold-start wipe of the status file so a stale "<<LIFT>>" from
        // last run doesn't immediately fire the lift on the new splash.
        // Skip on rotation (savedInstanceState != null) — the wizard may
        // have written a real status the splash is mid-displaying.
        if (savedInstanceState == null) {
            try {
                LoaderPaths.statusFile(this).delete()
            } catch (_: Exception) {
                // best-effort
            }
        }

        glSurface.alpha = 0f
        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(TAG, "surface ready (${w}x${h})")
            firstSurfaceReadyDone = true
            workloadController.onSurfaceReady()
            val directExecLoc = directGamePendingExecLocation
            if (directExecLoc != null) {
                directGamePendingExecLocation = null
                startGame(directExecLoc)
            } else {
                // Initial workload: whatever currentLoaderState says.
                startLoaderForCurrentState()
            }
        }

        if (savedInstanceState != null) return

        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_HUB

        // Direct-to-game path used by EntryActivity for classic-mode users
        // whose Release/ dir is already on disk. No fragment, no splash —
        // the game starts as soon as the surface comes up.
        if (target == TARGET_DIRECT_GAME) {
            val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
            // Defer until the surface is ready. The onSurfaceReady callback
            // installed above unconditionally starts the loader; suppress
            // that by setting a flag the callback consults.
            directGamePendingExecLocation = executionLocation
            return
        }

        val fragment = when (target) {
            TARGET_HUB -> ProjectHubFragment()
            TARGET_CLASSIC_WIZARD -> CompileWizardFragment.newInstance(
                executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish(),
                classicBootstrap = true,
            )
            else -> return finish()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Non-null iff this activity was started with TARGET_DIRECT_GAME and
     * the surface hasn't come up yet. Once non-null, the surface-ready
     * callback skips the loader and goes straight to startGame.
     */
    private var directGamePendingExecLocation: String? = null

    /**
     * Dispatcher for log lines coming out of the workload's Ruby
     * thread. Loader workloads need sentinel watching (for the splash
     * fade-in and icon-tap callback); game workloads don't.
     */
    private fun handleWorkloadLog(logMessage: LogMessage) {
        android.util.Log.i(TAG, logMessage.message)
        val workload = workloadController.currentWorkload() ?: return
        if (workload !is RubyWorkload.Loader) return

        if (logMessage.message.contains(LOADER_READY_SENTINEL)) {
            glSurface.post {
                if (!loaderFadedIn) {
                    loaderFadedIn = true
                    glSurface.animate()
                        .alpha(1f)
                        .setDuration(LOADER_FADE_IN_MS)
                        .start()
                }
                loaderListener?.onLoaderReady()
            }
        }
        if (logMessage.message.contains(LOADER_ICON_TAPPED_SENTINEL)) {
            glSurface.post { loaderListener?.onIconTapped() }
        }
    }

    /**
     * Workload-exit handler. A game workload exiting means the user
     * left the game (or the script terminated); we drop game-mode
     * input/IME forwarding and return to shell mode so the user is
     * back in the hub/wizard fragment they came from.
     */
    private fun handleWorkloadCompleted(workload: RubyWorkload, exitCode: Int) {
        android.util.Log.i(TAG, "workload ${workload.name} exited (code=$exitCode)")
        if (workload is RubyWorkload.Game) {
            runOnUiThread {
                inputForwarder.stop()
                imeBridge.stop()
                systemUi.enterShellMode()
                // Restart the loader so the surface isn't blank when
                // the user returns to the hub/wizard fragment.
                startLoaderForCurrentState()
            }
        }
    }

    override fun setLoaderListener(listener: LoaderListener?) {
        loaderListener = listener
    }

    override fun setLoaderState(state: LoaderState) {
        synchronized(stateLock) {
            if (state == currentLoaderState &&
                workloadController.currentWorkload() is RubyWorkload.Loader) return
            android.util.Log.i(TAG, "setLoaderState (state=$state)")
            currentLoaderState = state
            prepareAssetsFor(state)
            startLoaderForCurrentState()
        }
    }

    private fun prepareAssetsFor(state: LoaderState) {
        when (state) {
            LoaderState.Default -> LoaderPaths.resetToDefaults(this)
            is LoaderState.FromExtracted -> LoaderPaths.replaceAssetsWith(this, state.sourceDir)
            is LoaderState.FromArchive -> {
                // No prep: the loader's own Ruby thread will run [state.prelude]
                // (typically extract_loader_assets.rb) before loader.rb proper.
            }
        }
        // Clear stale status so a new loader run doesn't immediately fire
        // a sentinel left by the previous one.
        try {
            LoaderPaths.statusFile(this).delete()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun startLoaderForCurrentState() {
        val state = currentLoaderState
        val (archive, prelude) = when (state) {
            is LoaderState.FromArchive -> state.archive to state.prelude
            else -> null to emptyList()
        }
        workloadController.start(
            RubyWorkload.Loader(
                executionLocation = LoaderPaths.executionLocation(this),
                archive = archive,
                preludes = LOADER_PRELUDES + prelude,
            )
        )
    }

    override fun shutdownLoader() {
        // Kept for back-compat with any caller that pre-dates Phase D;
        // new code should use [startGame] which handles this internally.
        // Stops the current workload without queueing a successor.
        workloadController.stop()
    }

    override fun startGame(executionLocation: String) {
        // In-process workload swap. The controller's start() will tear
        // down the loader (if any), and then enqueue start.rb on a fresh
        // PsdkInterpreter against the same surface + EGL context the
        // loader was using.
        configureGameEnv(executionLocation)
        systemUi.enterGameMode()
        glSurface.alpha = 1f
        inputForwarder.start()
        imeBridge.start()
        workloadController.start(
            RubyWorkload.Game(
                executionLocation = executionLocation,
                preludes = START_PRELUDES,
            )
        )
    }

    /**
     * Populate the env vars start.rb reads (RGSS_EXECUTION_LOCATION,
     * RGSS_LOG_FILE, RGSS_ERROR_LOG_FILE, RGSS_REMOTE_*_TOKEN, etc.).
     * Called from [startGame] right before the controller enqueues
     * the game workload.
     */
    private fun configureGameEnv(executionLocation: String) {
        val releaseLocation = "$executionLocation/Release"
        android.system.Os.setenv("RGSS_EXECUTION_LOCATION", executionLocation, true)
        android.system.Os.setenv("RGSS_LOG_FILE", "$executionLocation/last_stdout.log", true)
        android.system.Os.setenv("RGSS_ERROR_LOG_FILE", "$releaseLocation/Error.log", true)

        try {
            FileWriter("$executionLocation/last_stdout.log", false).flush()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to reset stdout log: ${e.message}")
        }

        if (RemoteListenerConfig.enabled) {
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_HOST",    RemoteListenerConfig.DEBUG_HOST,           true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_PORT",    RemoteListenerConfig.DEBUG_PORT.toString(), true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_TOKEN",   RemoteListenerConfig.TOKEN,                true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_SESSION", "psdk-game-debug",                          true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_HOST",     RemoteListenerConfig.EVAL_HOST,            true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_PORT",     RemoteListenerConfig.EVAL_PORT.toString(),  true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_TOKEN",    RemoteListenerConfig.TOKEN,                true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_SESSION",  "psdk-game-eval",                           true)
        }
    }

    override fun onResume() {
        super.onResume()
        // PSDK's Graphics.@has_focus is gated on GainedFocus — without
        // this, every Input.* query returns false after a pause cycle.
        // Only fire after the surface has come up at least once
        // (otherwise forwardEvent reads ANativeWindow_getWidth from a
        // NULL window pointer).
        if (firstSurfaceReadyDone && workloadController.currentWorkload() is RubyWorkload.Game) {
            NativeSurface.injectFocusGained()
        }
    }

    override fun onPause() {
        if (firstSurfaceReadyDone && workloadController.currentWorkload() is RubyWorkload.Game) {
            NativeSurface.injectFocusLost()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::inputForwarder.isInitialized) inputForwarder.stop()
        if (::imeBridge.isInitialized) imeBridge.stop()
        if (::workloadController.isInitialized) {
            workloadController.stop()
        } else {
            NativeSurface.requestClose()
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // inputForwarder gates internally on its enabled flag, so this
        // is a no-op while a non-game workload is active.
        if (::inputForwarder.isInitialized && inputForwarder.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::inputForwarder.isInitialized && inputForwarder.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        const val EXTRA_TARGET = "TARGET"
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        const val TARGET_HUB = "HUB"
        const val TARGET_CLASSIC_WIZARD = "CLASSIC_WIZARD"
        const val TARGET_DIRECT_GAME = "DIRECT_GAME"

        private const val TAG = "RootActivity"

        /**
         * Helper modules concatenated before loader.rb itself. Each file
         * defines its piece of the Loader::* namespace; loader.rb is the
         * top-level orchestrator that instantiates Loader::Splash.
         *
         * Order is the dependency graph — every file may reference any
         * Loader::* defined earlier in this list at method-call time,
         * but class-body code only references things defined strictly
         * before it. Don't reorder casually.
         */
        private val LOADER_PRELUDES = listOf(
            "loader/util.rb",
            "loader/clock.rb",
            "loader/scene_state.rb",
            "loader/assets.rb",
            "loader/splash_window.rb",
            "loader/touch_router.rb",
            "loader/status_file.rb",
            "loader/layers/background.rb",
            "loader/layers/ambient_particles.rb",
            "loader/layers/dust.rb",
            "loader/layers/icon.rb",
            "loader/layers/text.rb",
            "loader/splash.rb",
        )

        /** Helper modules concatenated before start.rb. Same convention as LOADER_PRELUDES. */
        private val START_PRELUDES = listOf(
            "psdk_vm_snapshot.rb",
        )

        /** Matches Loader::Splash::READY_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_READY_SENTINEL = "[loader-ready]"

        /** Matches Loader::Splash::ICON_TAPPED_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_ICON_TAPPED_SENTINEL = "[loader-icon-tapped]"

        private const val LOADER_FADE_IN_MS = 180L

        fun hubIntent(activity: AppCompatActivity): Intent =
            Intent(activity, RootActivity::class.java).putExtra(EXTRA_TARGET, TARGET_HUB)

        fun classicWizardIntent(activity: AppCompatActivity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_CLASSIC_WIZARD)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)

        fun directGameIntent(activity: android.app.Activity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_DIRECT_GAME)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
    }
}
