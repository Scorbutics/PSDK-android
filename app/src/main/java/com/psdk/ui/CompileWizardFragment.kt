package com.psdk.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.AppModeDetector
import com.psdk.ClassicModeState
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.compilation.CompilationEngine
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.psdk.ui.wizard.CompileDoneFragment
import com.psdk.ui.wizard.CompileProgressFragment
import com.psdk.ui.wizard.ExportApkFragment
import com.psdk.ui.wizard.ImportArchiveFragment
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage

/**
 * Fragment incarnation of the (now-deleted) CompileWizardActivity. Hosted
 * by [RootActivity]; finds the shared [LiteRgssSurfaceView] via the host
 * activity rather than owning one itself.
 *
 * The Ruby splash interpreter ([loaderInterpreter]) is still owned by this
 * fragment — born when the GL surface signals readiness, torn down in
 * [onDestroy]. Promoting it to [RootActivity] so the splash persists into
 * the hub is a follow-up.
 */
class CompileWizardFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by viewModels()

    private lateinit var stepIndicators: List<TextView>
    private lateinit var connectors: List<View>
    private lateinit var toolbar: MaterialToolbar

    // Splash interpreter — same role as in the old activity: runs loader.rb
    // in its own Ruby Thread inside the process VM. No archive bound:
    // loader.rb reads its assets from <executionLocation>/loader_assets/
    // (written during validation) and never touches PhysFS. Lifecycle:
    // created once the GL surface is ready and the execution location is
    // known; closed in onDestroy.
    private var loaderInterpreter: PsdkInterpreter? = null

    private val loaderLaunchLock = Any()
    private var loaderSurfaceReady = false
    private var loaderLaunched = false

    // Captured in onViewCreated from the fragment's arguments; queried by
    // navigateToStep, tryLaunchLoaderLocked, and the LogListener so they
    // can branch between classic-bootstrap (headless splash-only flow)
    // and the regular super-user wizard.
    private var classicBootstrap: Boolean = false

    // Classic-bootstrap auto-tap, same semantics as the old activity.
    private val classicAutoTapHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    private val classicAutoTapRunnable = Runnable { performClassicAutoTap() }
    private val classicAutoTapAdvanceRunnable = Runnable { onLoaderIconTapped() }

    private var backCallback: OnBackPressedCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_compile_wizard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val executionLocation = args.getString(ARG_EXECUTION_LOCATION)
            ?: error("CompileWizardFragment requires ARG_EXECUTION_LOCATION")
        classicBootstrap = args.getBoolean(ARG_CLASSIC_BOOTSTRAP, false)
        val apkFolderLocation = args.getString(ARG_APK_FOLDER_LOCATION)

        viewModel.executionLocation.value = executionLocation
        if (apkFolderLocation != null) {
            viewModel.apkFolderLocation.value = apkFolderLocation
        }

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            // Block back navigation once compilation has started (VM is dirty)
            val step = viewModel.currentStep.value ?: 0
            if (step < 1) {
                requireActivity().onBackPressedDispatcher.onBackPressed()
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

        // Wipe any stale loader status file from a previous wizard
        // session. The file lives under the app data dir and survives
        // process restarts, so a leftover "<<LIFT>>" from last run would
        // fire the splash lift immediately on launch (loader.rb polls
        // this file every ~8 frames). Gated on engine == null so a
        // mid-compile rotation — where the fragment is re-created but
        // the ViewModel-owned engine keeps running — doesn't erase the
        // in-flight sub-step.
        if (savedInstanceState == null && viewModel.getEngine() == null) {
            try {
                java.io.File(executionLocation, CompileProgressFragment.LOADER_STATUS_FILE).delete()
            } catch (e: Exception) {
                // best-effort — a stale file just delays the user's first lift by one compile
            }
        }

        // Block system back button once compilation starts (VM is dirty).
        // In classic-bootstrap mode we lock back from the start too —
        // there's no parent activity to return to; the only exit is
        // "Restart Now" after success or "Restart App" after failure.
        val callback = object : OnBackPressedCallback(classicBootstrap) {
            override fun handleOnBackPressed() {
                // Swallow the back press — restart is the only way out
            }
        }
        backCallback = callback
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Observe step changes
        viewModel.currentStep.observe(viewLifecycleOwner) { step ->
            updateStepIndicators(step)
            navigateToStep(step)
            // Hide back arrow and block back button once compilation starts.
            // Classic-bootstrap keeps it locked at all steps.
            val locked = step >= 1 || classicBootstrap
            toolbar.navigationIcon = if (locked) null
                else androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            callback.isEnabled = locked
        }

        // Classic-bootstrap: hide all the wizard chrome (toolbar + step
        // indicator) so the user sees only the GL surface. The setup is
        // headless — no import fragment, no validation card, no manual
        // tap. The bootstrap thread stages the bundled .epsa and runs
        // only the JVM-side key derivation; loader.rb handles
        // extract_loader_assets.rb itself as a prelude (see
        // tryLaunchLoaderLocked).
        if (classicBootstrap) {
            view.findViewById<View>(R.id.wizardChrome).visibility = View.GONE
        }

        val glSurface = requireActivity().findViewById<LiteRgssSurfaceView>(R.id.glSurface)

        // Classic-bootstrap wiring: post-success prefs write + trigger
        // the loader launch when the archive lands (the surface-ready
        // handler covers the other ordering).
        if (classicBootstrap) {
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
            // The loader can't launch until both the GL surface is ready
            // AND the archive keys are derived (the extract_loader_assets.rb
            // prelude needs them via env vars). Whichever event fires
            // last in this race trips tryLaunchLoaderLocked.
            viewModel.archive.observe(viewLifecycleOwner) { archive ->
                if (archive != null) {
                    synchronized(loaderLaunchLock) {
                        tryLaunchLoaderLocked(executionLocation, glSurface)
                    }
                }
            }
        }

        // If export mode, go directly to export fragment
        if (viewModel.isExportMode()) {
            toolbar.title = "Export"
            view.findViewById<View>(R.id.stepIndicator).visibility = View.GONE
            navigateToFragment(ExportApkFragment())
        } else if (classicBootstrap) {
            // Off-thread stage + key derivation. The loader's startup
            // (which includes the ~2s extract_loader_assets.rb prelude)
            // runs in its own Ruby VM thread once we set the archive,
            // so this section is just I/O + header parse + HKDF (~ms).
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
                        android.util.Log.e(LOADER_TAG, "classic bootstrap failed", it)
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
            // Start at step 0 if not already set
            if (savedInstanceState == null) {
                viewModel.currentStep.value = 0
            }
        }

        // Hook the activity-owned GL surface for the splash. The surface
        // is alpha=0 until loader.rb emits LOADER_READY_SENTINEL on its
        // LogListener (its first @window.update has actually flipped a
        // frame onto EGL), then we fade it in. Hides the punch-through
        // black frame the SurfaceView would otherwise show between attach
        // and first draw.
        glSurface.alpha = 0f
        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(LOADER_TAG, "surface ready (${w}x${h})")
            synchronized(loaderLaunchLock) {
                loaderSurfaceReady = true
                tryLaunchLoaderLocked(executionLocation, glSurface)
            }
        }
    }

    private fun navigateToStep(step: Int) {
        // Classic-bootstrap skips the import fragment: the bundled .epsa
        // is staged headlessly and the loader interpreter handles
        // extract_loader_assets.rb in-process, so step 0 should leave
        // the GL surface unobstructed until the auto-tap advances to
        // step 1.
        if (classicBootstrap && step == 0) {
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
                    // Completed
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_completed)
                    stepIndicators[i].text = "✓"
                    stepIndicators[i].setTextColor(resources.getColor(R.color.white, null))
                }
                i == currentStep -> {
                    // Active
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_active)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(resources.getColor(R.color.colorOnPrimary, null))
                }
                else -> {
                    // Pending
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
     * Fire loader.rb once the GL surface has reported its dimensions
     * (and, in classic mode, the archive keys are resolved). Caller MUST
     * hold loaderLaunchLock.
     *
     * The script mix differs by mode:
     *   - super-user: just `loader.rb`. Kotlin-side ArchiveValidator
     *     has already populated <execLoc>/loader_assets/ during the
     *     import step, so loader.rb just reads files off disk.
     *   - classic-bootstrap: `ARCHIVE_PRELUDES + extract_loader_assets.rb +
     *     loader.rb`. The extraction runs first in the splash's own
     *     Ruby VM thread (using the archive keys we bound via
     *     ScriptLocation.archive → env vars), populating loader_assets/
     *     before loader.rb's `compute_logical_size` even runs. This is
     *     why the classic flow doesn't need ImportArchiveFragment.
     */
    private fun tryLaunchLoaderLocked(executionLocation: String, glSurface: LiteRgssSurfaceView) {
        if (loaderLaunched) return
        if (!loaderSurfaceReady) return
        // Classic mode needs the archive keys before launching — the
        // extract prelude can't run without them.
        if (classicBootstrap && viewModel.archive.value == null) return

        android.util.Log.i(LOADER_TAG, "launching loader.rb (execLoc=$executionLocation, classic=$classicBootstrap)")
        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                android.util.Log.i(LOADER_TAG, logMessage.message)
                if (logMessage.message.contains(LOADER_READY_SENTINEL)) {
                    glSurface.post {
                        glSurface.animate()
                            .alpha(1f)
                            .setDuration(LOADER_FADE_IN_MS)
                            .start()
                        // Classic-mode auto-tap is gated on READY_SENTINEL
                        // (= first successful @window.update), not on
                        // archive availability — the archive is bound
                        // early (~ms of key derivation) but the loader
                        // needs ~2s for its extract prelude before a
                        // tap can be visually responded to.
                        if (classicBootstrap) {
                            classicAutoTapHandler.removeCallbacks(classicAutoTapRunnable)
                            classicAutoTapHandler.postDelayed(
                                classicAutoTapRunnable,
                                CLASSIC_AUTO_TAP_DELAY_MS,
                            )
                        }
                    }
                }
                if (logMessage.message.contains(LOADER_ICON_TAPPED_SENTINEL)) {
                    glSurface.post { onLoaderIconTapped() }
                }
            }
        }
        val onErr: (Exception) -> Unit = { e ->
            android.util.Log.e(LOADER_TAG, "loader error", e)
        }

        val loader = PsdkInterpreter.create(requireContext(), listener, onErr)
        loaderInterpreter = loader

        val classicArchive = if (classicBootstrap) viewModel.archive.value else null
        val script = if (classicBootstrap) {
            RubyScript(
                requireContext().assets,
                LOADER_SCRIPT,
                CompilationEngine.ARCHIVE_PRELUDES + EXTRACT_LOADER_ASSETS_SCRIPT,
            )
        } else {
            RubyScript(requireContext().assets, LOADER_SCRIPT)
        }
        loader.enqueue(
            script = script,
            location = ScriptLocation(executionLocation = executionLocation, archive = classicArchive),
            onComplete = { exitCode ->
                android.util.Log.i(LOADER_TAG, "loader.rb exited (code=$exitCode)")
            }
        )

        loaderLaunched = true
    }

    /**
     * Classic-bootstrap helper: copy the bundled `.epsa` from APK assets
     * into the wizard's staging file. Runs off the UI thread because the
     * payload can be tens of MB. No-op if the staging file already exists.
     */
    private fun extractBundledArchive(context: Context, executionLocation: String) {
        val staging = ArchiveValidator.getStagingFile(executionLocation)
        if (staging.exists() && staging.length() > 0) {
            android.util.Log.i(LOADER_TAG, "classic bootstrap: staging already present, skipping copy")
            return
        }
        val asset = AppModeDetector.findEpsaInAssets(context)
            ?: error("classic bootstrap: no .epsa found in APK assets")
        android.util.Log.i(LOADER_TAG, "classic bootstrap: copying assets/$asset → ${staging.absolutePath}")
        context.assets.open(asset).use { input ->
            staging.outputStream().use { out -> input.copyTo(out) }
        }
    }

    /** See classic-bootstrap docs on the old activity for two-phase rationale. */
    private fun performClassicAutoTap() {
        if (viewModel.currentStep.value != 0) return
        if (viewModel.archive.value == null) return

        viewModel.executionLocation.value?.let { execLoc ->
            try {
                java.io.File(execLoc, CompileProgressFragment.LOADER_STATUS_FILE)
                    .writeText(CompileProgressFragment.TAP_SENTINEL)
            } catch (e: Exception) {
                android.util.Log.w(LOADER_TAG, "classic auto-tap sentinel write failed", e)
            }
        }
        classicAutoTapHandler.removeCallbacks(classicAutoTapAdvanceRunnable)
        classicAutoTapHandler.postDelayed(
            classicAutoTapAdvanceRunnable,
            CLASSIC_AUTO_TAP_HOLD_MS,
        )
    }

    private fun onLoaderIconTapped() {
        if (viewModel.currentStep.value != 0) return
        if (viewModel.archive.value == null) return
        viewModel.currentStep.value = 1
    }

    override fun onDestroy() {
        // Cancel any pending classic auto-tap before tearing the loader
        // down.
        classicAutoTapHandler.removeCallbacks(classicAutoTapRunnable)
        classicAutoTapHandler.removeCallbacks(classicAutoTapAdvanceRunnable)

        // Order matters: NativeSurface.requestClose pushes a sf::Event::Closed
        // into the loader's DisplayWindow event queue. loader.rb's render
        // loop drains it on the next frame, on_closed raises
        // rb_eClosedWindow, the script unwinds, and the worker thread
        // becomes free. Only then does loaderInterpreter.close() — which
        // queues a synchronous :stop on that same worker thread — actually
        // return. Without the requestClose, close() blocks the UI thread
        // forever because :stop sits behind an infinite render loop.
        com.scorbutics.litergss.NativeSurface.requestClose()
        loaderInterpreter?.close()
        loaderInterpreter = null

        // Drop the surface listener so a re-attached fragment (or anyone
        // else watching the activity-owned surface next) doesn't fire the
        // now-dead loader callback.
        activity?.findViewById<LiteRgssSurfaceView>(R.id.glSurface)?.onSurfaceReady = null

        super.onDestroy()
    }

    companion object {
        const val ARG_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        const val ARG_APK_FOLDER_LOCATION = "APK_FOLDER_LOCATION"

        /**
         * When true, the wizard treats itself as the first-launch setup
         * flow for classic mode: it auto-stages the bundled .epsa out of
         * APK assets, locks back navigation throughout, and writes
         * [ClassicModeState.STATE_SETUP_COMPLETE] to prefs when
         * compilation succeeds.
         */
        const val ARG_CLASSIC_BOOTSTRAP = "CLASSIC_BOOTSTRAP"

        private const val LOADER_SCRIPT = "loader.rb"

        /**
         * Prelude the loader script pulls in only in classic-bootstrap
         * mode. Same file the Kotlin-side ArchiveValidator runs in its
         * one-shot validation interpreter in super-user mode, but here
         * it runs inside the loader's own Ruby VM thread so the wizard
         * doesn't need a separate validation pass / fragment.
         */
        private const val EXTRACT_LOADER_ASSETS_SCRIPT = "extract_loader_assets.rb"

        private const val LOADER_TAG = "CompileWizard-loader"

        private const val LOADER_READY_SENTINEL = "[loader-ready]"
        private const val LOADER_ICON_TAPPED_SENTINEL = "[loader-icon-tapped]"
        private const val LOADER_FADE_IN_MS = 180L

        private const val CLASSIC_AUTO_TAP_DELAY_MS = 2000L
        private const val CLASSIC_AUTO_TAP_HOLD_MS = 200L

        fun newInstance(
            executionLocation: String,
            classicBootstrap: Boolean = false,
            apkFolderLocation: String? = null,
        ): CompileWizardFragment {
            return CompileWizardFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_EXECUTION_LOCATION, executionLocation)
                    putBoolean(ARG_CLASSIC_BOOTSTRAP, classicBootstrap)
                    if (apkFolderLocation != null) putString(ARG_APK_FOLDER_LOCATION, apkFolderLocation)
                }
            }
        }
    }
}
