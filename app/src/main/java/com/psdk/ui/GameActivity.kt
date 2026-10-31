package com.psdk.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RemoteListenerConfig
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.HostEventListener
import java.io.FileWriter

/**
 * Runs exactly one game session (start.rb → Game.rb), in its own Android
 * process (`android:process=":game"` in the manifest), and kills that
 * process when the session ends.
 *
 * Why a separate process: CRuby can only be initialised once per process,
 * and PSDK monkey-patches core classes, reopens its own classes with
 * alias_method chains, and fills globals as it boots. Running a second
 * session on the same VM compounds those chains (SystemStackError,
 * Options.new looping). Process death is the only reliable full reset, so
 * every launch here starts from a fresh VM and the shell process
 * ([RootActivity]: hub, wizard, splash, compile) never sees PSDK's game
 * state at all.
 *
 * Exit paths, all ending in [onDestroy] → [android.os.Process.killProcess]:
 *   - Game.rb returns (user quit in-game, or script error) →
 *     [RubyWorkloadController] onWorkloadCompleted → [finish]
 *   - super-user double BACK → controller.stop() → same as above
 *   - system destroys the activity → onDestroy directly
 *
 * The caller learns about the exit through the activity result (delivered
 * even if this process dies), see [RootActivity.startGame].
 */
class GameActivity : AppCompatActivity() {

    private lateinit var glSurface: LiteRgssSurfaceView
    private lateinit var gameStartOverlay: View
    private lateinit var gameStartIcon: android.widget.ImageView
    private lateinit var imeSink: EditText

    /** Continuous rotation on [gameStartIcon] while the start overlay is up. */
    private var gameStartIconAnimator: android.animation.ObjectAnimator? = null

    /** Posted on [showGameStartOverlay]; cancelled on [hideGameStartOverlay]. */
    private val gameStartTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gameStartTimeoutRunnable = Runnable {
        android.util.Log.w(TAG, "game-start overlay timeout reached without game.ready — forcing hide")
        hideGameStartOverlay()
    }

    private lateinit var workloadController: RubyWorkloadController
    private lateinit var systemUi: SystemUiPresenter
    private lateinit var inputForwarder: GameInputForwarder
    private lateinit var imeBridge: ImeBridge

    /** Built in onCreate; launched once the surface is ready. Null if the intent was unusable. */
    private var gameWorkload: RubyWorkload.Game? = null

    /**
     * True for the classic-user flow (one project baked into the APK, no
     * hub to return to). Gates the back-to-quit-game UX in
     * [dispatchKeyEvent]: in classic mode BACK keeps being consumed by
     * [inputForwarder].
     */
    private var isClassicMode: Boolean = false

    /** [android.os.SystemClock.uptimeMillis] of the most recent BACK press, or 0 if armed/expired. */
    private var lastGameBackPressUptimeMs: Long = 0L

