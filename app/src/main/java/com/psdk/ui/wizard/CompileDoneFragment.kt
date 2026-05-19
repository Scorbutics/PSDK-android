package com.psdk.ui.wizard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.CompileWizardViewModel
import com.psdk.ui.LogBottomSheetFragment
import java.io.File
import java.nio.charset.StandardCharsets

class CompileDoneFragment : Fragment() {

    // Scoped to the parent (CompileWizardFragment) so all wizard steps
    // share the same VM instance — was activityViewModels() when the
    // wizard was its own Activity.
    private val viewModel: CompileWizardViewModel by viewModels({ requireParentFragment() })

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
        val errorContent = view.findViewById<TextView>(R.id.errorContent)
        val primaryButton = view.findViewById<MaterialButton>(R.id.primaryActionButton)
        val secondaryButton = view.findViewById<MaterialButton>(R.id.secondaryActionButton)
        val tertiaryButton = view.findViewById<MaterialButton>(R.id.tertiaryActionButton)

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
        val success = viewModel.compilationSuccess.value == true

        if (!success) {
            showErrorPreview(errorCard, errorContent, errorLogFile)
        }

        if (success) {
            resultIcon.setImageResource(R.drawable.ic_check)
            resultIcon.setColorFilter(resources.getColor(R.color.colorSuccess, null))
            resultTitle.text = "Compilation Successful"
            resultMessage.text = "The app must restart to reset the internal engine before the game can be tested."
            primaryButton.text = "Restart Now"
            primaryButton.setOnClickListener { restartApp() }
        } else {
            resultIcon.setImageResource(R.drawable.ic_error)
            resultIcon.setColorFilter(resources.getColor(R.color.colorError, null))
            resultTitle.text = "Compilation Failed"
            resultMessage.text = "Check the logs for details, then retry the compilation."
            primaryButton.text = "Retry"
            primaryButton.setOnClickListener { retryCompilation() }

            tertiaryButton.visibility = View.VISIBLE
            tertiaryButton.text = "Restart App"
            tertiaryButton.setOnClickListener { restartApp() }
        }

        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "View Logs"
        secondaryButton.setOnClickListener { showLogs(logFile, errorLogFile) }
    }

    private fun showErrorPreview(card: MaterialCardView, contentView: TextView, errorLogFile: File) {
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

    private fun retryCompilation() {
        viewModel.compilationSuccess.value = null
        viewModel.currentStep.value = 1
    }

    private fun restartApp() {
        val activity = requireActivity()
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        // Slide is tuned a touch slower than the loader.rb lift
        // (~LIFT_DURATION_FRAMES = 36 frames = 0.6s) so the card
        // settles a beat after the splash centerpiece reaches its
        // new resting height — gives the eye a chance to track the
        // logo first, then read the result.
        private const val SLIDE_DURATION_MS = 520L
    }
}
