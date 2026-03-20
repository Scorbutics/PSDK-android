package com.psdk.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.psdk.PathUtil
import com.psdk.R
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import java.io.File
import java.util.UUID

class ProjectListFragment : Fragment() {

    private val viewModel: ProjectHubViewModel by activityViewModels()
    private lateinit var adapter: ProjectListAdapter
    private lateinit var database: AppDatabase

    private val chooseRootDirectoryResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadDirectory(uri)
                saveDirectoryPersistent(uri)
                refreshProjects()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_project_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getInstance(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.projectsRecyclerView)
        val emptyState = view.findViewById<LinearLayout>(R.id.emptyState)
        val newProjectFab = view.findViewById<FloatingActionButton>(R.id.newProjectFab)
        val currentDirectoryText = view.findViewById<TextView>(R.id.currentDirectoryText)
        val changeDirectoryButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.changeDirectoryButton)
        val deviceAbiText = view.findViewById<TextView>(R.id.deviceAbiText)

        // ABI info
        deviceAbiText.text = "ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}"

        adapter = ProjectListAdapter(
            onProjectClick = { project ->
                viewModel.selectedProject.value = project
                parentFragmentManager.beginTransaction()
                    .replace(R.id.hubContainer, ProjectDetailFragment())
                    .addToBackStack(null)
                    .commit()
            },
            onDeleteClick = { project -> showDeleteDialog(project) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        newProjectFab.setOnClickListener { showCreateDialog() }
        changeDirectoryButton.setOnClickListener { showStorageLocationDialog() }

        // Observe projects
        viewModel.projects.observe(viewLifecycleOwner) { projects ->
            adapter.submitList(projects)
            emptyState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.readableRootDirectory.observe(viewLifecycleOwner) { dir ->
            currentDirectoryText.text = if (viewModel.isInternal.value == true) "Internal storage" else dir
        }

        // Load saved directory and projects
        loadSavedDirectory()
        refreshProjects()
    }

    override fun onResume() {
        super.onResume()
        refreshProjects()
    }

    private fun loadSavedDirectory() {
        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
        val isInternal = prefs.getBoolean(ROOT_PROJECT_INTERNAL, false)
        val baseUri = if (isInternal) null else requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
        loadDirectory(baseUri)
    }

    private fun loadDirectory(uri: Uri?) {
        if (uri == null) {
            viewModel.isInternal.value = true
            viewModel.rootDirectory.value = requireContext().filesDir.toUri()
            viewModel.readableRootDirectory.value = PathUtil(requireContext()).getPathFromUri(viewModel.rootDirectory.value!!)
        } else {
            viewModel.isInternal.value = false
            viewModel.rootDirectory.value = uri
            val realUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            viewModel.readableRootDirectory.value = PathUtil(requireContext()).getPathFromUri(realUri)
        }
    }

    private fun saveDirectoryPersistent(uri: Uri) {
        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
        prefs.edit().remove(ROOT_PROJECT_INTERNAL).apply()
        val existing = requireContext().contentResolver.persistedUriPermissions.firstOrNull()?.uri
        if (existing != null) {
            requireContext().contentResolver.releasePersistableUriPermission(existing, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun refreshProjects() {
        val rootPath = viewModel.rootDirectory.value?.path ?: return
        viewModel.projects.value = database.projectDao().getAllByDirectory(rootPath)
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(resources.getColor(R.color.colorOnSurface, null))
            setHintTextColor(resources.getColor(R.color.colorOnSurfaceVariant, null))
            hint = "Project name"
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("New Project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createProject(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createProject(name: String) {
        val rootDir = viewModel.readableRootDirectory.value ?: return
        val projectId = UUID.randomUUID()
        val projectDir = File("$rootDir/projects/$name")
        projectDir.mkdirs()
        database.projectDao().insertAll(
            Project(name, projectDir.path, viewModel.rootDirectory.value?.path!!, projectId)
        )
        refreshProjects()
    }

    private fun showDeleteDialog(project: Project) {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("Delete Project")
            .setMessage("Are you sure you want to remove '${project.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                database.projectDao().delete(project)
                val dir = File(project.directory)
                if (dir.canRead()) dir.deleteRecursively()
                refreshProjects()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStorageLocationDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Psdk_Dialog)
            .setTitle("Storage Location")
            .setSingleChoiceItems(
                arrayOf("Internal storage", "Custom location"),
                if (viewModel.isInternal.value == true) 0 else 1
            ) { dialog, which ->
                when (which) {
                    0 -> {
                        loadDirectory(null)
                        val prefs = requireContext().getSharedPreferences("PSDK-PROJECT", Activity.MODE_PRIVATE)
                        prefs.edit().putBoolean(ROOT_PROJECT_INTERNAL, true).apply()
                        refreshProjects()
                        dialog.dismiss()
                    }
                    1 -> {
                        dialog.dismiss()
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        chooseRootDirectoryResult.launch(intent)
                    }
                }
            }
            .show()
    }

    companion object {
        private const val ROOT_PROJECT_INTERNAL = "ROOT_PROJECT_INTERNAL"
    }
}
