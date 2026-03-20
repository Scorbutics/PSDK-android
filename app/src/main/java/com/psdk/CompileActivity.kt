package com.psdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.psdk.zip.EpsaDecryptor
import java.util.zip.ZipFile
import java.io.File
import java.io.IOException

class CompileActivity: ComponentActivity() {

    private var m_archiveLocation: String? = null
    private var m_badArchiveLocation: String? = null
    private var m_releaseLocation: String? = null
    private var m_withSavedArchive: Boolean = false
    private var m_executionLocation: String? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler)

        m_executionLocation = intent.getStringExtra("EXECUTION_LOCATION")
        m_releaseLocation = intent.getStringExtra("RELEASE_LOCATION")

        m_withSavedArchive = importedFile.canRead()
        setArchiveLocationValue(importedFile.path)

        val compileButton = findViewById<Button>(R.id.compileGame)
        compileButton.setOnClickListener { v: View? ->
            val compileIntent = Intent(this, CompileProcessActivity::class.java)
            compileIntent.putExtra("EXECUTION_LOCATION", m_executionLocation)
            compileIntent.putExtra("ARCHIVE_LOCATION", m_archiveLocation)
            compileGameActivityResultLauncher.launch(compileIntent)
            return@setOnClickListener
        }

        val locatePsdkButton = findViewById<View>(R.id.locatePSDK) as Button
        locatePsdkButton.setOnClickListener { v: View? -> selectProjectFile() }
    }

    private fun checkFilepathValid(filepath: String?): String? {
        if (filepath == null) {
            return "File path not provided"
        }
        var finalFile = File(filepath)
        if (!finalFile.exists() || !finalFile.canRead()) {
            return "Error : file at filepath $filepath does not exist or not readable"
        }

        // If this is an encrypted archive, decrypt it first
        if (EpsaDecryptor.isEpsaFile(finalFile)) {
            val decryptedFile = importedFile
            val error = EpsaDecryptor.decrypt(finalFile, decryptedFile)
            if (error != null) {
                return error
            }
            finalFile = decryptedFile
            m_archiveLocation = decryptedFile.path
        }

        val absPath = finalFile.absolutePath
        val sep = absPath.lastIndexOf(File.separator)
        val filename = absPath.substring(sep + 1).trim { it <= ' ' }
        return if (!filename.endsWith(".psa")) {
            "Error : selected file at filepath $filepath is not a 'psa' file : '$filename'"
        } else try {
            ZipFile(finalFile).use { zip ->
                if (zip.getEntry("pokemonsdk/version.txt") == null) {
                    "pokemonsdk folder not found in the archive"
                } else if (zip.getEntry("Game.rb") == null) {
                    "no Game.rb init script in the archive"
                } else null
            }
        } catch (e: IOException) {
            "Error while reading the archive: " + e.localizedMessage
        }
    }

    private fun setArchiveLocationValue(location: String?) {
        m_archiveLocation = location?.trim { it <= ' ' }

        val projectEngineHealth = findViewById<TextView>(R.id.projectEngineHealth)
        if (m_withSavedArchive) {
            lockScreenIfInvalidState(true)
            projectEngineHealth.text = "Archive is valid"
        } else {
            projectEngineHealth.text = "Processing archive..."
            lockScreenIfInvalidState(false)
            val thread: Thread = object : Thread() {
                override fun run() {
                    m_badArchiveLocation = checkFilepathValid(m_archiveLocation)
                    runOnUiThread {
                        val validState = lockScreenIfInvalidState(isValidState)
                        if (validState) {
                            projectEngineHealth.text = "Archive is valid"
                        } else if (m_badArchiveLocation != null) {
                            projectEngineHealth.text = m_badArchiveLocation
                        }
                    }
                }
            }
            thread.start()
        }
    }

    private fun lockScreenIfInvalidState(validState: Boolean): Boolean {
        val psdkLocationValid = findViewById<CheckedTextView>(R.id.psdkLocationValid)
        psdkLocationValid.isChecked = validState
        val clickButton = findViewById<Button>(R.id.compileGame)
        clickButton.isEnabled = validState
        return validState
    }

    private fun selectProjectFile() {
        try {
            chooseProjectFile()
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(applicationContext, "No suitable File Manager was found.", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseProjectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        val chooseFile = Intent.createChooser(intent, "Choose a file")
        chooseProjectFileActivityResultLauncher.launch(chooseFile)
    }

    private val chooseProjectFileActivityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data!!
                val stagingFile = importedStagingFile
                if (!stagingFile.exists()) {
                    stagingFile.createNewFile()
                }
                val out = stagingFile.outputStream()
                val input = contentResolver.openInputStream(uri)
                input?.copyTo(out)
                input?.close()
                out.close()
                m_withSavedArchive = false
                if (EpsaDecryptor.isEpsaFile(stagingFile)) {
                    // Encrypted archive: validation will decrypt to archive.psa
                    setArchiveLocationValue(stagingFile.path)
                } else {
                    // Plain archive: rename staging to final location
                    stagingFile.renameTo(importedFile)
                    setArchiveLocationValue(importedFile.path)
                }
            }
        }

    private val compileGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val isValidState: Boolean
        get() = m_badArchiveLocation == null

    private val importedFile: File
        get() = File("$m_executionLocation/archive.psa")

    private val importedStagingFile: File
        get() = File("$m_executionLocation/archive.staging")

}