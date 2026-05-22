package com.psdk.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.wizard.CompileDoneFragment
import com.psdk.ui.wizard.CompileProgressFragment
import java.io.File

/**
 * Fragment incarnation of the (now-deleted) CompileWizardActivity. Hosted
 * by [RootActivity], which owns the [LiteRgssSurfaceView] AND the Ruby
 * splash interpreter that draws into it (Cut 2). This fragment talks to
 * the splash exclusively through the [LoaderHost] contract:
 *
 *   - Listens for icon-tap and ready events ([LoaderListener]).
 *   - Reconfigures the splash once the archive is in hand
 *     ([LoaderHost.restartLoader]):
 *       super-user        → default config, after copying project
 *                           loader_assets onto disk.
 *       classic-bootstrap → archive + prelude, so the loader's Ruby
 *                           thread runs `extract_loader_assets.rb`
 *                           before `loader.rb` proper.
 *
 * No direct knowledge of PsdkInterpreter, SurfaceView, or NativeSurface.
 */
class CompileWizardFragment : Fragment(), LoaderListener {

    private val viewModel: CompileWizardViewModel by viewModels()

    private lateinit var stepIndicators: List<TextView>
    private lateinit var connectors: List<View>
    private lateinit var toolbar: MaterialToolbar
    private lateinit var stepIndicatorBlock: View

    private var classicBootstrap: Boolean = false

    /**
     * True iff the project already has a `Release/` dir on disk. In this
     * mode the wizard shows "Debug Play" in the toolbar, asks the splash
     * to display "Tap logo to play", and a logo-tap launches the game via
     * [LoaderHost.startGame] instead of advancing to the compile step.
     */
    private var debugPlayMode: Boolean = false

    /** Guards [slideInStepIndicator] against repeating on rotation. */
    private var stepIndicatorRevealed: Boolean = false

