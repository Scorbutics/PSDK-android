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
import com.psdk.zip.EpsaArchive

class ImportArchiveFragment : Fragment() {

    private val viewModel: CompileWizardViewModel by activityViewModels()

    private lateinit var fragmentRoot: View
    private lateinit var importButton: MaterialButton
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

            if (EpsaArchive.isEpsaFile(stagingFile)) {
                validateArchive(stagingFile)
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

        fragmentRoot = view
        importButton = view.findViewById(R.id.importArchiveButton)
        validationContainer = view.findViewById(R.id.validationContainer)
        validationIcon = view.findViewById(R.id.validationIcon)
        validationText = view.findViewById(R.id.validationText)
        processingContainer = view.findViewById(R.id.processingContainer)

        importButton.setOnClickListener { selectFile() }

        // If the activity already validated an archive on a previous attach
        // (cache hit replayed into the ViewModel) we have no card to show —
        // collapse straight to the GL-surface affordance.
        if (viewModel.archive.value != null) {
            fragmentRoot.visibility = View.GONE
        }

        // Check if we already have a valid archive. Off the main thread:
        // the cache-miss path runs extract_loader_assets.rb (~2s) and even
        // the cache-hit path does an HMAC/KDF pass, which together would
        // freeze the activity transition into the wizard. The wizard UI
        // stays interactive while the check runs.
        val executionLocation = viewModel.executionLocation.value
        if (executionLocation != null && viewModel.archive.value == null) {
            val staged = ArchiveValidator.getStagingFile(executionLocation)
            if (staged.exists()) {
                processingContainer.visibility = View.VISIBLE
                val ctx = requireContext().applicationContext
                Thread {
                    val result = ArchiveValidator.validateExisting(ctx, staged, executionLocation)
                    activity?.runOnUiThread {
                        // Fragment may have detached between launching the
                        // worker and this callback running (rotation, step
                        // transition). `isAdded` keeps us off any view-
                        // resource access that would throw via requireContext().
                        if (!isAdded) return@runOnUiThread
                        processingContainer.visibility = View.GONE
                        if (result.isValid) {
                            viewModel.archive.value = result.archive
                            showValidationSuccess()
                        }
                    }
                }.start()
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

    private fun validateArchive(stagingFile: java.io.File) {
        processingContainer.visibility = View.VISIBLE
        validationContainer.visibility = View.GONE

        val ctx = requireContext().applicationContext
        val executionLocation = viewModel.executionLocation.value ?: return
        Thread {
            val result = ArchiveValidator.validate(ctx, stagingFile, executionLocation)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                processingContainer.visibility = View.GONE
                if (result.isValid) {
                    viewModel.archive.value = result.archive
                    showValidationSuccess()
                } else {
                    showValidationError(result.error ?: "Unknown validation error")
                }
            }
        }.start()
    }

    // Once the archive is valid the card stops being the UX focus: the
    // activity drops back to the GL surface, whose game.png logo is now
    // the "start compilation" tap target. Hiding the fragment root (the
    // ScrollView) keeps the surface unobstructed while preserving the
    // step-0 fragment so navigation back into it would still work.
    private fun showValidationSuccess() {
        if (::fragmentRoot.isInitialized) {
            fragmentRoot.visibility = View.GONE
        }
    }

    private fun showValidationError(error: String) {
        validationContainer.visibility = View.VISIBLE
        validationIcon.setImageResource(R.drawable.ic_error)
        validationIcon.setColorFilter(resources.getColor(R.color.colorError, null))
        validationText.text = error
        validationText.setTextColor(resources.getColor(R.color.colorError, null))
    }
}
