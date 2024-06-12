package com.psdk

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.psdk.db.AppDatabase
import com.psdk.db.entities.Project
import com.psdk.ruby.vm.RubyScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID


class ProjectMainActivity : ComponentActivity() {
    private lateinit var m_project: Project
    private lateinit var m_database: AppDatabase

    internal enum class Mode {
        START_GAME, COMPILE
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        m_database = AppDatabase.getInstance(this)
        val projectDao = m_database.projectDao()
        val projectId = intent.getStringExtra("PROJECT_ID")
        if (projectId == null || projectId == "") {
            val newProjectName = intent.getStringExtra("PROJECT_NAME") ?: "New project"
            val newProjectId = UUID.randomUUID()
            m_project = Project(newProjectName, newProjectId)
            projectDao.insertAll(m_project)
        } else {
            m_project = projectDao.findById(UUID.fromString(projectId))
        }

        val projectPreferences = getSharedPreferences(PROJECT_KEY, MODE_PRIVATE)
        val errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, projectPreferences)
        errorUnpackAssets?.let { unableToUnpackAssetsMessage(it) }
        val shouldAutoStart = AppInstall.unpackToStartGameIfRelease(this, "Release", releaseLocation)
        if (shouldAutoStart) {
            startGame()
            return
        }

        setContentView(R.layout.project_main_page)

        val projectHeader = findViewById<TextView>(R.id.projectHeader)
        projectHeader.text = m_project.name

        val compileButton = findViewById<Button>(R.id.compileGame)
        compileButton.setOnClickListener {
            val compileIntent = Intent(this, CompileActivity::class.java)
            compileIntent.putExtra("EXECUTION_LOCATION", executionLocation)
            compileIntent.putExtra("RELEASE_LOCATION", releaseLocation)
            compileActivityResultLauncher.launch(compileIntent)
        }

        val readLogDetails = findViewById<View>(R.id.showLogDetails) as TextView
        readLogDetails.setOnClickListener{ v: View? ->
            val readLogDetailsIntent = Intent(this, ReadLogDetailsActivity::class.java)
            readLogDetailsIntent.putExtra("GAME_LOG_FILE_LOCATION", gameLogOutputFile)
            readLogDetailsIntent.putExtra("GAME_ERROR_LOG_FILE_LOCATION", gameErrorLogOutputFile)
            readLogDetailsActivityResultLauncher.launch(readLogDetailsIntent)
            return@setOnClickListener
        }

        refreshScreenData()
    }

    private fun refreshScreenData() {
        val mode = computeCurrentGameState()
        val startButton = findViewById<Button>(R.id.startGame)
        startButton.visibility = if (mode == Mode.START_GAME) View.VISIBLE else View.GONE
        startButton.setOnClickListener {
            startGame()
        }

        val shareApplication = findViewById<View>(R.id.shareApplication) as TextView
        val appFolder = File(releaseLocation)
        if (appFolder.exists()) {
            shareApplication.visibility = View.VISIBLE
            shareApplication.setOnClickListener{ v: View? ->
                val buildApkIntent = Intent(this, BuildApkActivity::class.java)
                buildApkIntent.putExtra("APK_FOLDER_LOCATION", appFolder.path)
                buildApkActivityResultLauncher.launch(buildApkIntent)
                return@setOnClickListener
            }
        }

        val projectVersion = findViewById<TextView>(R.id.projectVersion)
        try {
            val encoded = Files.readAllBytes(Paths.get("$releaseLocation/pokemonsdk/version.txt"))
            var versionNumeric: Long = 0
            try {
                versionNumeric = java.lang.Long.valueOf(String(encoded, StandardCharsets.UTF_8).trim { it <= ' ' })
            } catch (nfe: NumberFormatException) {
                projectVersion.text = "Invalid"
            }
            val majorVersion = versionNumeric shr 8
            val versionStr = majorVersion.toString() + "." + (versionNumeric - (majorVersion shl 8)).toString()
            projectVersion.text = versionStr
        } catch (e: IOException) {
            projectVersion.text = "Not found"
        }
    }

    private fun computeCurrentGameState(): Mode {
        val path = Paths.get(releaseLocation)
        return if (Files.exists(path)) Mode.START_GAME else Mode.COMPILE
    }

    private val buildApkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
        ) {}

    private val startGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val readLogDetailsActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val compileActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshScreenData()
    }

    private fun startGame() {
        val startGameActivityIntent = Intent(this@ProjectMainActivity, NativeActivity::class.java)
        startGameActivityIntent.putExtra("EXECUTION_LOCATION", executionLocation)
        startGameActivityIntent.putExtra("INTERNAL_STORAGE_LOCATION", filesDir.path)
        startGameActivityIntent.putExtra("EXTERNAL_STORAGE_LOCATION", getExternalFilesDir(null)!!.path)
        startGameActivityIntent.putExtra("NATIVE_LIBS_LOCATION", applicationInfo.nativeLibraryDir)
        startGameActivityIntent.putExtra("OUTPUT_FILENAME", gameLogOutputFile)
        FileWriter(gameLogOutputFile, false).flush()
        val startScript: String = RubyScript.readFromAssets(assets, "start.rb")
        startGameActivityIntent.putExtra("START_SCRIPT", startScript)
        startGameActivityResultLauncher.launch(startGameActivityIntent)
    }

    private val executionLocation: String
        get() = applicationInfo.dataDir

    private val gameLogOutputFile: String
        get() = "$executionLocation/last_stdout.log"

    private val releaseLocation: String
        get() = "$executionLocation/Release"

    private val gameErrorLogOutputFile: String
        get() = "$releaseLocation/Error.log"

    private fun unableToUnpackAssetsMessage(error: String) {
        Toast.makeText(applicationContext, "Unable to unpack application assets : $error", Toast.LENGTH_LONG).show()
    }

    companion object {
        const val PROJECT_KEY = "PSDK-PROJECT"
    }
}