    // Classic-bootstrap auto-tap: writes TAP_SENTINEL into the loader's
    // status file so the splash plays the bang/shake/spin combo a real
    // touch would have produced, then advances the wizard step
    // CLASSIC_AUTO_TAP_HOLD_MS later (gives the loader's ~133ms poll a
    // window to see the sentinel).
    private val classicAutoTapHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    private val classicAutoTapRunnable = Runnable { performClassicAutoTap() }
    private val classicAutoTapAdvanceRunnable = Runnable { onIconTapped() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_compile_wizard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val executionLocation = args.getString(ARG_EXECUTION_LOCATION)
            ?: error("CompileWizardFragment requires ARG_EXECUTION_LOCATION")
        classicBootstrap = args.getBoolean(ARG_CLASSIC_BOOTSTRAP, false)

        viewModel.executionLocation.value = executionLocation

        // Detect debug-play mode: a Release/ dir already exists on disk
        // (built by a prior compile). Mutually exclusive with classic
        // mode, which pins its own flow.
        val releaseLocation = "$executionLocation/Release"
        debugPlayMode = !classicBootstrap && File(releaseLocation).exists()

        toolbar = view.findViewById(R.id.toolbar)
        stepIndicatorBlock = view.findViewById(R.id.stepIndicatorBlock)
        toolbar.setNavigationOnClickListener {
            if ((viewModel.currentStep.value ?: 0) != 1) {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        if (debugPlayMode) {
            toolbar.title = "Debug Play"
        }
        // Toolbar overflow lives in every super-user flow (compile and
        // debug-play); classic-bootstrap hides the whole chrome so the
        // menu never paints there. Item visibility is mode-dependent:
        //   - Game Logs: only when the release dir exists (debug-play).
        //   - Delete project: any time we know which project this is
        //     (super-user always; classic has no Project entity).
        if (!classicBootstrap) {
            toolbar.inflateMenu(R.menu.compile_wizard_menu)
            val menu = toolbar.menu
            menu.findItem(R.id.action_game_logs)?.isVisible = debugPlayMode
            menu.findItem(R.id.action_delete_project)?.isVisible = true
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_game_logs -> {
                        showGameLogs(executionLocation, releaseLocation)
                        true
                    }
                    R.id.action_delete_project -> {
                        showDeleteDialog(executionLocation)
                        true
                    }
                    else -> false
                }
            }
        }

        stepIndicators = listOf(
            view.findViewById(R.id.stepIndicator1),
            view.findViewById(R.id.stepIndicator2),
            view.findViewById(R.id.stepIndicator3),
        )
        connectors = listOf(
            view.findViewById(R.id.connector12),
            view.findViewById(R.id.connector23),
        )

        // Block system back only while compilation is actively running
        // (step 1) — interrupting the Ruby VM mid-build leaves on-disk
        // state inconsistent. Step 0 (pre-compile) and step 2 (done)
        // are safe to leave.
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Swallow — compilation in progress.
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Listen for splash icon-taps + readiness. The activity-owned
        // loader emits ICON_TAPPED_SENTINEL on every hit; we filter for
        // "still on step 0 AND have a valid archive" in [onIconTapped].
        // READY_SENTINEL drives the classic-bootstrap auto-tap timer.
        (requireActivity() as LoaderHost).setLoaderListener(this)

        // Observe step changes
        viewModel.currentStep.observe(viewLifecycleOwner) { step ->
            updateStepIndicators(step)
            navigateToStep(step)
            val locked = step == 1
            toolbar.navigationIcon = if (locked) null
                else androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            callback.isEnabled = locked
            // Reveal the step indicator the first time we leave step 0
            // (i.e., the user tapped the logo to begin compiling). Skipped
            // in classic-bootstrap (chrome is hidden whole-cloth) and in
            // debug-play (there's nothing to compile).
            if (step >= 1 && !classicBootstrap && !debugPlayMode) {
                slideInStepIndicator()
            }
        }

        // Archive ready → declare splash state for this project. Mode
        // splits:
        //   super-user        — assets are already on disk (the Kotlin
        //                       validation pass ran extract_loader_assets
        //                       to <projectDir>/loader_assets/). The
        //                       activity copies them into the loader's
        //                       central asset dir and restarts.
        //   classic-bootstrap — no Kotlin-side validation. The activity
        //                       binds the archive and runs the extract
        //                       prelude in the loader's own Ruby thread.
        // Re-firing on rotation is harmless: the activity treats
        // setLoaderState as idempotent on equality and skips the restart
        // if the state matches what's running.
        viewModel.archive.observe(viewLifecycleOwner) { archive ->
            if (archive == null) return@observe
            val host = requireActivity() as LoaderHost
            val state = if (classicBootstrap) {
                LoaderState.FromArchive(
                    archive = archive,
                    prelude = CompilationEngine.ARCHIVE_PRELUDES + EXTRACT_LOADER_ASSETS_SCRIPT,
                )
            } else {
                LoaderState.FromExtracted(sourceDir = File(executionLocation, "loader_assets"))
            }
            host.setLoaderState(state)
        }

        // Classic-bootstrap-only chrome / state transitions.
        if (classicBootstrap) {
            view.findViewById<View>(R.id.wizardChrome).visibility = View.GONE
            toolbar.title = "Setup"
            viewModel.compilationSuccess.observe(viewLifecycleOwner) { success ->
                if (success == true) {
                    val prefs = requireContext().getSharedPreferences(ClassicModeState.PREFS, Context.MODE_PRIVATE)
                    val versionCode = requireContext().packageManager
                        .getPackageInfo(requireContext().packageName, 0).longVersionCode
                    prefs.edit()
                        .putString(ClassicModeState.STATE_KEY, ClassicModeState.STATE_SETUP_COMPLETE)
                        .putLong(ClassicModeState.VERSION_CODE_KEY, versionCode)
                        .apply()
                }
            }
        }

        if (debugPlayMode) {
            // Already compiled — the splash shows the project's assets and
            // a "Tap logo to play" CTA; logo-tap fires LoaderHost.startGame.
            // No wizard step fragment is mounted (see [navigateToStep]);
            // the GL surface owns the user's attention.
            val host = requireActivity() as LoaderHost
            val projectAssets = File(executionLocation, "loader_assets")
            if (projectAssets.exists()) {
                host.setLoaderState(LoaderState.FromExtracted(projectAssets))
            }
        } else if (classicBootstrap) {
            // Off-thread stage + key derivation. Setting viewModel.archive
            // fires the observer above which restarts the loader with
            // archive+prelude bound; loader.rb's own thread then runs
            // extract_loader_assets.rb before its first frame.
            if (savedInstanceState == null && viewModel.archive.value == null) {
                val ctx = requireContext().applicationContext
                Thread {
                    val keys = runCatching {
                        extractBundledArchive(ctx, executionLocation)
                        ArchiveValidator.resolveKeys(
                            ctx,
                            ArchiveValidator.getStagingFile(executionLocation),
                        ) ?: error("classic bootstrap: archive key resolution failed")
                    }.onFailure {
                        android.util.Log.e(TAG, "classic bootstrap failed", it)
                    }.getOrNull()

                    activity?.runOnUiThread {
                        if (!isAdded || isRemoving) return@runOnUiThread
                        if (keys == null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Failed to load bundled archive. Try reinstalling.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            requireActivity().finish()
                            return@runOnUiThread
                        }
                        viewModel.archive.value = keys
                        viewModel.currentStep.value = 0
                    }
                }.start()
            } else if (savedInstanceState == null) {
                viewModel.currentStep.value = 0
            }
        } else {
            // Super-user (non-debug-play): the archive is already staged on
            // disk (ProjectListFragment did the import). Validate off-thread
            // — the cache-hit path is just HMAC + KDF (~ms); the cache-miss
            // path runs extract_loader_assets.rb (~2s). The splash stays
            // unobstructed while the worker runs; on success the
            // [viewModel.archive] observer points the loader at the
            // extracted assets, and the user taps the logo to compile.
            if (savedInstanceState == null && viewModel.archive.value == null) {
                val ctx = requireContext().applicationContext
                val staged = ArchiveValidator.getStagingFile(executionLocation)
                Thread {
                    val result = ArchiveValidator.validateExisting(ctx, staged, executionLocation)
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (result.isValid) {
                            viewModel.archive.value = result.archive
                        } else {
                            android.widget.Toast.makeText(
                                requireContext(),
                                result.error ?: "Archive validation failed",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }.start()
            }
        }
    }

    private fun navigateToStep(step: Int) {
        // Step 0 never mounts a fragment: the GL surface's "Tap logo to
        // compile" CTA is the start-compilation affordance. Debug-play
        // never advances past step 0 — the logo tap launches the game
        // directly via [onIconTapped] — so it stays bare for every step.
        if (step == 0 || debugPlayMode) {
            clearFragmentContainer()
            return
        }
        val fragment = when (step) {
            1 -> CompileProgressFragment()
            2 -> CompileDoneFragment()
            else -> return
        }
        navigateToFragment(fragment)
    }

    private fun navigateToFragment(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.wizardContainer, fragment)
            .commit()
    }

    private fun clearFragmentContainer() {
        val current = childFragmentManager.findFragmentById(R.id.wizardContainer) ?: return
        childFragmentManager.beginTransaction().remove(current).commit()
    }

    /**
     * Called by [CompileDoneFragment] once the success card finishes its
     * slide-out animation. Flips the wizard into [debugPlayMode] in-place
     * — no app restart — so the user can tap the logo to play immediately.
     * Mirrors the bootstrap path in [onViewCreated] / [onLoaderReady], but
     * runs against an already-live wizard rather than a fresh one.
     */
    fun onCompileDoneDismissed() {
        if (!isAdded) return
        if (debugPlayMode) return
        if (viewModel.compilationSuccess.value != true) return

        debugPlayMode = true
        toolbar.title = "Debug Play"
        if (!classicBootstrap) {
            toolbar.menu.findItem(R.id.action_game_logs)?.isVisible = true
        }
        stepIndicatorBlock.visibility = View.GONE

        // Reset the splash's Ruby SceneState in-place via the status-file
        // sentinel, then write the play CTA in the same payload. A full
        // workload restart was tempting (clean slate), but in this app the
        // loader's `ensure` block runs PSDKVMSnapshot.log_diff!/restore! —
        // and because compile.rb loaded ~hundreds of PSDK scripts on the
        // shared g_global_vm, log_diff! enumerates every entry as a "DROP"
        // and STDERR.puts each one. The Ruby render thread is blocked for
        // seconds, the glSurfaceOverlay stays opaque (no [loader-ready] to
        // fade it out), and the user sees only the activity background.
        // The sentinel keeps the same Loader::Splash alive — its next poll
        // wipes lifted_start / unlifted_start / rolling_start / shake_start /
        // pressed_finger and resets the icon angle to 0, then the trailing
        // payload becomes the new status text.
        try {
            LoaderPaths.statusFile(requireContext())
                .writeText(SCENE_RESET_SENTINEL + PLAY_CTA)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "debug-play CTA write failed", e)
        }
        clearFragmentContainer()
    }

