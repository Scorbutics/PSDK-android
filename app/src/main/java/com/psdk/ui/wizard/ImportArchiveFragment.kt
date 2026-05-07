package com.psdk.ui.wizard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.psdk.R
import com.psdk.compilation.ArchiveValidator
import com.psdk.ui.CompileWizardViewModel
import com.psdk.zip.EpsaDecryptor

class ImportArchiveFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()

    private lateinit var importButton: MaterialButton
    private lateinit var startCompilationButton: MaterialButton
    private lateinit var validationContainer: LinearLayout
    private lateinit var validationIcon: ImageView
    private lateinit var validationText: TextView
    private lateinit var processingContainer: LinearLayout

    private val chooseFileResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val executionLocation = viewModel.executionLocation.value ?: return@registerForActivityResult

            val stagingFile = ArchiveValidator.getStagingFile(executionLocation)
            if (!stagingFile.exists()) stagingFile.createNewFile()

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                stagingFile.outputStream().use { out -> input.copyTo(out) }
            }

            if (EpsaDecryptor.isEpsaFile(stagingFile)) {
                validateArchive(stagingFile, executionLocation)
            } else {
                stagingFile.delete()
                showValidationError("Only encrypted .epsa archives are supported.")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_import_archive, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        importButton = view.findViewById(R.id.importArchiveButton)
        startCompilationButton = view.findViewById(R.id.startCompilationButton)
        validationContainer = view.findViewById(R.id.validationContainer)
        validationIcon = view.findViewById(R.id.validationIcon)
        validationText = view.findViewById(R.id.validationText)
        processingContainer = view.findViewById(R.id.processingContainer)

        importButton.setOnClickListener { selectFile() }

        startCompilationButton.setOnClickListener {
            viewModel.currentStep.value = 1
        }

        // Check if we already have a valid archive
        val executionLocation = viewModel.executionLocation.value
        if (executionLocation != null) {
            val result = ArchiveValidator.validateExisting(executionLocation)
            if (result.isValid) {
                viewModel.archivePath.value = result.archivePath
                showValidationSuccess()
            }
        }
    }

    private fun selectFile() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            chooseFileResultLauncher.launch(Intent.createChooser(intent, "Choose a file"))
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No suitable File Manager was found.", Toast.LENGTH_LONG).show()
        }
    }

    private fun validateArchive(stagingFile: java.io.File, executionLocation: String) {
        processingContainer.visibility = View.VISIBLE
        validationContainer.visibility = View.GONE
        startCompilationButton.isEnabled = false

        val ctx = requireContext().applicationContext
        Thread {
            val result = ArchiveValidator.validate(ctx, executionLocation, stagingFile)
            activity?.runOnUiThread {
                processingContainer.visibility = View.GONE
                if (result.isValid) {
                    viewModel.archivePath.value = result.archivePath
                    showValidationSuccess()
                } else {
                    showValidationError(result.error ?: "Unknown validation error")
                }
            }
        }.start()
    }

    private fun showValidationSuccess() {
        validationContainer.visibility = View.VISIBLE
        validationIcon.setImageResource(R.drawable.ic_check)
        validationIcon.setColorFilter(resources.getColor(R.color.colorSuccess, null))
        validationText.text = "Archive is valid"
        validationText.setTextColor(resources.getColor(R.color.colorSuccess, null))
        startCompilationButton.isEnabled = true
    }

    private fun showValidationError(error: String) {
        validationContainer.visibility = View.VISIBLE
        validationIcon.setImageResource(R.drawable.ic_error)
        validationIcon.setColorFilter(resources.getColor(R.color.colorError, null))
        validationText.text = error
        validationText.setTextColor(resources.getColor(R.color.colorError, null))
        startCompilationButton.isEnabled = false
    }
}
