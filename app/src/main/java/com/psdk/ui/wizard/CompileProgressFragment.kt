package com.psdk.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.compilation.CompileSubStepMapper
import com.psdk.ui.CompileWizardViewModel
import com.psdk.ui.LoaderPaths
import java.io.File

/**
 * Headless harness for step 1 of the wizard.
 *
 * The visible UI during compile is the GL surface running loader.rb —
 * its `status_text` shows the current sub-step. We get text into Ruby
 * via a polled file ([LoaderPaths.statusFile]) instead of bringing up an
 * Android overlay, because we already pay for the splash and a second
 * UI layer just competes for the user's eye.
 *
 * The fragment owns nothing visual on purpose: the wizard's toolbar
 * and step indicator are the only Kotlin-side chrome, and the full
 * per-step log lives on disk for CompileDoneFragment's "View Logs".
 */
class CompileProgressFragment : Fragment() {

    // Scoped to the parent (CompileWizardFragment) so all wizard steps
    // share the same VM instance — was activityViewModels() when the
    // wizard was its own Activity.
    private val viewModel: CompileWizardViewModel by viewModels({ requireParentFragment() })

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
                //
                // The tail after the sentinel becomes the new status
                // text (loader/status_file.rb strips the sentinel then
                // falls through to update_status), so the splash flips
                // from the last sub-step ("Finalizing…") to the final
                // outcome as it lifts.
                val finalStatus = if (success) "Compilation done" else "Compilation failed"
                pushStatus("$LIFT_SENTINEL$finalStatus")
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
     * Write a one-line status into [LoaderPaths.statusFile], the file the
     * splash's loader.rb polls each frame. We debounce identical writes
     * so the loader's mtime-based poll doesn't re-read the same string
     * repeatedly.
     *
     * The status file lives next to the loader's assets dir (app data
     * dir), not next to the wizard's per-project executionLocation — the
     * loader is activity-scoped since Cut 2 and chdir's to one stable
     * location regardless of which project is compiling.
     *
     * Best-effort: a failed write (disk full, permission flake) just
     * means the splash keeps the previous label until the next update
     * lands. That's a strictly worse UX than the happy path, never a
     * broken one.
     */
    private fun pushStatus(text: String) {
        if (text == lastStatusWritten) return
        try {
            LoaderPaths.statusFile(requireContext()).writeText(text)
            lastStatusWritten = text
        } catch (e: Exception) {
            // Swallow — see kdoc above.
        }
    }

    companion object {
        /**
         * Matches Loader::StatusFile::LIFT_SENTINEL in
         * loader/status_file.rb verbatim. Writing this string to
         * [LoaderPaths.statusFile] asks the splash to lift its
         * centerpiece upward — see StatusFile#poll and the staggered
         * SceneState::LIFT_DELAY_* offsets in the Icon / Text layers.
         */
        const val LIFT_SENTINEL = "<<LIFT>>"

        /**
         * Matches Loader::StatusFile::TAP_SENTINEL in
         * loader/status_file.rb verbatim. Writing this string asks the
         * splash to play the release-on-icon combo (bang + shake +
         * spin) without waiting for a real finger. Currently only the
         * classic-setup bootstrap path uses it — see
         * CompileWizardFragment.performClassicAutoTap.
         */
        const val TAP_SENTINEL = "<<TAP>>"
    }
}
