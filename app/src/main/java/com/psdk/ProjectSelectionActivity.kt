package com.psdk

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.ruby.RubyInfo
import java.util.UUID


class ProjectSelectionActivity: ComponentActivity() {
    init {
        System.loadLibrary("jni")
    }

    private lateinit var m_database: AppDatabase

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

        val newProject = findViewById<Button>(R.id.newProject)
        newProject.setOnClickListener {
            createNewProjectDialog()
        }

        val existingProjects = findViewById<LinearLayout>(R.id.existingProjects)
        m_database.projectDao().getAll().forEach { project ->
            val projectView = buildExistingProjectButton(project)
            existingProjects.addView(projectView)
        }
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
        // Empty project id means a new project
        projectMainActivity.putExtra("PROJECT_ID", if (id == null) "" else id.toString())
        projectMainActivity.putExtra("PROJECT_NAME", name)
        selectProjectActivityResult.launch(projectMainActivity)
    }

    private fun buildExistingProjectButton(project: Project): Button {
        val dynamicButton = Button(this)
        dynamicButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dynamicButton.text = project.name
        dynamicButton.setOnClickListener {
            accessProject(project.name, project.id)
        }
        return dynamicButton
    }

    private val selectProjectActivityResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
}