    /**
     * Failure counterpart to [onCompileDoneDismissed]. The user read the
     * error preview and tapped to dismiss; we drop back to step 0 with
     * the "Tap logo to compile" CTA so a logo tap retriggers the same
     * compilation pipeline. The archive is already validated and staged
     * from the failed run, so step 0 just leaves the GL splash bare.
     */
    fun onCompileFailureDismissed() {
        if (!isAdded) return
        if (viewModel.compilationSuccess.value != false) return

        // Mirror of [onCompileDoneDismissed]'s in-place reset. The Ruby
        // SceneState is just as dirty on the failure path (lifted_start +
        // unlifted_start fired, rolling_start pinned, icon angle non-zero) —
        // a retry tap on a stale scene leaves the icon in its post-unlift
        // position with a stuck rotation. The sentinel clears that state
        // without paying the workload-restart cost (see kdoc on the success
        // path for why a restart is wrong here).
        try {
            LoaderPaths.statusFile(requireContext())
                .writeText(SCENE_RESET_SENTINEL + COMPILE_CTA)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "compile CTA write failed", e)
        }
        // Retreat to the pre-compile look: step indicator hidden again so
        // the user lands on the same blank splash they saw on first entry.
        // [stepIndicatorRevealed] is reset so a subsequent retry slides it
        // back in with the same animation as the initial reveal.
        stepIndicatorBlock.visibility = View.GONE
        stepIndicatorRevealed = false
        viewModel.compilationSuccess.value = null
        viewModel.currentStep.value = 0
    }

    private fun updateStepIndicators(currentStep: Int) {
        for (i in stepIndicators.indices) {
            when {
                i < currentStep -> {
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_completed)
                    stepIndicators[i].text = "✓"
                    stepIndicators[i].setTextColor(resources.getColor(R.color.white, null))
                }
                i == currentStep -> {
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_active)
                    stepIndicators[i].text = (i + 1).toString()
                    // White on splash-accent reads cleaner than the old
                    // black-on-mint pairing did.
                    stepIndicators[i].setTextColor(resources.getColor(R.color.white, null))
                }
                else -> {
                    // Stroke-only pending circle: text sits inside the
                    // transparent fill, so it needs its own readable
                    // colour rather than relying on contrast against a
                    // solid background.
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_pending)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(resources.getColor(R.color.colorOnSurfaceVariant, null))
                }
            }
        }

        for (i in connectors.indices) {
            connectors[i].setBackgroundResource(
                if (i < currentStep) R.drawable.bg_step_connector_active
                else R.drawable.bg_step_connector
            )
        }
    }

    /**
     * Classic-bootstrap helper: copy the bundled `.epsa` from APK assets
     * into the wizard's staging file. Runs off the UI thread because the
     * payload can be tens of MB. No-op if the staging file already exists.
     */
    private fun extractBundledArchive(context: Context, executionLocation: String) {
        val staging = ArchiveValidator.getStagingFile(executionLocation)
        if (staging.exists() && staging.length() > 0) {
            android.util.Log.i(TAG, "classic bootstrap: staging already present, skipping copy")
            return
        }
        val asset = AppModeDetector.findEpsaInAssets(context)
            ?: error("classic bootstrap: no .epsa found in APK assets")
        android.util.Log.i(TAG, "classic bootstrap: copying assets/$asset → ${staging.absolutePath}")
        context.assets.open(asset).use { input ->
            staging.outputStream().use { out -> input.copyTo(out) }
        }
    }

    /**
     * Two-phase auto-tap (classic-bootstrap only):
     *  1. Write TAP_SENTINEL so the loader plays the release-on-icon combo.
     *  2. After CLASSIC_AUTO_TAP_HOLD_MS, advance the wizard step.
     */
    private fun performClassicAutoTap() {
        if (viewModel.currentStep.value != 0) return
        if (viewModel.archive.value == null) return

        try {
            LoaderPaths.statusFile(requireContext())
                .writeText(CompileProgressFragment.TAP_SENTINEL)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "classic auto-tap sentinel write failed", e)
        }
        classicAutoTapHandler.removeCallbacks(classicAutoTapAdvanceRunnable)
        classicAutoTapHandler.postDelayed(
            classicAutoTapAdvanceRunnable,
            CLASSIC_AUTO_TAP_HOLD_MS,
        )
    }

    // ── LoaderListener ───────────────────────────────────────────────

    override fun onLoaderReady() {
        if (debugPlayMode) {
            // Override loader.rb's default "‹ Tap logo to compile ›" CTA
            // with the play variant. The write also flips
            // `external_status_received` inside the loader so the default
            // CTA branch is suppressed (see loader.rb's
            // poll_external_status).
            try {
                LoaderPaths.statusFile(requireContext()).writeText(PLAY_CTA)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "debug-play CTA write failed", e)
            }
            return
        }
        // Classic-bootstrap auto-tap is gated on the splash actually being
        // visible (= READY_SENTINEL = first @window.update flipped onto
        // EGL), not on archive availability. The archive is bound much
        // earlier than ready (key derivation is ~ms vs the prelude's ~2s),
        // so a finger-equivalent tap couldn't be visually responded to
        // before this point.
        if (!classicBootstrap) return
        if (viewModel.currentStep.value != 0) return
        classicAutoTapHandler.removeCallbacks(classicAutoTapRunnable)
        classicAutoTapHandler.postDelayed(classicAutoTapRunnable, CLASSIC_AUTO_TAP_DELAY_MS)
    }

    override fun onIconTapped() {
        if (!isAdded) return
        if (debugPlayMode) {
            launchGame()
            return
        }
        if (viewModel.currentStep.value != 0) return
        if (viewModel.archive.value == null) return
        viewModel.currentStep.value = 1
    }

    private fun launchGame() {
        val executionLocation = viewModel.executionLocation.value ?: return
        // Route through the host. RootActivity.startGame performs the
        // in-process workload swap: tears down the loader and enqueues
        // start.rb on a fresh PsdkInterpreter against the same surface.
        val host = requireActivity() as? LoaderHost ?: run {
            android.util.Log.e("CompileWizard", "host activity is not a LoaderHost; cannot launch game")
            return
        }
        host.startGame(executionLocation)
    }

    private fun showGameLogs(executionLocation: String, releaseLocation: String) {
        val fragment = LogBottomSheetFragment.newInstance(
            outputLogPath = "$executionLocation/last_stdout.log",
            errorLogPath = "$releaseLocation/Error.log",
            title = "Game Logs",
        )
        fragment.show(parentFragmentManager, "game_logs")
    }

    /**
     * "Delete project" relocated from the row trash icon. Resolves the
     * Project entity by its on-disk directory (the only stable handle the
     * wizard was given), drops it from the DB, wipes the directory, and
     * pops the wizard back to the project list. The list refreshes
     * itself in [ProjectListFragment.onResume].
     */
    private fun showDeleteDialog(executionLocation: String) {
        val database = com.psdk.db.AppDatabase.getInstance(requireContext())
        val project = database.projectDao().findByDirectory(executionLocation) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext(),
            R.style.Theme_Psdk_Dialog,
        )
            .setTitle("Delete project")
            .setMessage("Remove '${project.name}'? This deletes the staged archive and any compiled output.")
            .setPositiveButton("Delete") { _, _ ->
                database.projectDao().delete(project)
                val dir = File(project.directory)
                if (dir.canRead()) dir.deleteRecursively()
                if (isAdded) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Reveal the step-indicator block by sliding it down from behind the
     * toolbar. View was [View.GONE] until now, so [doOnPreDraw] runs after
     * the layout pass that gives it a measured height. Idempotent: a
     * second call (rotation while past step 0) is a no-op.
     */
    private fun slideInStepIndicator() {
        if (stepIndicatorRevealed) return
        if (stepIndicatorBlock.visibility == View.VISIBLE) {
            stepIndicatorRevealed = true
            return
        }
        stepIndicatorRevealed = true
        stepIndicatorBlock.visibility = View.VISIBLE
        stepIndicatorBlock.alpha = 0f
        stepIndicatorBlock.doOnPreDraw {
            stepIndicatorBlock.translationY = -stepIndicatorBlock.height.toFloat()
            stepIndicatorBlock.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(STEP_INDICATOR_SLIDE_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    override fun onDestroyView() {
        classicAutoTapHandler.removeCallbacks(classicAutoTapRunnable)
        classicAutoTapHandler.removeCallbacks(classicAutoTapAdvanceRunnable)
        (requireActivity() as LoaderHost).setLoaderListener(null)
        super.onDestroyView()
    }

    companion object {
        const val ARG_EXECUTION_LOCATION = "EXECUTION_LOCATION"

        /**
         * When true, the wizard treats itself as the first-launch setup
         * flow for classic mode: it auto-stages the bundled .epsa out of
         * APK assets, locks back navigation throughout, and writes
         * [ClassicModeState.STATE_SETUP_COMPLETE] to prefs when
         * compilation succeeds.
         */
        const val ARG_CLASSIC_BOOTSTRAP = "CLASSIC_BOOTSTRAP"

        private const val TAG = "CompileWizardFragment"

        /**
         * Prelude the loader script pulls in only in classic-bootstrap
         * mode. Runs inside the loader's own Ruby VM thread so the wizard
         * doesn't need a separate validation pass; the file IS the same
         * file the super-user [com.psdk.compilation.ArchiveValidator]
         * runs in its one-shot validation interpreter.
         */
        private const val EXTRACT_LOADER_ASSETS_SCRIPT = "extract_loader_assets.rb"

        private const val CLASSIC_AUTO_TAP_DELAY_MS = 2000L
        private const val CLASSIC_AUTO_TAP_HOLD_MS = 200L

        /**
         * Tuned to feel like the indicator is "dropping in" from behind
         * the toolbar — long enough to register, short enough that the
         * user's eye is still on the logo they just tapped.
         */
        private const val STEP_INDICATOR_SLIDE_MS = 300L

        /**
         * Mirrors the bracket styling of the loader's default
         * "‹ Tap logo to compile ›" CTA so the swap reads as a label
         * change, not a font change. Written into [LoaderPaths.statusFile]
         * in debug-play mode once the splash is ready.
         */
        private const val PLAY_CTA = "< Tap logo to play >"

        /**
         * Restores the loader's default CTA after a failed compile is
         * dismissed. Matches the literal string loader.rb prints when
         * external_status_received is still false — once we've written
         * anything to the status file that flag is permanently true,
         * so we have to push the text ourselves rather than rely on
         * the loader's own default.
         */
        private const val COMPILE_CTA = "< Tap logo to compile >"

        /**
         * Matches Loader::StatusFile::RESET_SENTINEL in
         * loader/status_file.rb verbatim. Writing this prefix on the
         * status file asks the splash to wipe its SceneState in-place
         * (lifted/unlifted/rolling/shake/pressed all back to nil, icon
         * angle back to 0) without restarting the workload — anything
         * after the prefix becomes the new status text in the same poll.
         */
        private const val SCENE_RESET_SENTINEL = "<<RESET>>"

        fun newInstance(
            executionLocation: String,
            classicBootstrap: Boolean = false,
        ): CompileWizardFragment {
            return CompileWizardFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EXECUTION_LOCATION, executionLocation)
                    putBoolean(ARG_CLASSIC_BOOTSTRAP, classicBootstrap)
                }
            }
        }
    }
}
