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
    private lateinit var glSurfaceOverlay: View
    private lateinit var gameStartOverlay: View
    private lateinit var gameStartIcon: android.widget.ImageView
    private lateinit var imeSink: EditText
    private lateinit var fragmentContainer: View

    /** Continuous rotation on [gameStartIcon] while the start overlay is up. */
    private var gameStartIconAnimator: android.animation.ObjectAnimator? = null

    /** Posted on [showGameStartOverlay]; cancelled on [hideGameStartOverlay]. */
    private val gameStartTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gameStartTimeoutRunnable = Runnable {
        android.util.Log.w(TAG, "game-start overlay timeout reached without sentinel — forcing hide")
        hideGameStartOverlay()
    }

    /** File [start.rb] writes when PSDK's first LiteRGSS::DisplayWindow.new returns. */
    private var gameReadyFile: java.io.File? = null
    private val gameReadyPollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gameReadyPollRunnable = object : Runnable {
        override fun run() {
            val f = gameReadyFile
            if (f != null && f.exists()) {
                android.util.Log.i(TAG, "game-ready file appeared at ${f.absolutePath} — hiding overlay")
                hideGameStartOverlay()
                return
            }
            gameReadyPollHandler.postDelayed(this, GAME_READY_POLL_INTERVAL_MS)
        }
    }

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

    /**
     * True iff this activity was launched via the classic-user flow
     * (TARGET_CLASSIC_WIZARD or TARGET_DIRECT_GAME) — i.e., one project
     * baked into the APK, no hub to fall back to. Gates the
     * back-to-quit-game UX in [dispatchKeyEvent]: in classic mode
     * there's no "project page" to return to, so BACK keeps being
     * consumed by [inputForwarder] as before.
     */
    private var isClassicMode: Boolean = false

    /** [SystemClock.uptimeMillis] of the most recent in-game BACK press, or 0 if armed/expired. */
    private var lastGameBackPressUptimeMs: Long = 0L

    /** Active "press again to quit" toast so the second press can dismiss it instantly. */
    private var gameBackToast: android.widget.Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw the GL surface edge-to-edge so the background fills the whole
        // screen, including behind the status and navigation bars. Fragment
        // content stays inside the system bars via insets applied below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root)

        glSurface = findViewById(R.id.glSurface)
        glSurfaceOverlay = findViewById(R.id.glSurfaceOverlay)
        gameStartOverlay = findViewById(R.id.gameStartOverlay)
        gameStartIcon = findViewById(R.id.gameStartIcon)
        imeSink = findViewById(R.id.imeSink)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        // Hand the host Activity to the native layer so SFML's hosted-mode
        // JNI paths (`Sf::Keyboard.virtual_keyboard=` lives here) have a
        // Context + Window to operate on. Cleared in onDestroy to release
        // the JNI global ref. LiteCGSS caches the Activity and re-applies
        // it across attach/detach surface cycles, so this single call is
        // enough for the Activity's whole lifetime.
        NativeSurface.setHostActivity(this)

        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // SystemUI: shell mode by default (status/nav visible, fragment
        // container drawn, orientation free). Game mode is entered
        // explicitly in startGame and locks orientation to landscape.
        systemUi = SystemUiPresenter(this, shellContent = fragmentContainer)
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

        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(TAG, "surface ready (${w}x${h})")
            firstSurfaceReadyDone = true
            // Dispatches any workload that setLoaderState (or another
            // path) queued BEFORE the surface was ready.
            workloadController.onSurfaceReady()

            val directExecLoc = directGamePendingExecLocation
            when {
                directExecLoc != null -> {
                    val mode = directGamePendingMode
                    directGamePendingExecLocation = null
                    directGamePendingMode = GameMode.RELEASE
                    startGame(directExecLoc, mode)
                }
                workloadController.currentWorkload() == null -> {
                    // Only fall back to "start the default loader" if
                    // nothing was queued AND nothing is already running.
                    // The previous code path unconditionally re-launched
                    // the loader here, which caused a double-launch
                    // (queued-from-fragment + explicit-from-onSurfaceReady)
                    // at startup — symptom was a "tearing down loader
                    // before loader" race within ~16ms of the surface
                    // coming up, leaving the second loader's render
                    // thread looping on EGL_BAD_ACCESS because the
                    // first loader's render thread still held the EGL
                    // context.
                    startLoaderForCurrentState()
                }
            }
        }

        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_HUB
        // Cache once: the intent doesn't change on rotation, but
        // computing this before the savedInstanceState early return
        // keeps the value populated on restored instances too.
        isClassicMode = (target == TARGET_DIRECT_GAME) || (target == TARGET_CLASSIC_WIZARD)

        if (savedInstanceState != null) return

        // Direct-to-game path used by EntryActivity for classic-mode users
        // whose Release/ dir is already on disk. No fragment, no splash —
        // the game starts as soon as the surface comes up.
        if (target == TARGET_DIRECT_GAME) {
            val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
            // Defer until the surface is ready. The onSurfaceReady callback
            // installed above unconditionally starts the loader; suppress
            // that by setting a flag the callback consults.
            directGamePendingExecLocation = executionLocation
            directGamePendingMode = GameMode.fromEnvValue(
                intent.getStringExtra(EXTRA_GAME_MODE)
            )
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

    /** Pairs with [directGamePendingExecLocation]; default RELEASE if no extra was set. */
    private var directGamePendingMode: GameMode = GameMode.RELEASE

    /**
     * Dispatcher for log lines coming out of the workload's Ruby
     * thread. Loader workloads need sentinel watching (for the splash
     * fade-in and icon-tap callback); game workloads don't.
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
        val workload = workloadController.currentWorkload() ?: return

        // Game workloads don't drive any UI state from log messages anymore.
        // The start-overlay-hide signal moved to a marker file the Ruby side
        // writes (see [gameReadyFile] + the polling Handler) because Ruby
        // logs from start.rb were not reaching this listener reliably on
        // some hosts.
        if (workload is RubyWorkload.Game) return
        if (workload !is RubyWorkload.Loader) return

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
                resetGameBackQuitState()
                systemUi.enterShellMode()
                // Belt-and-braces: if Game.rb died before constructing its
                // DisplayWindow (script error in start.rb's preludes, or in
                // Game.rb itself before LiteRGSS::DisplayWindow.new fires),
                // [GAME_DISPLAY_READY_SENTINEL] never reached the log
                // pipeline and the overlay would stay opaque forever.
                // Tearing it down here guarantees the user gets back to the
                // hub / wizard splash whatever path the game took.
                hideGameStartOverlay()
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
        workloadController.start(
            RubyWorkload.Loader(
                executionLocation = LoaderPaths.executionLocation(this),
                archive = archive,
                preludes = LOADER_PRELUDES + prelude,
            )
        )
    }

    override fun startGame(executionLocation: String, mode: GameMode) {
        // In-process workload swap on the SAME persistent interpreter
        // and render thread the loader is running on. The controller
        // pushes Closed to signal the loader's script to unwind; the
        // loader's ensure block disposes its DisplayWindow; the
        // controller then enqueues start.rb on the same interpreter.
        // Same surface, same EGL context, same render thread — no
        // handoff, no race.
        //
        // DEBUG_UNCOMPILED needs the archive keys bound for the start.rb
        // prelude to mount PhysFS. Resolve them off the staged .epsa via
        // the same path the wizard uses; if resolution fails, fall back
        // to RELEASE so we don't end up running with no Game.rb visible.
        val resolvedArchive = if (mode == GameMode.DEBUG_UNCOMPILED) {
            val keys = com.psdk.compilation.ArchiveValidator.resolveKeys(
                this,
                com.psdk.compilation.ArchiveValidator.getStagingFile(executionLocation),
            )
            if (keys == null) {
                android.util.Log.e(TAG, "DEBUG_UNCOMPILED requested but archive keys did not resolve; aborting")
                return
            }
            keys
        } else null

        configureGameEnv(executionLocation, mode)
        systemUi.enterGameMode()
        glSurfaceOverlay.alpha = 0f
        // Cover the surface with a spinning project icon for the duration
        // of the loader→game handoff. Without this, the loader's last
        // frame sits frozen on the GL surface while PSDK's require chain
        // runs (1–5s depending on mode + cold/warm), reading as "the app
        // stalled" to the user. The sentinel handler in handleWorkloadLog
        // (matched against GAME_DISPLAY_READY_SENTINEL) fades this out as
        // soon as Game.rb constructs its first LiteRGSS::DisplayWindow.
        showGameStartOverlay(executionLocation)
        inputForwarder.start()
        imeBridge.start()
        workloadController.start(
            RubyWorkload.Game(
                executionLocation = executionLocation,
                mode = mode,
                archive = resolvedArchive,
                preludes = if (mode == GameMode.DEBUG_UNCOMPILED) START_PRELUDES_DEBUG else START_PRELUDES,
            )
        )
    }

    /**
     * Open the start overlay: bind the project's icon, animate a continuous
     * rotation. Caller is responsible for triggering [hideGameStartOverlay]
     * later — typically on the [GAME_DISPLAY_READY_SENTINEL] sentinel from
     * start.rb, but also from [handleWorkloadCompleted] in case the game
     * exits without ever constructing a window (script error before PSDK
     * boots its display).
     */
    private fun showGameStartOverlay(executionLocation: String) {
        // Project icon. extract_loader_assets.rb writes game.png alongside
        // game_font.ttf and infos_config.json in <exec>/loader_assets/. If
        // it's not there (e.g. first debug-uncompiled run on a project
        // that's never been validated), fall back to the bundled logo so
        // the overlay still has something to spin.
        val projectIcon = java.io.File(executionLocation, "loader_assets/game.png")
        if (projectIcon.exists()) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(projectIcon.absolutePath)
            if (bitmap != null) {
                gameStartIcon.setImageBitmap(bitmap)
            } else {
                gameStartIcon.setImageResource(R.drawable.logo)
            }
        } else {
            gameStartIcon.setImageResource(R.drawable.logo)
        }

        gameStartOverlay.alpha = 1f
        gameStartOverlay.visibility = View.VISIBLE
        gameStartOverlay.bringToFront()  // sit above glSurfaceOverlay

        // Stop any prior animator (re-entry from a quick exit-and-retry).
        gameStartIconAnimator?.cancel()
        gameStartIcon.rotation = 0f
        gameStartIconAnimator = android.animation.ObjectAnimator.ofFloat(
            gameStartIcon, View.ROTATION, 0f, 360f
        ).apply {
            duration = GAME_START_ICON_SPIN_MS
            repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }

        // Defensive timeout. If start.rb's marker file never appears
        // (script error before DisplayWindow.new, the wrapper code didn't
        // catch the construction path for some reason, etc.) we still
        // reveal the GL surface so the user sees whatever the game IS
        // rendering. Long enough to cover a cold debug-uncompiled boot
        // (PSDK's script load on PhysFS is ~10–25s on a mid-range device);
        // the marker-file path normally pre-empts it.
        gameStartTimeoutHandler.removeCallbacks(gameStartTimeoutRunnable)
        gameStartTimeoutHandler.postDelayed(gameStartTimeoutRunnable, GAME_START_TIMEOUT_MS)

        // Wipe any stale marker file from a prior run and start polling.
        // The Ruby wrapper writes this once on first DisplayWindow.new;
        // the poller hops the result back to UI thread via [hideGameStartOverlay].
        val readyFile = gameReadyFile(executionLocation)
        try {
            if (readyFile.exists()) readyFile.delete()
        } catch (_: Exception) { /* best-effort */ }
        gameReadyFile = readyFile
        gameReadyPollHandler.removeCallbacks(gameReadyPollRunnable)
        gameReadyPollHandler.postDelayed(gameReadyPollRunnable, GAME_READY_POLL_INTERVAL_MS)
    }

    /**
     * Cross-fade the start overlay out (revealing Game.rb's first frame on
     * the GL surface), then stop the rotation animator + hide the View so
     * subsequent touches reach the surface instead of getting eaten by the
     * overlay's clickable=true.
     *
     * Safe to call from any thread (hops to UI internally) and safe to call
     * when the overlay isn't visible (idempotent — no animation queued).
     */
    private fun hideGameStartOverlay() {
        runOnUiThread {
            gameStartTimeoutHandler.removeCallbacks(gameStartTimeoutRunnable)
            gameReadyPollHandler.removeCallbacks(gameReadyPollRunnable)
            gameReadyFile = null
            if (gameStartOverlay.visibility != View.VISIBLE) return@runOnUiThread
            gameStartOverlay.animate()
                .alpha(0f)
                .setDuration(GAME_START_FADE_MS)
                .withEndAction {
                    gameStartOverlay.visibility = View.GONE
                    gameStartIconAnimator?.cancel()
                    gameStartIconAnimator = null
                }
                .start()
        }
    }

    /**
     * Marker file [start.rb]'s LiteRGSS::DisplayWindow.new wrapper writes
     * the moment PSDK's main window is constructed. Lives next to the
     * other per-session log files so a single rm -rf on the execution
     * dir cleans it up. The poller in [showGameStartOverlay] watches
     * this path; deletion is deferred to [hideGameStartOverlay] so a
     * subsequent start cycle starts from a clean slate.
     */
    private fun gameReadyFile(executionLocation: String): java.io.File =
        java.io.File(executionLocation, ".game_ready")

    /**
     * Populate the env vars start.rb reads (RGSS_EXECUTION_LOCATION,
     * RGSS_LOG_FILE, RGSS_ERROR_LOG_FILE, RGSS_GAME_MODE,
     * RGSS_GAME_READY_FILE, RGSS_REMOTE_*_TOKEN, etc.).
     * Called from [startGame] right before the controller enqueues
     * the game workload.
     */
    private fun configureGameEnv(executionLocation: String, mode: GameMode) {
        // The error log lives next to whichever Game.rb actually ran:
        //   RELEASE          → <exec>/Release/Error.log  (PSDK's traditional spot)
        //   DEBUG_UNCOMPILED → <exec>/Error.log         (we chdir to <exec>)
        val errorLogLocation = if (mode == GameMode.DEBUG_UNCOMPILED) {
            executionLocation
        } else {
            "$executionLocation/Release"
        }
        android.system.Os.setenv("RGSS_EXECUTION_LOCATION", executionLocation, true)
        android.system.Os.setenv("RGSS_LOG_FILE", "$executionLocation/last_stdout.log", true)
        android.system.Os.setenv("RGSS_ERROR_LOG_FILE", "$errorLogLocation/Error.log", true)
        android.system.Os.setenv("RGSS_GAME_MODE", mode.envValue(), true)
        android.system.Os.setenv("RGSS_GAME_READY_FILE", gameReadyFile(executionLocation).absolutePath, true)

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

    // onPause/onResume intentionally NOT overridden to push LostFocus /
    // GainedFocus into the native event queue. SurfaceHolder.Callback
    // already owns that lifecycle: surfaceDestroyed → NativeSurface.detach
    // (which pushes LostFocus inside HostSurface.cpp:84-86 so SFML flips
    // m_windowBeingDestroyed and tears down the EGL surface on the next
    // processEvents) and surfaceChanged → NativeSurface.attach (which
    // pushes GainedFocus inside HostSurface.cpp:57-59 to drive surface
    // re-creation). For loader workloads only that single path fires; for
    // game workloads, a second push from here resulted in SFML processing
    // two LostFocus events back-to-back — the first destroyed the surface
    // cleanly, the second called eglDestroySurface against EGL_NO_SURFACE
    // and returned EGL_BAD_SURFACE. The subsequent draw call then failed
    // and the game workload exited silently, dropping the user back at
    // the hub. Mirror symptom on resume with two GainedFocus events.
    //
    // Trade-off: partial-pause cases where the SurfaceView's surface
    // survives (incoming-call overlay, split-screen handoff that keeps
    // the surface alive) will NOT see a focus event under this design,
    // so PSDK's Graphics.@has_focus stays true and Input.* keeps
    // reporting taps. That's acceptable for the current single-player
    // game model — if we ever need pause-on-partial-focus, wire it via
    // a deduplication flag (e.g. track "surface still attached" in
    // LiteRgssSurfaceView and only push from here when that's true)
    // rather than re-introducing the unconditional double-push.

    override fun onDestroy() {
        if (::inputForwarder.isInitialized) inputForwarder.stop()
        if (::imeBridge.isInitialized) imeBridge.stop()
        // Terminal teardown: shutdown() destroys the persistent
        // interpreter (blocks briefly waiting for the render thread
        // to exit). The persistent interpreter survived every prior
        // workload swap for the Activity's lifetime; this is the one
        // place we actually tear it down.
        if (::workloadController.isInitialized) {
            workloadController.shutdown()
        } else {
            NativeSurface.requestClose()
        }
        // Drop the JNI global ref to this Activity that we registered in
        // onCreate. Must come after the workload has finished so any
        // late virtual-keyboard call from Ruby still finds a valid ref.
        NativeSurface.setHostActivity(null)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Super-user flow only: intercept BACK while a game workload is
        // running so the user can leave the game and return to the
        // project page. In classic mode there is no page to return to,
        // so BACK keeps being consumed by inputForwarder unchanged.
        if (event.keyCode == KeyEvent.KEYCODE_BACK
            && !isClassicMode
            && ::workloadController.isInitialized
            && workloadController.currentWorkload() is RubyWorkload.Game
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleGameBackPress()
            }
            return true
        }
        // inputForwarder gates internally on its enabled flag, so this
        // is a no-op while a non-game workload is active.
        if (::inputForwarder.isInitialized && inputForwarder.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Double-tap-to-quit handler for the in-game BACK key. First press
     * shows a transient toast and arms a timer; a second press within
     * [BACK_QUIT_WINDOW_MS] requests the game workload to stop, which
     * routes through [handleWorkloadCompleted] and lands the user back
     * on the hub/project page.
     */
    private fun handleGameBackPress() {
        val now = android.os.SystemClock.uptimeMillis()
        val armed = lastGameBackPressUptimeMs != 0L &&
            (now - lastGameBackPressUptimeMs) <= BACK_QUIT_WINDOW_MS
        if (armed) {
            gameBackToast?.cancel()
            gameBackToast = null
            lastGameBackPressUptimeMs = 0L
            workloadController.stop()
            return
        }
        lastGameBackPressUptimeMs = now
        gameBackToast?.cancel()
        gameBackToast = android.widget.Toast.makeText(
            this,
            "Press back again to quit the game (progress won't be saved)",
            android.widget.Toast.LENGTH_SHORT,
        ).also { it.show() }
    }

    private fun resetGameBackQuitState() {
        gameBackToast?.cancel()
        gameBackToast = null
        lastGameBackPressUptimeMs = 0L
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::inputForwarder.isInitialized && inputForwarder.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        const val EXTRA_TARGET = "TARGET"
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        /** Optional [GameMode] envValue ("release" | "debug_uncompiled"); only honored when [TARGET_DIRECT_GAME]. */
        const val EXTRA_GAME_MODE = "GAME_MODE"

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
            "psdk_vm_snapshot.rb",
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

        /** Helper modules concatenated before start.rb in RELEASE mode. */
        private val START_PRELUDES = listOf(
            "psdk_vm_snapshot.rb",
        )

        /**
         * Helper modules for DEBUG_UNCOMPILED mode. start.rb's debug branch
         * needs EpsaFormat / EpsaStream / ArchiveMount in scope to mount the
         * encrypted .epsa via PhysFS. All three are `unless defined?`-guarded
         * so re-eval across runs on the persistent VM is safe; the
         * PSDKVMSnapshot.restore! call in start.rb's ensure reaps them
         * between launches.
         *
         * psdk_vm_snapshot.rb MUST stay first — its auto-capture freezes the
         * baseline before any of the archive modules get added.
         */
        private val START_PRELUDES_DEBUG = listOf(
            "psdk_vm_snapshot.rb",
            "lib/epsa_format.rb",
            "lib/epsa_stream.rb",
            "lib/archive_mount.rb",
        )

        /** Matches Loader::Splash::READY_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_READY_SENTINEL = "[loader-ready]"

        /** Matches Loader::Splash::ICON_TAPPED_SENTINEL in loader/splash.rb verbatim. */
        private const val LOADER_ICON_TAPPED_SENTINEL = "[loader-icon-tapped]"

        private const val LOADER_FADE_IN_MS = 80L

        /** Poll interval for [gameReadyFile]. Cheap stat call, 100ms feels instant. */
        private const val GAME_READY_POLL_INTERVAL_MS = 100L

        /**
         * Window inside which a second BACK press is treated as
         * "confirm quit". Toast LENGTH_SHORT is ~2s, so anything in
         * that ballpark gives the user the visible toast as their cue
         * for the second tap.
         */
        private const val BACK_QUIT_WINDOW_MS = 2500L

        /** One full rotation duration for the start-overlay icon spin. */
        private const val GAME_START_ICON_SPIN_MS = 1200L

        /** Fade-out duration for the start overlay when the game is ready. */
        private const val GAME_START_FADE_MS = 180L

        /**
         * Force-hide the start overlay after this long without
         * [GAME_DISPLAY_READY_SENTINEL]. Sized to a generous cold-boot
         * worst case (PSDK script load on PhysFS in debug mode, no
         * mega_script.deflate, mid-range device) plus headroom; the
         * sentinel path takes precedence whenever it reaches us.
         */
        private const val GAME_START_TIMEOUT_MS = 30_000L

        fun hubIntent(activity: AppCompatActivity): Intent =
            Intent(activity, RootActivity::class.java).putExtra(EXTRA_TARGET, TARGET_HUB)

        fun classicWizardIntent(activity: AppCompatActivity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_CLASSIC_WIZARD)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)

        fun directGameIntent(
            activity: android.app.Activity,
            executionLocation: String,
            mode: GameMode = GameMode.RELEASE,
        ): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_DIRECT_GAME)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
                .putExtra(EXTRA_GAME_MODE, mode.envValue())
    }
}
