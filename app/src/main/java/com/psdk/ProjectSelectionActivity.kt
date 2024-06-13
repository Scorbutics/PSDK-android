package com.psdk

import android.app.Activity
import android.app.AlertDialog
import android.app.NativeActivity
import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.updatePadding
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.ruby.RubyInfo
import com.psdk.ruby.vm.RubyScript
import java.io.File
import java.io.FileWriter
import java.util.UUID


class ProjectSelectionActivity: ComponentActivity() {
    init {
        System.loadLibrary("jni")
    }

    private lateinit var m_database: AppDatabase
    private lateinit var m_rootDirectory: Uri
    private lateinit var m_readableRootDirectory: String
    private lateinit var m_projectPreferences: SharedPreferences
    private var m_isInternal: Boolean = false

    private var m_allProjects: List<Project> = listOf()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        m_projectPreferences = getSharedPreferences(ProjectMainActivity.PROJECT_KEY, MODE_PRIVATE)
        val errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, m_projectPreferences)
        errorUnpackAssets?.let { unableToUnpackAssetsMessage(it) }
        val shouldAutoStart = AppInstall.unpackToStartGameIfRelease(this, "Release", releaseLocation)
        if (shouldAutoStart) {
            autoStartGame()
            return
        }

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

        val changeProjectRootDir = findViewById<Button>(R.id.changeProjectRootDir)
        changeProjectRootDir.setOnClickListener { chooseStorageLocationDialog() }

        loadSavedDirectory()
        loadProjects()
    }

    private fun unableToUnpackAssetsMessage(error: String) {
        Toast.makeText(applicationContext, "Unable to unpack application assets : $error", Toast.LENGTH_LONG).show()
    }

    private fun autoStartGame() {
        val startGameActivityIntent = Intent(this@ProjectSelectionActivity, NativeActivity::class.java)
        startGameActivityIntent.putExtra("RUBY_BASEDIR", filesDir.path)
        startGameActivityIntent.putExtra("NATIVE_LIBS_LOCATION", applicationInfo.nativeLibraryDir)
        startGameActivityIntent.putExtra("EXECUTION_LOCATION", executionLocation)
        startGameActivityIntent.putExtra("OUTPUT_FILENAME", gameLogOutputFile)
        FileWriter(gameLogOutputFile, false).flush()
        val startScript: String = RubyScript.readFromAssets(assets, "start.rb")
        startGameActivityIntent.putExtra("START_SCRIPT", startScript)
        startGameActivityResultLauncher.launch(startGameActivityIntent)
    }

    private val startGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val executionLocation: String
        get() = applicationInfo.dataDir

    private val gameLogOutputFile: String
        get() = "$executionLocation/last_stdout.log"

    private val releaseLocation: String
        get() = "$executionLocation/Release"

    private fun saveDirectoryPersistent(uri: Uri) {
        val edit = m_projectPreferences.edit()
        edit.remove(ROOT_PROJECT_INTERNAL)
        edit.apply()
        val existing = contentResolver.persistedUriPermissions.firstOrNull()?.uri
        if (existing != null) {
            // Release existing directory when new one is granted
            contentResolver.releasePersistableUriPermission(existing, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun loadSavedDirectory() {
        val isInternal = m_projectPreferences.getBoolean(ROOT_PROJECT_INTERNAL, false)
        val baseUri = if (isInternal) null else contentResolver.persistedUriPermissions.firstOrNull()?.uri
        loadSavedDirectory(baseUri)
    }

    private fun loadSavedDirectory(uri: Uri?) {
        if (uri == null) {
            m_isInternal = true
            m_rootDirectory = filesDir.toUri()
            m_readableRootDirectory = PathUtil(applicationContext).getPathFromUri(m_rootDirectory)!!
        } else {
            m_isInternal = false
            m_rootDirectory = uri
            val realUri = DocumentsContract.buildDocumentUriUsingTree(
                m_rootDirectory,
                DocumentsContract.getTreeDocumentId(m_rootDirectory)
            )
            m_readableRootDirectory = PathUtil(applicationContext).getPathFromUri(realUri)!!
        }
        val currentDirectoryText = findViewById<TextView>(R.id.currentDirectoryText)
        currentDirectoryText.text = if (m_isInternal) "Internal application directory" else m_readableRootDirectory
    }

    private fun chooseStorageLocationDialog() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("Choose your root projects directory")
        builder.setSingleChoiceItems(
            arrayOf("Prefer an internal storage", "Choose a custom location"), 0
        ) { dialog, which ->
            when (which) {
                0 -> {
                    loadSavedDirectory(null)

                    // Save in preferences as it's an user action
                    val edit = m_projectPreferences.edit()
                    edit.putBoolean(ROOT_PROJECT_INTERNAL, true)
                    edit.apply()

                    loadProjects()
                    dialog.dismiss()
                }
                1 -> {
                    dialog.dismiss()
                    openRootProjectsDirectory(null)
                }
            }
        }

        builder.show()
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
        val projectId: UUID = id ?: createNewProject(name)

        // Empty project id means a new project
        projectMainActivity.putExtra("PROJECT_ID", projectId.toString())
        selectProjectActivityResult.launch(projectMainActivity)
    }

    private fun createNewProject(name: String): UUID {
        val projectId = UUID.randomUUID()
        val projectDirectoryRoot = File(m_readableRootDirectory + "/projects/" + name)
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
            loadSavedDirectory(result.data?.data!!)
            saveDirectoryPersistent(m_rootDirectory)
        }
        refreshExistingProjectsUI()
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

    companion object {
        const val ROOT_PROJECT_INTERNAL = "ROOT_PROJECT_INTERNAL"
    }

}