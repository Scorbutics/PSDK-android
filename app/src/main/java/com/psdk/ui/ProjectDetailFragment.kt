package com.psdk.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.psdk.GameLauncher
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ProjectDetailFragment : Fragment() {

    private val viewModel: ProjectHubViewModel by activityViewModels()

    private val startGameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val compileWizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh UI when returning from compile wizard
        refreshUI()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_project_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val projectName = view.findViewById<TextView>(R.id.projectName)
        val projectVersion = view.findViewById<TextView>(R.id.projectVersion)
        val testGameButton = view.findViewById<MaterialButton>(R.id.testGameButton)
        val compileButton = view.findViewById<MaterialButton>(R.id.compileButton)
        val exportButton = view.findViewById<MaterialButton>(R.id.exportButton)
        val gameLogsButton = view.findViewById<MaterialButton>(R.id.gameLogsButton)
        val compilationLogsButton = view.findViewById<MaterialButton>(R.id.compilationLogsButton)

        val project = viewModel.selectedProject.value ?: return
        val executionLocation = project.directory
        val releaseLocation = "$executionLocation/Release"

        projectName.text = project.name
        projectVersion.text = "Version: ${readVersion(releaseLocation)}"

        val releaseExists = File(releaseLocation).exists()

        // Test Game button
        testGameButton.visibility = if (releaseExists) View.VISIBLE else View.GONE
        testGameButton.setOnClickListener {
            GameLauncher.launch(requireActivity() as androidx.activity.ComponentActivity, executionLocation, releaseLocation, startGameLauncher)
        }

        // Compile button
        compileButton.setOnClickListener {
            val intent = Intent(requireContext(), CompileWizardActivity::class.java)
            intent.putExtra(CompileWizardActivity.EXTRA_EXECUTION_LOCATION, executionLocation)
            compileWizardLauncher.launch(intent)
        }

        // Export button
        exportButton.visibility = if (releaseExists) View.VISIBLE else View.GONE
        exportButton.setOnClickListener {
            val intent = Intent(requireContext(), CompileWizardActivity::class.java)
            intent.putExtra(CompileWizardActivity.EXTRA_EXECUTION_LOCATION, executionLocation)
            intent.putExtra(CompileWizardActivity.EXTRA_APK_FOLDER_LOCATION, releaseLocation)
            compileWizardLauncher.launch(intent)
        }

        // Game logs
        gameLogsButton.setOnClickListener {
            val fragment = LogBottomSheetFragment.newInstance(
                outputLogPath = "$executionLocation/last_stdout.log",
                errorLogPath = "$releaseLocation/Error.log",
                title = "Game Logs"
            )
            fragment.show(parentFragmentManager, "game_logs")
        }

        // Compilation logs
        val compilationLogFile = File(executionLocation, CompilationEngine.COMPILATION_LOG_FILE)
        if (compilationLogFile.exists()) {
            compilationLogsButton.visibility = View.VISIBLE
            compilationLogsButton.setOnClickListener {
                val fragment = LogBottomSheetFragment.newInstance(
                    outputLogPath = compilationLogFile.absolutePath,
                    errorLogPath = null,
                    title = "Compilation Logs"
                )
                fragment.show(parentFragmentManager, "compilation_logs")
            }
        }
    }

    private fun refreshUI() {
        // Re-navigate to detail to refresh state
        val project = viewModel.selectedProject.value ?: return
        view?.let { onViewCreated(it, null) }
    }

    private fun readVersion(releaseLocation: String): String {
        return try {
            val encoded = Files.readAllBytes(Paths.get("$releaseLocation/pokemonsdk/version.txt"))
            val versionNumeric = String(encoded, StandardCharsets.UTF_8).trim().toLong()
            val majorVersion = versionNumeric shr 8
            "${majorVersion}.${versionNumeric - (majorVersion shl 8)}"
        } catch (e: Exception) {
            "Not available"
        }
    }
}
