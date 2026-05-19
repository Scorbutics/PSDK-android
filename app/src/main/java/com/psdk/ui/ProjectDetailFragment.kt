package com.psdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.psdk.GameLauncher
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class ProjectDetailFragment : Fragment() {

    // Scoped to the parent (ProjectHubFragment) so detail/list share state.
    // Was activityViewModels() when the hub was its own Activity.
    private val viewModel: ProjectHubViewModel by viewModels({ requireParentFragment() })

    private val startGameLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_project_detail, container, false)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the wizard (popped off the activity back stack)
        // — repaint state. Used to be triggered by an ActivityResult
        // callback when the wizard was its own activity.
        view?.let { onViewCreated(it, null) }
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

        // Compile button — pushes the wizard onto the host activity's
        // fragment stack so it covers the hub entirely. The shared GL
        // surface lives on the activity, so navigation between hub and
        // wizard doesn't tear the surface down.
        compileButton.setOnClickListener {
            launchWizard(executionLocation, apkFolderLocation = null)
        }

        // Export button
        exportButton.visibility = if (releaseExists) View.VISIBLE else View.GONE
        exportButton.setOnClickListener {
            launchWizard(executionLocation, apkFolderLocation = releaseLocation)
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
        val compilationErrorLogFile = File(executionLocation, CompilationEngine.COMPILATION_ERROR_LOG_FILE)
        if (compilationLogFile.exists()) {
            compilationLogsButton.visibility = View.VISIBLE
            compilationLogsButton.setOnClickListener {
                val fragment = LogBottomSheetFragment.newInstance(
                    outputLogPath = compilationLogFile.absolutePath,
                    errorLogPath = if (compilationErrorLogFile.exists()) compilationErrorLogFile.absolutePath else null,
                    title = "Compilation Logs"
                )
                fragment.show(parentFragmentManager, "compilation_logs")
            }
        }
    }

    private fun launchWizard(executionLocation: String, apkFolderLocation: String?) {
        val fragment = CompileWizardFragment.newInstance(
            executionLocation = executionLocation,
            classicBootstrap = false,
            apkFolderLocation = apkFolderLocation,
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
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
