package com.psdk

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.updatePadding
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.ruby.RubyInfo
import java.io.File
import java.util.UUID


class ProjectSelectionActivity: ComponentActivity() {
    init {
        System.loadLibrary("jni")
    }

    private lateinit var m_database: AppDatabase
    private lateinit var m_rootDirectory: Uri

    private var m_allProjects: List<Project> = listOf()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.project_selection)

        m_database = AppDatabase.getInstance(this)

        val sb = StringBuilder()
        for (abi in Build.SUPPORTED_ABIS) {
            sb.append("$abi/ ")
        }
        val abiVersion = findViewById<TextView>(R.id.deviceAbiVersion)
        abiVersion.text = sb.toString()
        val rubyVersion = findViewById<TextView>(R.id.engineRubyVersion)
        rubyVersion.text = RubyInfo.rubyVersion
        val rubyPlatform = findViewById<TextView>(R.id.engineRubyPlatform)
        rubyPlatform.text = RubyInfo.rubyPlatform

        val baseUri = loadSavedDirectory()
        if (baseUri == null) {
            openRootProjectsDirectory(null)
        } else {
            m_rootDirectory = baseUri
            loadProjects()
        }
    }

    fun saveDirectory(uri: Uri?) {
        if (uri == null) {
            return
        }
        val existing = loadSavedDirectory()
        if (existing != null) {
            // Release existing directory when new one is granted
            contentResolver.releasePersistableUriPermission(existing, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun loadSavedDirectory() : Uri? {
        return contentResolver.persistedUriPermissions.firstOrNull()?.uri
    }

    private fun createNewProjectDialog() {
        // Set up an input inside an alert dialog in order to ask for project details
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("New project")
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        builder.setPositiveButton("Create") { _, _ ->
            run {
                val newProjectName = input.text.toString()
                accessProject(newProjectName, null)
            }
        }
        builder.setNegativeButton("Cancel"
        ) { dialog, _ -> dialog.cancel() }

        builder.show()
    }

    private fun accessProject(name: String, id: UUID?) {
        val projectMainActivity =
            Intent(this@ProjectSelectionActivity, ProjectMainActivity::class.java)

        val projectId: UUID
        if (id == null) {
            projectId = createNewProject(name)
        } else {
            projectId = id
        }

        // Empty project id means a new project
        projectMainActivity.putExtra("PROJECT_ID", projectId.toString())
        selectProjectActivityResult.launch(projectMainActivity)
    }

    private fun createNewProject(name: String): UUID {
        val projectId = UUID.randomUUID()
        val realRootDirectory = PathUtil(applicationContext).getPathFromUri(DocumentsContract.buildDocumentUriUsingTree(
            m_rootDirectory,
            DocumentsContract.getTreeDocumentId(m_rootDirectory)
        ))
        val projectDirectoryRoot = File(realRootDirectory + "/projects/" + name)
        var projectDirectoryIt = projectDirectoryRoot
        var index = 0
        while (projectDirectoryIt.exists() && m_allProjects.find { p -> p.directory != projectDirectoryIt.path } != null) {
            projectDirectoryIt = File(projectDirectoryRoot.path + "-" + ++index)
        }
        projectDirectoryIt.mkdirs()
        m_database.projectDao().insertAll(Project(name, projectDirectoryIt.path, m_rootDirectory.path!!, projectId))
        refreshExistingProjectsUI()
        return projectId
    }

    fun openRootProjectsDirectory(pickerInitialUri: Uri?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (pickerInitialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        chooseRootDirectoryActivityResult.launch(intent)
    }

    private fun buildExistingProjectButton(project: Project): View {
        val projectReadableOnFilesystem = File(project.directory).canRead()

        val accessProject = Button(this)
        accessProject.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        accessProject.text = project.name
        accessProject.isEnabled = projectReadableOnFilesystem
        if (projectReadableOnFilesystem) {
            accessProject.setOnClickListener {
                accessProject(project.name, project.id)
            }
        }

        val deleteProject = ImageButton(this)
        deleteProject.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        deleteProject.setImageResource(android.R.drawable.ic_delete)
        deleteProject.setOnClickListener {
            deleteProject(project)
        }

        val unsyncIcon = ImageView(this)
        unsyncIcon.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        unsyncIcon.setImageResource(android.R.drawable.ic_dialog_alert)
        unsyncIcon.visibility = if (projectReadableOnFilesystem) View.GONE else View.VISIBLE

        val unsyncDetails = TextView(this)
        unsyncIcon.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        unsyncDetails.updatePadding(left = 10)
        unsyncDetails.text = "(Not found on filesystem)"
        unsyncDetails.visibility = unsyncIcon.visibility

        val container = LinearLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(accessProject)
        container.addView(deleteProject)
        container.addView(unsyncIcon)
        container.addView(unsyncDetails)
        return container
    }

    private fun deleteProject(project: Project) {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("Are you sure you want to remove project '${project.name}'?")
        builder.setPositiveButton("Yes") { _, _ ->
            run {
                m_database.projectDao().delete(project)
                val projectDirectory = File(project.directory)
                if (projectDirectory.canRead()) {
                    projectDirectory.deleteRecursively()
                }
                refreshExistingProjectsUI()
            }
        }

        builder.setNegativeButton("No") { dialog, _ ->
            run { dialog.cancel() }
        }

        builder.show()
    }

    private val selectProjectActivityResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val chooseRootDirectoryActivityResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data!!
            m_rootDirectory = uri
            saveDirectory(m_rootDirectory)
        }
        loadProjects()
    }


    private fun loadProjects() {
        val newProject = findViewById<ImageButton>(R.id.newProject)
        newProject.setOnClickListener {
            createNewProjectDialog()
        }

        refreshExistingProjectsUI()
    }

    private fun refreshExistingProjectsUI() {
        val existingProjects = findViewById<LinearLayout>(R.id.existingProjects)
        existingProjects.removeAllViews()
        m_allProjects = m_database.projectDao().getAllByDirectory(m_rootDirectory.path!!)
        m_allProjects.forEach { project ->
            val projectView = buildExistingProjectButton(project)
            existingProjects.addView(projectView)
        }
    }

}