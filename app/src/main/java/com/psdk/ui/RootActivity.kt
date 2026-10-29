package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogMessage

/**
 * Shell Activity: hub, wizard and splash all live here, in the main
 * process. The game itself runs in [GameActivity], in a separate `:game`
 * process that dies with the session — see [startGame].
 *
 * Composition root for:
 *   - [RubyWorkloadController] — loader script lifecycle
 *   - [SystemUiPresenter]      — window chrome (shell mode)
 *
 * Fragments (hub, wizard) interact with the host through the
 * [LoaderHost] contract; they never touch the controller or components
 * directly.
 *
 * The persistent interpreter keeps the surface, the EGL context, and the
 * render thread alive across loader restarts; the only thing that swaps
 * is which loader script the controller is running.
 */
class RootActivity : AppCompatActivity(), LoaderHost {

    private lateinit var glSurface: LiteRgssSurfaceView
    private lateinit var glSurfaceOverlay: View
    private lateinit var fragmentContainer: View

    private lateinit var workloadController: RubyWorkloadController
    private lateinit var systemUi: SystemUiPresenter

    /**
     * Fires when [GameActivity] finishes — whatever the exit path, and even
     * if the `:game` process died — so the splash comes back with a fresh
     * scene. Unconditional: [startGame] stopped the loader, so it is either
     * gone or still unwinding (fast game exit), and the controller queues
     * the restart behind the unwind in that case. Delivered before the
     * fragments' onResume, whose setLoaderState then sees the loader queued
     * and no-ops. Also delivered before the SurfaceView re-attaches, so the
     * restart stays pending until onSurfaceReady (see
     * [RubyWorkloadController.onSurfaceLost]).
     */
    private val gameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val exitCode = result.data?.getIntExtra(GameActivity.RESULT_EXIT_CODE, -1)
        android.util.Log.i(TAG, "game activity returned (result=${result.resultCode}, exit=$exitCode)")
        gameLaunchInFlight = false
        reportAbnormalGameExit(result.resultCode, exitCode)
        startLoaderForCurrentState()
    }

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
        glSurfaceOverlay = findViewById(R.id.glSurfaceOverlay)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        // Hand the host Activity to the native layer so SFML's hosted-mode
        // JNI paths (`Sf::Keyboard.virtual_keyboard=` lives here) have a
        // Context + Window to operate on. Cleared in onDestroy to release
        // the JNI global ref. LiteCGSS caches the Activity and re-applies
        // it across attach/detach surface cycles, so this single call is
        // enough for the Activity's whole lifetime.
        NativeSurface.setHostActivity(this)

        // Same process-wide setup as EntryActivity. When Android restores a
        // process it killed in the background, it recreates this activity
        // directly and EntryActivity never runs: without these the runtime
        // library name is unset and the loader falls back to owned windows.
        // Both calls are idempotent.
        PsdkInterpreter.initialize()
        NativeSurface.setReuseSharedWindow(true)

        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // The splash draws under the bars and reads them at start
            // (startLoaderForCurrentState): restart it when they move, as on
            // a resize (they may arrive after the resize's own restart).
            if (bars != systemBarInsets) {
                systemBarInsets = bars
                if (::glSurface.isInitialized && ::workloadController.isInitialized &&
                    workloadController.currentWorkload() is RubyWorkload.Loader) {
                    glSurface.removeCallbacks(restartLoaderAfterResize)
                    glSurface.postDelayed(restartLoaderAfterResize, LOADER_RESIZE_RESTART_DELAY_MS)
                }
            }
            insets
        }

        // SystemUI: shell mode for the whole lifetime (status/nav visible,
        // fragment container drawn, orientation free). Game mode belongs to
        // GameActivity.
        systemUi = SystemUiPresenter(this, shellContent = fragmentContainer)
        systemUi.enterShellMode()

        workloadController = RubyWorkloadController(
            context = this,
            assets = assets,
            onLog = { logMessage -> handleWorkloadLog(logMessage) },
            onError = { e -> android.util.Log.e(TAG, "workload error", e) },
            onWorkloadCompleted = { workload, exitCode ->
                android.util.Log.i(TAG, "workload ${workload.name} exited (code=$exitCode)")
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

        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(TAG, "surface ready (${w}x${h})")
            firstSurfaceReadyDone = true
            // Dispatches any workload that setLoaderState (or another
            // path) queued BEFORE the surface was ready.
            workloadController.onSurfaceReady()

            // Only fall back to "start the default loader" if nothing was
            // queued AND nothing is already running. Unconditionally
            // re-launching here caused a double-launch (queued-from-fragment
            // + explicit-from-onSurfaceReady) at startup — symptom was a
            // "tearing down loader before loader" race within ~16ms of the
            // surface coming up, leaving the second loader's render thread
            // looping on EGL_BAD_ACCESS because the first loader's render
            // thread still held the EGL context.
            if (workloadController.currentWorkload() == null) {
                startLoaderForCurrentState()
            }
        }
        glSurface.onSurfaceLost = {
            workloadController.onSurfaceLost()
        }
        // The splash lays its canvas out once, from the surface size at
        // start: after a rotation, fold or multi-window resize it would stay
        // stretched. Restart it so it rebuilds for the new shape. Debounced:
        // dragging a split-screen divider fires many resizes in a row.
        glSurface.onSurfaceResized = { w, h ->
            android.util.Log.i(TAG, "surface resized (${w}x${h})")
            glSurface.removeCallbacks(restartLoaderAfterResize)
            glSurface.postDelayed(restartLoaderAfterResize, LOADER_RESIZE_RESTART_DELAY_MS)
        }

        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_HUB
        // Cache once: the intent doesn't change on rotation, but
        // computing this before the savedInstanceState early return
        // keeps the value populated on restored instances too.
        isClassicMode = target == TARGET_CLASSIC_WIZARD

        if (savedInstanceState != null) return

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
     * True iff this activity was launched for the classic-user wizard (one
     * project baked into the APK, no hub). Forwarded to [GameActivity],
     * where it disables the back-to-quit-game UX.
     */
    private var isClassicMode: Boolean = false

    /**
     * Dispatcher for log lines coming out of the loader's Ruby thread:
     * sentinel watching for the splash fade-in and icon-tap callback.
     */
    /**
     * Synthesize a single ACTION_HOVER_MOVE that travels through the
     * input dispatcher and reaches the SurfaceView's layer-visibility
     * revalidation path — the same path a real touch would trigger.
     * Used to work around the MIUI / HyperOS "SurfaceView blank until
     * input arrives" quirk; see the call site in [handleWorkloadLog]
     * for the symptom description.
     *
     * Hover events are NOT consumed by [LiteRgssSurfaceView.onTouchEvent]
     * (it only handles ACTION_DOWN/UP/MOVE/POINTER_*CANCEL), so this
     * does not produce a spurious icon-tap in the splash.
     */
    private fun wakeCompositor() {
        val now = android.os.SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_HOVER_MOVE,
            0f, 0f, 0
        )
        ev.source = android.view.InputDevice.SOURCE_CLASS_POINTER
        try {
            glSurface.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun handleWorkloadLog(logMessage: LogMessage) {
        // TEMP diagnostic: confirm the LogListener pipeline is actually
        // delivering loader-side messages here. If the user's repeated
        // taps fire ICON_TAPPED on the Ruby side but we never see this
        // log line, that proves the native-side stdout-to-logcat path is
        // independent of our LogListener, and the listener isn't wired
        // to the persistent interpreter's output. Logged only for the
        // tap sentinel to avoid logspam from the frame-per-second feed.
        if (logMessage.message.contains(LOADER_ICON_TAPPED_SENTINEL)) {
            android.util.Log.i(TAG, "handleWorkloadLog: ICON_TAPPED entry " +
                "(currentWorkload=${workloadController.currentWorkload()?.name})")
        }
        if (workloadController.currentWorkload() !is RubyWorkload.Loader) return

        if (logMessage.message.contains(LOADER_READY_SENTINEL)) {
            glSurface.post {
                if (!loaderFadedIn) {
                    loaderFadedIn = true
                    glSurfaceOverlay.animate()
                        .alpha(0f)
                        .setDuration(LOADER_FADE_IN_MS)
                        .start()
                }
                // The previous (requestLayout + invalidate) pair unstuck
                // the compositor's buffer queue (no more QueueBuffer
                // timeouts), but on MIUI / HyperOS that alone isn't
                // enough to make the SurfaceView's *layer* actually
                // visible — buffers are pulled but the layer stays
                // transparent until a real input event arrives. (Symptom
                // log: "MirrorManager this model don't Support" appears
                // on first touch — that's MIUI's smart-view check
                // piggybacking on the same input-arrival path the
                // compositor is waiting on, which is why the timing of
                // that warning correlates perfectly with the splash
                // becoming visible.)
                //
                // Synthesize an ACTION_HOVER_MOVE: it travels through
                // dispatchGenericMotionEvent, hits the input dispatcher,
                // and triggers the same visibility-revalidation pass a
                // real touch would — but it is NOT consumed by
                // LiteRgssSurfaceView.onTouchEvent (which only handles
                // ACTION_DOWN/UP/MOVE/POINTER_*/CANCEL), so it won't
                // accidentally fire an icon-tap in the splash.
                (glSurface.parent as? View)?.requestLayout()
                glSurface.invalidate()
                wakeCompositor()
                loaderListener?.onLoaderReady()
            }
        }
        if (logMessage.message.contains(LOADER_ICON_TAPPED_SENTINEL)) {
            // Diagnostic: surface whether the dispatch reaches a non-null
            // listener. "tap log appears but nothing happens" reports
            // routinely reduce to "listener was null at dispatch time"
            // (fragment view destroyed in the gap between the loader's
            // emit and glSurface.post running on the UI thread).
            glSurface.post {
                val listener = loaderListener
                android.util.Log.i(TAG, "ICON_TAPPED dispatch: listener=${listener?.javaClass?.simpleName}")
                listener?.onIconTapped()
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
            is LoaderState.Preparing -> {
                LoaderPaths.resetToDefaults(this)
                LoaderPaths.writePreparingMessage(this, state.message)
            }
            is LoaderState.FromExtracted -> LoaderPaths.replaceAssetsWith(this, state.sourceDir)
            is LoaderState.FromArchive -> {
                // No prep: the loader's own Ruby thread will run [state.prelude]
                // (typically extract_loader_assets.rb) before loader.rb proper.
            }
        }
        if (state !is LoaderState.Preparing) LoaderPaths.clearPreparingMessage(this)
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
        // Each new loader workload re-enters the fade-in path: the
        // previous loader's surface content was disposed in
        // setLoaderState's restart cycle, but the surface itself was
        // reused (DisplayWindow.cpp:143 "Reusing existing window"), so
        // until the next [loader-ready] re-establishes content the
        // SurfaceView is showing stale/blank pixels.
        //
        // Resetting `loaderFadedIn` alone is NOT enough — if a previous
        // run already animated alpha to 1, the `.alpha(1f)` call in the
        // [loader-ready] handler is a no-op animation and the surface
        // compositor never gets a state change to react to. The render
        // thread happily queues frames; the compositor (with no fresh
        // signal that this layer is "interesting") leaves them buffered
        // until something — typically a user touch — wakes the
        // view-hierarchy invalidation path. Until then the screen looks
        // blank AND we see QueueBuffer timeouts piling up (the producer
        // is outrunning a sleeping consumer). Forcing alpha back to 0
        // here gives the subsequent fade animator a real 0→1 transition
        // to perform, which both visibly cross-fades the new loader in
        // AND kicks the compositor into pulling buffers again.
        loaderFadedIn = false
        glSurfaceOverlay.alpha = 1f
        // Read by loader/layers/text.rb: keeps the footer clear of the bars
        val bars = systemBarInsets
        android.system.Os.setenv(LOADER_INSETS_ENV, "${bars.left},${bars.top},${bars.right},${bars.bottom}", true)
        workloadController.start(
            RubyWorkload.Loader(
                executionLocation = LoaderPaths.executionLocation(this),
                archive = archive,
                preludes = LOADER_PRELUDES + prelude,
            )
        )
    }

    /**
     * True from [startGame] until [gameLauncher] reports the game finished.
     * The splash emits one icon-tap sentinel per release, and taps already
     * queued ahead of the loader's Closed event still reach us after the
     * first launch: without this guard a double tap started two
     * GameActivity instances in the same `:game` process. UI thread only.
     */
    private var gameLaunchInFlight = false

    /** Set by [startGame]; used to find the error report of that session. */
    private var gameStartedAtMs = 0L
    private var gameExecutionLocation: String? = null

    /**
     * The game ending by itself returns RESULT_OK with exit code 0. A Ruby
     * error that unwound returns a non-zero code; a crash kills the `:game`
     * process before it can set a result. Either way, tell the user why
     * instead of silently landing back on the splash: PSDK writes the
     * reason to Error.log in the game's working directory (Release/ for
     * release builds, the execution location for debug).
     */
    private fun reportAbnormalGameExit(resultCode: Int, exitCode: Int?) {
        val crashed = resultCode != RESULT_OK
        if (!crashed && (exitCode == null || exitCode == 0)) return
        val location = gameExecutionLocation ?: return

        val errorLog = listOf(java.io.File(location, "Release/Error.log"), java.io.File(location, "Error.log"))
            // 2 s slack: file mtimes can be coarser than the clock.
            .filter { it.isFile && it.lastModified() >= gameStartedAtMs - 2_000 }
            .maxByOrNull { it.lastModified() }
        val details = errorLog?.let { runCatching { it.readText() }.getOrNull() }?.takeIf { it.isNotBlank() }
        val summary = details?.let(::summarizeErrorLog)
            ?: if (crashed) "The game closed unexpectedly and did not write an error report."
               else "The game stopped with an error (exit code $exitCode)."

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Psdk_Dialog)
            .setTitle("The game stopped")
            .setMessage(summary)
            .setPositiveButton(android.R.string.ok, null)
        if (details != null) {
            dialog.setNeutralButton("Details") { _, _ ->
                LogBottomSheetFragment.newInstanceWithContent(details, title = "Game error")
                    .show(supportFragmentManager, "game_error")
            }
        }
        dialog.show()
    }

    /**
     * "Type: message" from a PSDK Error.log. Handles both layouts PSDK
     * writes: "Error type : X" / "Error message : Y" on one line each, and
     * the in-game report with "Message :" followed by the text on the next
     * line. Falls back to the first meaningful lines.
     */
    private fun summarizeErrorLog(log: String): String {
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

    /** Last system bar insets, in window pixels. UI thread only. */
    private var systemBarInsets = androidx.core.graphics.Insets.NONE

    /** See [setLoaderRestartsDeferred]. UI thread only. */
    private var loaderRestartsDeferred = false
    private var resizeRestartPending = false

    /** See glSurface.onSurfaceResized. Only the splash is restarted, never a game. */
    private val restartLoaderAfterResize = Runnable {
        if (loaderRestartsDeferred) {
            resizeRestartPending = true
        } else if (workloadController.currentWorkload() is RubyWorkload.Loader) {
            startLoaderForCurrentState()
        }
    }

    override fun setLoaderRestartsDeferred(deferred: Boolean) {
        if (loaderRestartsDeferred == deferred) return
        loaderRestartsDeferred = deferred
        if (!deferred && resizeRestartPending) {
            resizeRestartPending = false
            glSurface.post(restartLoaderAfterResize)
        }
    }

    override fun startGame(executionLocation: String, mode: GameMode) {
        if (gameLaunchInFlight) {
            android.util.Log.i(TAG, "startGame: a game launch is already in flight, ignoring")
            return
        }
        gameLaunchInFlight = true
        gameStartedAtMs = System.currentTimeMillis()
        gameExecutionLocation = executionLocation
        // The game runs in GameActivity's own `:game` process, so it always
        // starts on a fresh Ruby VM and its state is thrown away with the
        // process on exit. Stop the loader first: the shell surface is
        // about to be hidden, and there's no point keeping it ticking
        // behind the game. [gameLauncher]'s result callback restarts it on
        // return.
        //
        // With no script pumping events, the shell surface's detach and
        // re-attach reach SFML back-to-back (pending EGL-surface destroy
        // cancelled). That relies on EglContext::createSurface unbinding
        // the stale surface through setActive(false) — SFML-opengl-es2
        // 4c47f38. Older runtimes left GlContext's current-context
        // tracking stale here and the next loader drew black.
        workloadController.stop()
        gameLauncher.launch(GameActivity.intent(this, executionLocation, mode, isClassicMode))
    }

    // onPause/onResume intentionally NOT overridden to push LostFocus /
    // GainedFocus into the native event queue. SurfaceHolder.Callback
    // already owns that lifecycle: surfaceDestroyed → NativeSurface.detach
    // (which pushes LostFocus inside HostSurface.cpp:84-86 so SFML flips
    // m_windowBeingDestroyed and tears down the EGL surface on the next
    // processEvents) and surfaceChanged → NativeSurface.attach (which
    // pushes GainedFocus inside HostSurface.cpp:57-59 to drive surface
    // re-creation). A second push from here resulted in SFML processing
    // two LostFocus events back-to-back — the first destroyed the surface
    // cleanly, the second called eglDestroySurface against EGL_NO_SURFACE
    // and returned EGL_BAD_SURFACE, killing the running workload. Mirror
    // symptom on resume with two GainedFocus events. GameActivity follows
    // the same rule.

    override fun onDestroy() {
        if (::glSurface.isInitialized) glSurface.removeCallbacks(restartLoaderAfterResize)
        // Terminal teardown: shutdown() asks the running script to close
        // and destroys the persistent interpreter on a background thread
        // once the script returns (never blocking here). The persistent
        // interpreter survived every loader restart for the Activity's
        // lifetime; this is the one place we actually tear it down.
        if (::workloadController.isInitialized) {
            workloadController.shutdown()
        } else {
            NativeSurface.requestClose()
        }
        // Drop the JNI global ref to this Activity that we registered in
        // onCreate. The script may still be unwinding: a late
        // virtual-keyboard call from Ruby then finds no activity and is a
        // no-op (SFML InputImpl checks hostActivity before using it).
        NativeSurface.setHostActivity(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TARGET = "TARGET"
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        const val TARGET_HUB = "HUB"
        const val TARGET_CLASSIC_WIZARD = "CLASSIC_WIZARD"

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

        /** Matches Loader::Splash::READY_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_READY_SENTINEL = "[loader-ready]"

        /** Matches Loader::Splash::ICON_TAPPED_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_ICON_TAPPED_SENTINEL = "[loader-icon-tapped]"

        private const val LOADER_FADE_IN_MS = 80L

        private const val LOADER_RESIZE_RESTART_DELAY_MS = 300L
        /** System bar insets for the splash, see loader/layers/text.rb's INSETS_ENV. */
        private const val LOADER_INSETS_ENV = "PSDK_LOADER_INSETS"

        fun hubIntent(activity: AppCompatActivity): Intent =
            Intent(activity, RootActivity::class.java).putExtra(EXTRA_TARGET, TARGET_HUB)

        fun classicWizardIntent(activity: AppCompatActivity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_CLASSIC_WIZARD)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)

    }
}
