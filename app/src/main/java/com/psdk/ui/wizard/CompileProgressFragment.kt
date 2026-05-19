package com.psdk.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.compilation.CompileSubStepMapper
import com.psdk.ui.CompileWizardViewModel
import java.io.File

/**
 * Headless harness for step 1 of the wizard.
 *
 * The visible UI during compile is the GL surface running loader.rb —
 * its `status_text` shows the current sub-step. We get text into Ruby
 * via a polled file (see [LOADER_STATUS_FILE]) instead of bringing up an
 * Android overlay, because we already pay for the splash and a second
 * UI layer just competes for the user's eye.
 *
 * The fragment owns nothing visual on purpose: the activity's toolbar
 * and step indicator are the only Kotlin-side chrome, and the full
 * per-step log lives on disk for CompileDoneFragment's "View Logs".
 */
class CompileProgressFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()

    private var lastStatusWritten: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_compile_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Engine is owned by the ViewModel so a fragment re-creation
        // (e.g. orientation change) doesn't restart compilation. The
        // first call to ensureCompilationStarted starts the engine; later
        // calls return the same instance. setCallback then atomically
        // swaps in our fragment-scoped callback and returns a snapshot.
        val engine = viewModel.ensureCompilationStarted(requireContext()) ?: return

        val callback = object : CompilationEngine.CompilationCallback {
            override fun onStepStarted(stepIndex: Int, stepName: String) {
                // Step 3 ("Copying saves") is the engine's housekeeping
                // tail. By the time it fires we've already shown
                // "Finalizing…" via CompileSubStepMapper, and a fresh
                // "Copying saves…" label right before the lift reads as
                // an out-of-context interruption — the user is done,
                // the compile result card is what matters next.
                if (stepIndex >= 3) return
                pushStatus("$stepName...")
            }

            override fun onStepCompleted(stepIndex: Int, success: Boolean) {
                // Sub-step labels carry the within-step granularity; the
                // step-completed transition is implicit in the next
                // step's onStepStarted, so no extra status push here.
            }

            override fun onLogMessage(stepIndex: Int, message: String) {
                val sub = CompileSubStepMapper.fromLog(message) ?: return
                pushStatus(sub.label)
            }

            override fun onCompilationFinished(success: Boolean, logFile: File, errorLogFile: File) {
                // Trigger the splash lift BEFORE the wizard advances —
                // the loader picks up <<LIFT>> on its next file poll
                // (≤133ms) and the icon + title + version + status
                // animate upward to make room for CompileDoneFragment's
                // bottom card sliding up. The card's slide animation
                // and the splash lift play in parallel; minor de-sync
                // is fine because the card and lifted content land in
                // separate regions of the screen.
                pushStatus(LIFT_SENTINEL)
                activity?.runOnUiThread {
                    viewModel.compilationSuccess.value = success
                    viewModel.currentStep.value = 2
                }
            }
        }

        val snapshot = engine.setCallback(callback)

        // If the engine ran ahead of us while we were detached, fast-
        // forward straight to the done step. compilationSuccess uses
        // setValue (UI thread) because we're on it.
        snapshot.finishResult?.let { fr ->
            viewModel.compilationSuccess.value = fr.success
            viewModel.currentStep.value = 2
        }
    }

    override fun onDestroyView() {
        // Detach so the engine doesn't dispatch into a dead view. The
        // engine itself keeps running and accumulating state for whoever
        // attaches next (or for ViewModel.onCleared to drain).
        viewModel.getEngine()?.setCallback(null)
        super.onDestroyView()
    }

    /**
     * Write a one-line status into `<executionLocation>/.loader_status`,
     * the file the splash's loader.rb polls each frame. We debounce
     * identical writes so the loader's mtime-based poll doesn't re-read
     * the same string repeatedly.
     *
     * Best-effort: a failed write (executionLocation missing, disk full,
     * permission flake) just means the splash keeps the previous label
     * until the next update lands. That's a strictly worse UX than the
     * happy path, never a broken one.
     */
    private fun pushStatus(text: String) {
        if (text == lastStatusWritten) return
        val executionLocation = viewModel.executionLocation.value ?: return
        try {
            File(executionLocation, LOADER_STATUS_FILE).writeText(text)
            lastStatusWritten = text
        } catch (e: Exception) {
            // Swallow — see kdoc above.
        }
    }

    companion object {
        /** Matches Loader::LOADER_STATUS_FILE in loader.rb verbatim. */
        const val LOADER_STATUS_FILE = ".loader_status"

        /**
         * Matches Loader::LIFT_SENTINEL in loader.rb verbatim. Writing
         * this string to LOADER_STATUS_FILE asks the splash to lift its
         * centerpiece upward — see loader.rb's [poll_external_status]
         * and the staggered offsets in animate_icon / animate_text_layer.
         */
        const val LIFT_SENTINEL = "<<LIFT>>"
    }
}
