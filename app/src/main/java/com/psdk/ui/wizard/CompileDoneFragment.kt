package com.psdk.ui.wizard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.ui.CompileWizardViewModel
import com.psdk.ui.LogBottomSheetFragment
import java.io.File

class CompileDoneFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_compile_done, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val resultIcon = view.findViewById<ImageView>(R.id.resultIcon)
        val resultTitle = view.findViewById<TextView>(R.id.resultTitle)
        val resultMessage = view.findViewById<TextView>(R.id.resultMessage)
        val primaryButton = view.findViewById<MaterialButton>(R.id.primaryActionButton)
        val secondaryButton = view.findViewById<MaterialButton>(R.id.secondaryActionButton)

        val executionLocation = viewModel.executionLocation.value ?: ""
        val logFile = File(executionLocation, CompilationEngine.COMPILATION_LOG_FILE)
        val releaseExists = File(executionLocation, "Release").exists()

        // The Ruby VM is now in a dirty state — a restart is mandatory
        // regardless of compilation success or failure.
        if (releaseExists) {
            resultIcon.setImageResource(R.drawable.ic_check)
            resultIcon.setColorFilter(resources.getColor(R.color.colorSuccess, null))
            resultTitle.text = "Compilation Successful"
            resultMessage.text = "The app must restart to reset the internal engine before the game can be tested."
        } else {
            resultIcon.setImageResource(R.drawable.ic_error)
            resultIcon.setColorFilter(resources.getColor(R.color.colorError, null))
            resultTitle.text = "Compilation Failed"
            resultMessage.text = "The app must restart to reset the internal engine. Check the logs after restart for details."
        }

        primaryButton.text = "Restart Now"
        primaryButton.setOnClickListener { restartApp() }

        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "View Logs"
        secondaryButton.setOnClickListener { showLogs(logFile) }
    }

    private fun showLogs(logFile: File) {
        if (logFile.exists()) {
            val fragment = LogBottomSheetFragment.newInstance(
                outputLogPath = logFile.absolutePath,
                errorLogPath = null,
                title = "Compilation Logs"
            )
            fragment.show(parentFragmentManager, "compilation_logs")
        }
    }

    private fun restartApp() {
        val activity = requireActivity()
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
