package com.psdk.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.GameLauncher
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.wizard.CompileDoneFragment
import com.psdk.ui.wizard.CompileProgressFragment
import com.psdk.ui.wizard.ImportArchiveFragment
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
     * True iff the wizard was launched with a freshly-imported archive
     * already staged at [ArchiveValidator.getStagingFile] (the
     * ProjectListFragment "+" → file-picker flow). Once the import
     * step's validation flips [CompileWizardViewModel.archive] non-null,
     * we advance straight to the compile step instead of waiting for a
     * user tap on the splash icon.
     */
    private var autoStart: Boolean = false

    /**
     * True iff the project already has a `Release/` dir on disk. In this
     * mode the wizard skips ImportArchive entirely, shows "Debug Play" in
     * the toolbar, asks the splash to display "Tap logo to play", and a
     * logo-tap launches the game via [GameLauncher] instead of advancing
     * to the compile step.
     */
    private var debugPlayMode: Boolean = false

    /** Used only in [debugPlayMode] to dispatch into the game's NativeActivity. */
    private lateinit var gameLauncher: ActivityResultLauncher<Intent>

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
        // Registered in onCreate so the launcher is ready by the time
        // onIconTapped fires.
        gameLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {}
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
        autoStart = args.getBoolean(ARG_AUTO_START, false)

        viewModel.executionLocation.value = executionLocation

        // Detect debug-play mode: a Release/ dir already exists on disk
        // (built by a prior compile). Mutually exclusive with classic
        // mode, which pins its own flow.
        val releaseLocation = "$executionLocation/Release"
        debugPlayMode = !classicBootstrap && File(releaseLocation).exists()

        toolbar = view.findViewById(R.id.toolbar)
        stepIndicatorBlock = view.findViewById(R.id.stepIndicatorBlock)
        toolbar.setNavigationOnClickListener {
            val step = viewModel.currentStep.value ?: 0
            if (step < 1) {
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

        // Block system back button once compilation starts (VM is dirty).
        // In classic-bootstrap mode we lock back from the start — there's
        // no parent screen to return to; the only exit is "Restart Now"
        // after success or "Restart App" after failure.
        val callback = object : OnBackPressedCallback(classicBootstrap) {
            override fun handleOnBackPressed() {
                // Swallow — restart is the only way out.
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
            val locked = step >= 1 || classicBootstrap
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

            // Auto-start (super-user import-from-hub flow): the user
            // already picked the archive on the hub, so skip the "tap
            // logo to compile" CTA and advance straight to the compile
            // step. The `step == 0` guard makes this idempotent across
            // rotation, where the observer re-fires with the same value.
            if (autoStart && !classicBootstrap && viewModel.currentStep.value == 0) {
                viewModel.currentStep.value = 1
            }
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
            // a "Tap logo to play" CTA; logo-tap fires [GameLauncher].
            // No wizard step fragment is mounted (see [navigateToStep]);
            // the GL surface owns the user's attention.
            val host = requireActivity() as LoaderHost
            val projectAssets = File(executionLocation, "loader_assets")
            if (projectAssets.exists()) {
                host.setLoaderState(LoaderState.FromExtracted(projectAssets))
            }
        } else if (autoStart && !classicBootstrap) {
            // Pre-staged archive (super-user import-from-hub). Validate
            // off-thread — the cache-hit path is just HMAC + KDF (~ms);
            // the cache-miss path runs extract_loader_assets.rb (~2s).
            // Either way we leave the splash unobstructed while the
            // worker runs, then let the [viewModel.archive] observer
            // fire the autoStart advance to step 1.
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
            if (savedInstanceState == null) {
                viewModel.currentStep.value = 0
            }
        }
    }

    private fun navigateToStep(step: Int) {
        // Classic-bootstrap skips the import fragment: the bundled .epsa
        // is staged headlessly and the loader interpreter handles
        // extract_loader_assets.rb in-process, so step 0 should leave
        // the GL surface unobstructed until the auto-tap advances.
        if (classicBootstrap && step == 0) {
            clearFragmentContainer()
            return
        }
        // Debug-play never advances past step 0 — the logo tap launches
        // the game directly. Short-circuiting here (instead of relying on
        // a follow-up clearFragmentContainer in onViewCreated) prevents
        // ImportArchiveFragment from flashing onto the GL surface when
        // the step observer fires its initial value.
        if (debugPlayMode) {
            clearFragmentContainer()
            return
        }
        // Auto-start (import-from-hub) likewise skips ImportArchiveFragment
        // — the wizard validates the pre-staged archive in [onViewCreated]
        // and flips [viewModel.archive] itself, which fires the autoStart
        // branch in the archive observer and advances straight to step 1.
        if (autoStart && step == 0) {
            clearFragmentContainer()
            return
        }
        val fragment = when (step) {
            0 -> ImportArchiveFragment()
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
                    stepIndicators[i].setTextColor(resources.getColor(R.color.colorOnPrimary, null))
                }
                else -> {
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
        val releaseLocation = "$executionLocation/Release"
        GameLauncher.launch(
            requireActivity() as androidx.activity.ComponentActivity,
            executionLocation,
            releaseLocation,
            gameLauncher,
        )
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

        /**
         * Super-user companion to [ARG_CLASSIC_BOOTSTRAP]: the wizard was
         * launched with an archive already staged on disk (typically by
         * [ProjectListFragment]'s "+" → file-picker flow). When set, the
         * wizard skips the "tap logo to compile" CTA — see the
         * [CompileWizardViewModel.archive] observer in [onViewCreated].
         */
        const val ARG_AUTO_START = "AUTO_START"

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
        private const val PLAY_CTA = "‹ Tap logo to play ›"

        fun newInstance(
            executionLocation: String,
            classicBootstrap: Boolean = false,
            autoStart: Boolean = false,
        ): CompileWizardFragment {
            return CompileWizardFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EXECUTION_LOCATION, executionLocation)
                    putBoolean(ARG_CLASSIC_BOOTSTRAP, classicBootstrap)
                    putBoolean(ARG_AUTO_START, autoStart)
                }
            }
        }
    }
}
