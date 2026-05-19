package com.psdk.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.psdk.R
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.psdk.ui.wizard.CompileDoneFragment
import com.psdk.ui.wizard.CompileProgressFragment
import com.psdk.ui.wizard.ExportApkFragment
import com.psdk.ui.wizard.ImportArchiveFragment
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompileWizardActivity : AppCompatActivity() {

    private val viewModel: CompileWizardViewModel by viewModels()

    private lateinit var stepIndicators: List<TextView>
    private lateinit var connectors: List<View>

    // Splash interpreter — runs loader.rb in its own Ruby Thread inside
    // the process VM. No archive bound: loader.rb reads its assets from
    // <executionLocation>/loader_assets/ (written during validation) and
    // never touches PhysFS. Lifecycle: created once the GL surface is
    // ready and the execution location is known; closed in onDestroy.
    private var loaderInterpreter: PsdkInterpreter? = null

    private val loaderLaunchLock = Any()
    private var loaderSurfaceReady = false
    private var loaderLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compile_wizard)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            // Block back navigation once compilation has started (VM is dirty)
            val step = viewModel.currentStep.value ?: 0
            if (step < 1) {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Initialize step indicators
        stepIndicators = listOf(
            findViewById(R.id.stepIndicator1),
            findViewById(R.id.stepIndicator2),
            findViewById(R.id.stepIndicator3)
        )
        connectors = listOf(
            findViewById(R.id.connector12),
            findViewById(R.id.connector23)
        )

        // Get intent extras
        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
        viewModel.executionLocation.value = executionLocation
        intent.getStringExtra(EXTRA_APK_FOLDER_LOCATION)?.let {
            viewModel.apkFolderLocation.value = it
        }

        // Wipe any stale loader status file from a previous wizard
        // session. The file lives under the app data dir and survives
        // process restarts, so a leftover "<<LIFT>>" from last run would
        // fire the splash lift immediately on launch (loader.rb polls
        // this file every ~8 frames). Gated on engine == null so a
        // mid-compile rotation — where the activity is re-created but
        // the ViewModel-owned engine keeps running — doesn't erase the
        // in-flight sub-step.
        if (savedInstanceState == null && viewModel.getEngine() == null) {
            try {
                java.io.File(executionLocation, CompileProgressFragment.LOADER_STATUS_FILE).delete()
            } catch (e: Exception) {
                // best-effort — a stale file just delays the user's first lift by one compile
            }
        }

        // Block system back button once compilation starts (VM is dirty)
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Swallow the back press — restart is the only way out
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Observe step changes
        viewModel.currentStep.observe(this) { step ->
            updateStepIndicators(step)
            navigateToStep(step)
            // Hide back arrow and block back button once compilation starts
            val locked = step >= 1
            toolbar.navigationIcon = if (locked) null else getDrawable(R.drawable.ic_arrow_back)
            backCallback.isEnabled = locked
        }

        // If export mode, go directly to export fragment
        if (viewModel.isExportMode()) {
            toolbar.title = "Export"
            findViewById<View>(R.id.stepIndicator).visibility = View.GONE
            navigateToFragment(ExportApkFragment())
        } else {
            // Start at step 0 if not already set
            if (savedInstanceState == null) {
                viewModel.currentStep.value = 0
            }
        }

        // Hook the GL surface for the splash. The surface is alpha=0 until
        // loader.rb emits LOADER_READY_SENTINEL on its LogListener (its
        // first @window.update has actually flipped a frame onto EGL),
        // then we fade it in. Hides the punch-through black frame the
        // SurfaceView would otherwise show between attach and first draw.
        val glSurface = findViewById<LiteRgssSurfaceView>(R.id.glSurface)
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
        val fragment = when (step) {
            0 -> ImportArchiveFragment()
            1 -> CompileProgressFragment()
            2 -> CompileDoneFragment()
            else -> return
        }
        navigateToFragment(fragment)
    }

    private fun navigateToFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.wizardContainer, fragment)
            .commit()
    }

    private fun updateStepIndicators(currentStep: Int) {
        for (i in stepIndicators.indices) {
            when {
                i < currentStep -> {
                    // Completed
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_completed)
                    stepIndicators[i].text = "✓"
                    stepIndicators[i].setTextColor(getColor(R.color.white))
                }
                i == currentStep -> {
                    // Active
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_active)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(getColor(R.color.colorOnPrimary))
                }
                else -> {
                    // Pending
                    stepIndicators[i].setBackgroundResource(R.drawable.bg_step_pending)
                    stepIndicators[i].text = (i + 1).toString()
                    stepIndicators[i].setTextColor(getColor(R.color.colorOnSurfaceVariant))
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
     * Fire loader.rb once the GL surface has reported its dimensions.
     * Caller MUST hold loaderLaunchLock.
     */
    private fun tryLaunchLoaderLocked(executionLocation: String, glSurface: LiteRgssSurfaceView) {
        if (loaderLaunched) return
        if (!loaderSurfaceReady) return

        android.util.Log.i(LOADER_TAG, "launching loader.rb (execLoc=$executionLocation)")
        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                android.util.Log.i(LOADER_TAG, logMessage.message)
                if (logMessage.message.contains(LOADER_READY_SENTINEL)) {
                    glSurface.post {
                        glSurface.animate()
                            .alpha(1f)
                            .setDuration(LOADER_FADE_IN_MS)
                            .start()
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

        val loader = PsdkInterpreter.create(this, listener, onErr)
        loaderInterpreter = loader
        // No archive bound — loader.rb reads from <execLoc>/loader_assets/.
        loader.enqueue(
            script = RubyScript(assets, LOADER_SCRIPT),
            location = ScriptLocation(executionLocation = executionLocation, archive = null),
            onComplete = { exitCode ->
                android.util.Log.i(LOADER_TAG, "loader.rb exited (code=$exitCode)")
            }
        )

        loaderLaunched = true
    }

    /**
     * Loader.rb emits ICON_TAPPED_SENTINEL on every touch that lands inside
     * the logo's hit rect. We're the arbiter: only act if a validated
     * archive is in hand AND we're still on the import step. Once we
     * advance, [currentStep] changes and any duplicate sentinel from a
     * fast double-tap is filtered by the same check on the next call.
     */
    private fun onLoaderIconTapped() {
        if (viewModel.currentStep.value != 0) return
        if (viewModel.archive.value == null) return
        viewModel.currentStep.value = 1
    }

    override fun onDestroy() {
        // Order matters: NativeSurface.requestClose pushes a sf::Event::Closed
        // into the loader's DisplayWindow event queue. loader.rb's render
        // loop drains it on the next frame, on_closed raises
        // rb_eClosedWindow, the script unwinds, and the worker thread
        // becomes free.
        //
        // Only then does loaderInterpreter.close() — which queues a
        // synchronous :stop on that same worker thread — actually return.
        // Without the requestClose, close() blocks the UI thread forever
        // because :stop sits behind an infinite render loop, and the next
        // CompileWizardActivity.onCreate can't run cleanly because the
        // old WindowImpl/EglContext still own the SFML singletons.
        com.scorbutics.litergss.NativeSurface.requestClose()
        loaderInterpreter?.close()
        loaderInterpreter = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        const val EXTRA_APK_FOLDER_LOCATION = "APK_FOLDER_LOCATION"

        private const val LOADER_SCRIPT = "loader.rb"

        /** Single tag for the splash interpreter lifecycle —
         *  searchable in logcat with `adb logcat -s 'CompileWizard-loader'`. */
        private const val LOADER_TAG = "CompileWizard-loader"

        /** Matches Loader::READY_SENTINEL in loader.rb. */
        private const val LOADER_READY_SENTINEL = "[loader-ready]"
        /** Matches Loader::ICON_TAPPED_SENTINEL in loader.rb. */
        private const val LOADER_ICON_TAPPED_SENTINEL = "[loader-icon-tapped]"
        private const val LOADER_FADE_IN_MS = 180L
    }
}
