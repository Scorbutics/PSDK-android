package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.litergss.NativeSurface
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage

/**
 * Single-activity host for in-app screens. Owns the [LiteRgssSurfaceView]
 * AND the splash [PsdkInterpreter] that draws into it so both survive
 * fragment-level navigation. Fragments interact with the splash through
 * the [LoaderHost] contract this activity implements.
 *
 * Routed into by [EntryActivity] with one of:
 *   EXTRA_TARGET = TARGET_HUB            → [ProjectHubFragment]
 *   EXTRA_TARGET = TARGET_CLASSIC_WIZARD → [CompileWizardFragment] in
 *                                          classic-bootstrap mode.
 *
 * Loader lifecycle:
 *   onCreate
 *     → LoaderPaths.resetToDefaults              (wipe + reinstall PSDK
 *                                                 defaults, clobbering
 *                                                 any project assets a
 *                                                 prior session left)
 *     → wire onSurfaceReady listener
 *   surfaceReady
 *     → launch loader interpreter for [currentState] (initially Default)
 *   setLoaderState(s)  ← called by a fragment to declare desired state
 *     → if [s] equals [currentState], no-op
 *     → else prepare on-disk assets per [s], close current interpreter,
 *       launch a new one
 *   onDestroy
 *     → requestClose + close — see the per-line comments in
 *       [teardownAndRelaunchLocked] for the deadlock that makes the
 *       order matter.
 */
class RootActivity : AppCompatActivity(), LoaderHost {

    private var loaderInterpreter: PsdkInterpreter? = null

    private val loaderLaunchLock = Any()
    private var loaderSurfaceReady = false
    private var loaderLaunched = false

    /** Tracked under [loaderLaunchLock]; the source-of-truth for the splash. */
    private var currentState: LoaderState = LoaderState.Default

    /** Latched true on the first READY_SENTINEL so subsequent restarts don't re-fade. */
    private var loaderFadedIn = false

    private var loaderListener: LoaderListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw the GL surface edge-to-edge so the background fills the whole
        // screen, including behind the status and navigation bars. Fragment
        // content stays inside the system bars via insets applied below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_root)

        val fragmentContainer = findViewById<android.view.View>(R.id.fragmentContainer)
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

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

        val glSurface = findViewById<LiteRgssSurfaceView>(R.id.glSurface)
        glSurface.alpha = 0f
        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(LOADER_TAG, "surface ready (${w}x${h})")
            synchronized(loaderLaunchLock) {
                loaderSurfaceReady = true
                tryLaunchLoaderLocked()
            }
        }

        if (savedInstanceState != null) return

        val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_HUB
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

    override fun setLoaderListener(listener: LoaderListener?) {
        loaderListener = listener
    }

    override fun setLoaderState(state: LoaderState) {
        synchronized(loaderLaunchLock) {
            if (state == currentState && loaderLaunched) return  // idempotent
            android.util.Log.i(LOADER_TAG, "setLoaderState (state=$state)")
            currentState = state
            prepareAssetsForLocked(state)
            teardownAndRelaunchLocked()
        }
    }

    /** Caller MUST hold [loaderLaunchLock]. */
    private fun prepareAssetsForLocked(state: LoaderState) {
        when (state) {
            LoaderState.Default -> LoaderPaths.resetToDefaults(this)
            is LoaderState.FromExtracted -> LoaderPaths.replaceAssetsWith(this, state.sourceDir)
            is LoaderState.FromArchive -> {
                // No prep: the loader's own Ruby thread will run [state.prelude]
                // (typically extract_loader_assets.rb) before loader.rb proper.
            }
        }
    }

    /**
     * Caller MUST hold [loaderLaunchLock].
     *
     * Order matters: requestClose pushes a sf::Event::Closed into the
     * loader's DisplayWindow event queue. loader.rb's render loop drains
     * it on the next frame, on_closed raises rb_eClosedWindow, the script
     * unwinds, and the worker thread becomes free. Only THEN does close()
     * — which queues a synchronous :stop on that same worker thread —
     * actually return. Without the requestClose, close() blocks the UI
     * thread forever because :stop sits behind an infinite render loop.
     */
    private fun teardownAndRelaunchLocked() {
        if (loaderLaunched) {
            NativeSurface.requestClose()
            loaderInterpreter?.close()
            loaderInterpreter = null
            loaderLaunched = false
        }
        // Clear stale status so the new loader doesn't immediately fire a
        // sentinel left by the previous one.
        try {
            LoaderPaths.statusFile(this).delete()
        } catch (_: Exception) {
            // best-effort
        }
        tryLaunchLoaderLocked()
    }

    /** Caller MUST hold [loaderLaunchLock]. */
    private fun tryLaunchLoaderLocked() {
        if (loaderLaunched) return
        if (!loaderSurfaceReady) return

        val executionLocation = LoaderPaths.executionLocation(this)
        android.util.Log.i(LOADER_TAG, "launching loader.rb (execLoc=$executionLocation, state=$currentState)")

        val glSurface = findViewById<LiteRgssSurfaceView>(R.id.glSurface)
        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                android.util.Log.i(LOADER_TAG, logMessage.message)
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
        }
        val onErr: (Exception) -> Unit = { e ->
            android.util.Log.e(LOADER_TAG, "loader error", e)
        }

        val (archive, prelude) = when (val s = currentState) {
            is LoaderState.FromArchive -> s.archive to s.prelude
            else -> null to emptyList()
        }

        val loader = PsdkInterpreter.create(this, listener, onErr)
        loaderInterpreter = loader
        // loader.rb itself is the orchestrator — its dependencies live
        // in loader/*.rb. Android's AssetManager can't expose them via
        // require_relative (the script reaches the VM as ruby -e, so
        // __FILE__ is "-e" and the helpers aren't on disk), so we
        // concatenate them as preludes here. Order matches the
        // dependency graph: util → clock → scene_state → assets →
        // window → router → status → layers → splash. Project preludes
        // (extract_loader_assets.rb, etc.) come after so they can rely
        // on Loader::* being defined.
        val script = RubyScript(assets, LOADER_SCRIPT, LOADER_PRELUDES + prelude)
        loader.enqueue(
            script = script,
            location = ScriptLocation(executionLocation = executionLocation, archive = archive),
            onComplete = { exitCode ->
                android.util.Log.i(LOADER_TAG, "loader.rb exited (code=$exitCode)")
            }
        )

        loaderLaunched = true
    }

    override fun onDestroy() {
        // See [teardownAndRelaunchLocked] for the order rationale.
        NativeSurface.requestClose()
        loaderInterpreter?.close()
        loaderInterpreter = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TARGET = "TARGET"
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        const val TARGET_HUB = "HUB"
        const val TARGET_CLASSIC_WIZARD = "CLASSIC_WIZARD"

        private const val LOADER_SCRIPT = "loader.rb"
        private const val LOADER_TAG = "Root-loader"

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

        private const val LOADER_FADE_IN_MS = 180L

        fun hubIntent(activity: AppCompatActivity): Intent =
            Intent(activity, RootActivity::class.java).putExtra(EXTRA_TARGET, TARGET_HUB)

        fun classicWizardIntent(activity: AppCompatActivity, executionLocation: String): Intent =
            Intent(activity, RootActivity::class.java)
                .putExtra(EXTRA_TARGET, TARGET_CLASSIC_WIZARD)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
    }
}