    /** Active "press again to quit" toast so the second press can dismiss it instantly. */
    private var gameBackToast: android.widget.Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fresh process: redo the process-wide native setup EntryActivity
        // does for the shell process.
        PsdkInterpreter.initialize()
        NativeSurface.setReuseSharedWindow(true)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_game)

        glSurface = findViewById(R.id.glSurface)
        gameStartOverlay = findViewById(R.id.gameStartOverlay)
        gameStartIcon = findViewById(R.id.gameStartIcon)
        imeSink = findViewById(R.id.imeSink)

        // See RootActivity.onCreate: SFML's hosted-mode JNI paths (virtual
        // keyboard) need a Context + Window. Cleared in onDestroy.
        NativeSurface.setHostActivity(this)

        systemUi = SystemUiPresenter(this, shellContent = null)
        systemUi.enterGameMode()

        imeBridge = ImeBridge(this, imeSink, focusRestoreTarget = glSurface)
        inputForwarder = GameInputForwarder(this).apply {
            // While the soft keyboard is up, printable keys go to the IME
            // sink (ImeBridge forwards its text to the game); the others
            // (Backspace, Enter, arrows, Escape...) reach the game as keys,
            // the way a desktop keyboard does while typing a name.
            keyEventShouldConsume = { event -> !imeBridge.isImeActive || !producesText(event) }
        }

        workloadController = RubyWorkloadController(
            context = this,
            assets = assets,
            onError = { e -> android.util.Log.e(TAG, "workload error", e) },
            onWorkloadCompleted = { workload, exitCode ->
                android.util.Log.i(TAG, "workload ${workload.name} exited (code=$exitCode)")
                runOnUiThread {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_EXIT_CODE, exitCode))
                    finish()
                }
            },
            // start.rb emits game.ready once PSDK's first LiteRGSS::DisplayWindow.new returns
            hostEventListener = HostEventListener { event ->
                if (event.topic == "game.ready") {
                    android.util.Log.i(TAG, "game.ready event — hiding overlay")
                    hideGameStartOverlay()
                }
            },
        )

        isClassicMode = intent.getBooleanExtra(EXTRA_CLASSIC_MODE, false)
        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION)
            ?: return finish()
        val mode = GameMode.fromEnvValue(intent.getStringExtra(EXTRA_GAME_MODE))
        configureGameEnv(executionLocation, mode)
        showGameStartOverlay(executionLocation)
        inputForwarder.start()
        imeBridge.start()

        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(TAG, "surface ready (${w}x${h})")
            workloadController.onSurfaceReady()
        }
        // Debug mode resolves the archive keys first: KDF, plus a fresh key
        // attestation when the cached verdict expired. Not on the main
        // thread (that froze the launch). The controller queues the start
        // until the surface is ready anyway.
        Thread({
            val workload = buildWorkload(executionLocation, mode)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (workload == null) {
                    finish()
                    return@runOnUiThread
                }
                gameWorkload = workload
                workloadController.start(workload)
            }
        }, "psdk-game-workload").start()
    }

    /**
     * DEBUG_UNCOMPILED needs the archive keys bound for the start.rb
     * prelude to mount PhysFS. Resolve them off the staged .epsa via the
     * same path the wizard uses; null if resolution fails, so we don't end
     * up running with no Game.rb visible.
     */
    private fun buildWorkload(executionLocation: String, mode: GameMode): RubyWorkload.Game? {
        val resolvedArchive = if (mode == GameMode.DEBUG_UNCOMPILED) {
            com.psdk.compilation.ArchiveValidator.resolveKeys(
                this,
                com.psdk.compilation.ArchiveValidator.getStagingFile(executionLocation),
            ) ?: run {
                android.util.Log.e(TAG, "DEBUG_UNCOMPILED requested but archive keys did not resolve; aborting")
                return null
            }
        } else null
        return RubyWorkload.Game(
            executionLocation = executionLocation,
            mode = mode,
            archive = resolvedArchive,
            preludes = if (mode == GameMode.DEBUG_UNCOMPILED) START_PRELUDES_DEBUG else START_PRELUDES,
        )
    }

    /**
     * Open the start overlay: bind the project's icon, animate a continuous
     * rotation. Hidden by [hideGameStartOverlay] once start.rb sends
     * game.ready, or by the timeout.
     */
    private fun showGameStartOverlay(executionLocation: String) {
        // Project icon. extract_loader_assets.rb writes game.png alongside
        // game_font.ttf and infos_config.json in <exec>/loader_assets/. If
        // it's not there (e.g. first debug-uncompiled run on a project
        // that's never been validated), fall back to the bundled logo so
        // the overlay still has something to spin.
        val projectIcon = java.io.File(executionLocation, "loader_assets/game.png")
        val bitmap = if (projectIcon.exists()) {
            android.graphics.BitmapFactory.decodeFile(projectIcon.absolutePath)
        } else null
        if (bitmap != null) {
            gameStartIcon.setImageBitmap(bitmap)
        } else {
            gameStartIcon.setImageResource(R.drawable.logo)
        }

        gameStartOverlay.alpha = 1f
        gameStartOverlay.visibility = View.VISIBLE
        gameStartIcon.rotation = 0f
        gameStartIconAnimator = android.animation.ObjectAnimator.ofFloat(
            gameStartIcon, View.ROTATION, 0f, 360f
        ).apply {
            duration = GAME_START_ICON_SPIN_MS
            repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }

        // Defensive timeout. If start.rb's game.ready never arrives
        // (script error before DisplayWindow.new, the wrapper code didn't
        // catch the construction path for some reason, etc.) we still
        // reveal the GL surface so the user sees whatever the game IS
        // rendering. Long enough to cover a cold debug-uncompiled boot
        // (PSDK's script load on PhysFS is ~10–25s on a mid-range device);
        // game.ready normally pre-empts it.
        gameStartTimeoutHandler.postDelayed(gameStartTimeoutRunnable, GAME_START_TIMEOUT_MS)
    }

    /**
     * Cross-fade the start overlay out (revealing Game.rb's first frame on
     * the GL surface), then stop the rotation animator + hide the View so
     * subsequent touches reach the surface instead of getting eaten by the
     * overlay's clickable=true.
     *
     * Safe to call from any thread and when the overlay isn't visible.
     */
    private fun hideGameStartOverlay() {
        runOnUiThread {
            gameStartTimeoutHandler.removeCallbacks(gameStartTimeoutRunnable)
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
     * Populate the env vars start.rb reads (RGSS_EXECUTION_LOCATION,
     * RGSS_LOG_FILE, RGSS_ERROR_LOG_FILE, RGSS_GAME_MODE, RGSS_REMOTE_*).
     * Process-local, which is why it runs here and not in the shell process.
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

        try {
            FileWriter("$executionLocation/last_stdout.log", false).flush()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to reset stdout log: ${e.message}")
        }

        if (RemoteListenerConfig.enabled) {
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_HOST",    RemoteListenerConfig.DEBUG_HOST,                true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_PORT",    RemoteListenerConfig.debugPort.toString(),      true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_TOKEN",   RemoteListenerConfig.TOKEN,                     true)
            android.system.Os.setenv("RGSS_REMOTE_DEBUG_SESSION", "psdk-game-debug",                               true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_HOST",     RemoteListenerConfig.EVAL_HOST,                 true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_PORT",     RemoteListenerConfig.evalPort.toString(),       true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_TOKEN",    RemoteListenerConfig.TOKEN,                     true)
            android.system.Os.setenv("RGSS_REMOTE_EVAL_SESSION",  "psdk-game-eval",                                true)
        }
    }

    // onPause/onResume intentionally NOT overridden to push focus events:
    // the SurfaceHolder callbacks already do it (see the long note in
    // RootActivity). A second push produces back-to-back LostFocus events
    // and an EGL_BAD_SURFACE that kills the game workload.

    override fun onDestroy() {
        if (::inputForwarder.isInitialized) inputForwarder.stop()
        if (::imeBridge.isInitialized) imeBridge.stop()
        gameStartTimeoutHandler.removeCallbacks(gameStartTimeoutRunnable)
        if (::workloadController.isInitialized) {
            workloadController.shutdown()
        } else {
            NativeSurface.requestClose()
        }
        NativeSurface.setHostActivity(null)
        super.onDestroy()
        // The VM is now dirty (PSDK state can't be unloaded). Kill the
        // process so the next launch — or an Android-driven recreation of
        // this activity — gets a fresh one.
        android.util.Log.i(TAG, "game session over — killing :game process")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun producesText(event: KeyEvent): Boolean =
        event.getUnicodeChar(event.metaState) >= ' '.code

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Super-user flow only: intercept BACK so the user can leave the
        // game and return to the project page. In classic mode there is no
        // page to return to, so BACK keeps being consumed by inputForwarder.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !isClassicMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleGameBackPress()
            }
            return true
        }
        if (::inputForwarder.isInitialized && inputForwarder.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Double-tap-to-quit handler for the in-game BACK key. First press
     * shows a transient toast and arms a timer; a second press within
     * [BACK_QUIT_WINDOW_MS] requests the game workload to stop, which
     * finishes this activity once the script unwinds.
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

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::inputForwarder.isInitialized && inputForwarder.handleMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        private const val TAG = "GameActivity"

        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        /** [GameMode.envValue] ("release" | "debug_uncompiled"). */
        const val EXTRA_GAME_MODE = "GAME_MODE"
        const val EXTRA_CLASSIC_MODE = "CLASSIC_MODE"

        /** Result extra: the start.rb workload's exit code. */
        const val RESULT_EXIT_CODE = "EXIT_CODE"

        /** start.rb's helper modules: TouchLayouts loads the gamepad's layout files. */
        private val START_PRELUDES = listOf("lib/touch_layouts.rb")

        /**
         * DEBUG_UNCOMPILED mode's: start.rb's debug branch also needs
         * EpsaFormat / EpsaStream / ArchiveMount in scope to mount the
         * encrypted .epsa via PhysFS.
         */
        private val START_PRELUDES_DEBUG = listOf(
            "lib/epsa_format.rb",
            "lib/epsa_stream.rb",
            "lib/archive_mount.rb",
        ) + START_PRELUDES

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
         * Force-hide the start overlay after this long without the ready
         * file. Sized to a generous cold-boot worst case (PSDK script load
         * on PhysFS in debug mode, no mega_script.deflate, mid-range device)
         * plus headroom.
         */
        private const val GAME_START_TIMEOUT_MS = 30_000L

        fun intent(
            activity: Activity,
            executionLocation: String,
            mode: GameMode = GameMode.RELEASE,
            classicMode: Boolean = false,
        ): Intent =
            Intent(activity, GameActivity::class.java)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
                .putExtra(EXTRA_GAME_MODE, mode.envValue())
                .putExtra(EXTRA_CLASSIC_MODE, classicMode)
    }
}
