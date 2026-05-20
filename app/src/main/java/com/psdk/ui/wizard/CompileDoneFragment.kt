package com.psdk.ui.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.CompileWizardFragment
import com.psdk.ui.CompileWizardViewModel
import com.psdk.ui.LoaderPaths
import com.psdk.ui.LogBottomSheetFragment
import java.io.File
import java.nio.charset.StandardCharsets

class CompileDoneFragment : Fragment() {

    // Scoped to the parent (CompileWizardFragment) so all wizard steps
    // share the same VM instance — was activityViewModels() when the
    // wizard was its own Activity.
    private val viewModel: CompileWizardViewModel by viewModels({ requireParentFragment() })

    private var dismissing: Boolean = false
    private var compilationWasSuccessful: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_compile_done, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val resultCard = view.findViewById<MaterialCardView>(R.id.resultCard)
        val resultIcon = view.findViewById<ImageView>(R.id.resultIcon)
        val resultTitle = view.findViewById<TextView>(R.id.resultTitle)
        val resultMessage = view.findViewById<TextView>(R.id.resultMessage)
        val errorCard = view.findViewById<MaterialCardView>(R.id.errorCard)
        val errorScroll = view.findViewById<ScrollView>(R.id.errorScroll)
        val errorContent = view.findViewById<TextView>(R.id.errorContent)
        val primaryButton = view.findViewById<MaterialButton>(R.id.primaryActionButton)
        val secondaryButton = view.findViewById<MaterialButton>(R.id.secondaryActionButton)

        // Slide the card up from below its resting position. doOnPreDraw
        // runs after layout but before the first frame, so we set
        // translationY to "fully off-screen below" before the user
        // ever sees the card at rest — no flash. The loader.rb side
        // lifts its centerpiece in parallel (triggered by the LIFT
        // sentinel CompileProgressFragment wrote on finish), so the
        // composition reads as "splash makes room, result rises in".
        resultCard.doOnPreDraw {
            val bottomMargin = (resultCard.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            resultCard.translationY = (resultCard.height + bottomMargin).toFloat()
            resultCard.animate()
                .translationY(0f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }

        val executionLocation = viewModel.executionLocation.value ?: ""
        val logFile = File(executionLocation, CompilationEngine.COMPILATION_LOG_FILE)
        val errorLogFile = File(executionLocation, CompilationEngine.COMPILATION_ERROR_LOG_FILE)
        compilationWasSuccessful = viewModel.compilationSuccess.value == true

        if (!compilationWasSuccessful) {
            showErrorPreview(errorCard, errorScroll, errorContent, errorLogFile)
        }

        if (compilationWasSuccessful) {
            resultIcon.setImageResource(R.drawable.ic_check)
            resultIcon.setColorFilter(resources.getColor(R.color.colorSuccess, null))
            resultTitle.text = "Compilation Successful"
            resultMessage.text = "Your game is ready to play. Tap to continue."
            primaryButton.visibility = View.GONE
        } else {
            resultIcon.setImageResource(R.drawable.ic_error)
            resultIcon.setColorFilter(resources.getColor(R.color.colorError, null))
            resultTitle.text = "Compilation Failed"
            resultMessage.text = "Check the logs for details, then tap to dismiss and retry."
            primaryButton.visibility = View.GONE

            secondaryButton.visibility = View.VISIBLE
            secondaryButton.text = "View Logs"
            secondaryButton.setOnClickListener { showLogs(logFile, errorLogFile) }
        }

        // Tap-anywhere dismiss for both success and failure. Clicks on
        // the View Logs button (failure path) are consumed by the button
        // and don't bubble here. dismissCard then branches on the
        // captured [compilationWasSuccessful] to call the right handler
        // on the parent wizard.
        view.isClickable = true
        view.setOnClickListener { dismissCard(resultCard) }
    }

    private fun showErrorPreview(
        card: MaterialCardView,
        scroll: ScrollView,
        contentView: TextView,
        errorLogFile: File,
    ) {
        if (!errorLogFile.exists()) {
            card.visibility = View.GONE
            return
        }
        val text = try {
            errorLogFile.readText(StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
        if (text.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        contentView.text = text
        card.visibility = View.VISIBLE
        // Jump to the bottom so the most-recent stack frames are visible
        // without the user having to scroll. post() defers until after
        // the ScrollView has measured its child — fullScroll is a no-op
        // before that.
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showLogs(logFile: File, errorLogFile: File) {
        if (logFile.exists()) {
            val fragment = LogBottomSheetFragment.newInstance(
                outputLogPath = logFile.absolutePath,
                errorLogPath = if (errorLogFile.exists()) errorLogFile.absolutePath else null,
                title = "Compilation Logs"
            )
            fragment.show(parentFragmentManager, "compilation_logs")
        }
    }

    private fun dismissCard(card: MaterialCardView) {
        if (dismissing) return
        dismissing = true

        // Mirror of CompileProgressFragment's LIFT_SENTINEL push: settle
        // the splash centerpiece back to its resting position in parallel
        // with the card slide-out. The loader picks this up on its next
        // file poll (~133 ms); minor de-sync vs. the card animation is
        // fine for the same reason it was on the way up.
        try {
            LoaderPaths.statusFile(requireContext()).writeText(UNLIFT_SENTINEL)
        } catch (e: Exception) {
            // Best-effort — the card slide-out runs regardless.
        }

        val bottomMargin = (card.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        card.animate()
            .translationY((card.height + bottomMargin).toFloat())
            .setDuration(SLIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                val parent = parentFragment as? CompileWizardFragment ?: return@withEndAction
                if (compilationWasSuccessful) {
                    parent.onCompileDoneDismissed()
                } else {
                    parent.onCompileFailureDismissed()
                }
            }
            .start()
    }

    companion object {
        // Slide is tuned a touch slower than the loader.rb lift
        // (~LIFT_DURATION_FRAMES = 36 frames = 0.6s) so the card
        // settles a beat after the splash centerpiece reaches its
        // new resting height — gives the eye a chance to track the
        // logo first, then read the result.
        private const val SLIDE_DURATION_MS = 520L

        /**
         * Matches Loader::StatusFile::UNLIFT_SENTINEL in
         * loader/status_file.rb verbatim. Writing this string into
         * [LoaderPaths.statusFile] asks the splash to settle its
         * centerpiece back to its resting position — the inverse of
         * CompileProgressFragment's LIFT_SENTINEL.
         */
        const val UNLIFT_SENTINEL = "<<UNLIFT>>"
    }
}
