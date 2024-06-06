package com.psdk

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Arrays
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class CompileActivity: ComponentActivity() {

    private var m_permissionErrorMessage: String? = null
    private var m_archiveLocation: String? = null
    private var m_badArchiveLocation: String? = null
    private var m_projectPreferences: SharedPreferences? = null
    private var m_releaseLocation: String? = null
    private var m_withSavedArchive: Boolean = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.compiler)
        if (m_projectPreferences == null) {
            m_projectPreferences = getSharedPreferences(MainActivity.PROJECT_KEY, MODE_PRIVATE)
        }

        val executionLocation = intent.getStringExtra("EXECUTION_LOCATION")
        m_releaseLocation = intent.getStringExtra("RELEASE_LOCATION")

        val psdkLocation = m_projectPreferences!!.getString(PROJECT_LOCATION_STRING,"")
        m_withSavedArchive = psdkLocation?.isNotEmpty() ?: false
        setArchiveLocationValue(psdkLocation, true)

        val compileButton = findViewById<Button>(R.id.compileGame)
        compileButton.setOnClickListener { v: View? ->
            val compileIntent = Intent(this, CompileProcessActivity::class.java)
            compileIntent.putExtra("EXECUTION_LOCATION", executionLocation)
            compileIntent.putExtra("ARCHIVE_LOCATION", m_archiveLocation)
            compileGameActivityResultLauncher.launch(compileIntent)
            return@setOnClickListener
        }

        val locatePsdkButton = findViewById<View>(R.id.locatePSDK) as Button
        locatePsdkButton.setOnClickListener { v: View? -> selectProjectFile() }
        val psdkLocationText = findViewById<View>(R.id.psdkLocation) as EditText
        psdkLocationText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                m_withSavedArchive = false
                setArchiveLocationValue(psdkLocationText.text.toString(), false)
            }

            override fun afterTextChanged(s: Editable) {}
        })
    }

    private fun checkFilepathValid(filepath: String?): String? {
        if (filepath == null) {
            return "File path not provided"
        }
        val finalFile = File(filepath)
        if (!finalFile.exists() || !finalFile.canRead()) {
            return "Error : file at filepath $filepath does not exist or not readable"
        }
        val absPath = finalFile.absolutePath
        val sep = absPath.lastIndexOf(File.separator)
        val filename = absPath.substring(sep + 1).trim { it <= ' ' }
        return if (!filename.endsWith(".psa")) {
            "Error : selected file at filepath $filepath is not a 'psa' file : '$filename'"
        } else try {
            val bufIn = BufferedInputStream(FileInputStream(finalFile))
            bufIn.mark(512)
            val zipIn = ZipInputStream(bufIn)
            var foundSpecial = false
            var entry: ZipEntry
            while (zipIn.nextEntry.also { entry = it } != null) {
                if ("pokemonsdk/version.txt" == entry.name) {
                    foundSpecial = true
                    break
                }
            }
            if (!foundSpecial) {
                "pokemonsdk folder not found in the archive"
            } else null
        } catch (e: IOException) {
            "Error while reading the archive: " + e.localizedMessage
        }
    }

    private fun setArchiveLocationValue(location: String?, triggerEvent: Boolean) {
        m_archiveLocation = location?.trim { it <= ' ' }
        val psdkLocation = findViewById<View>(R.id.psdkLocation) as EditText
        if (triggerEvent) {
            psdkLocation.setText(m_archiveLocation)
        }

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
                            val edit = m_projectPreferences!!.edit()
                            edit.putString(PROJECT_LOCATION_STRING, m_archiveLocation)
                            edit.apply()
                            projectEngineHealth.text = "Archive is valid"
                        } else {
                            projectEngineHealth.text =
                                "$m_permissionErrorMessage $m_badArchiveLocation"
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
    private fun handlePermissionsRequestResult(grantResults: IntArray) {
        if (Arrays.stream(grantResults).anyMatch { i: Int -> i != PackageManager.PERMISSION_GRANTED }) {
            m_permissionErrorMessage = "You must have read and write to external storage permissions in order to use PSDK"
        }
    }

    private fun askForExternalStorageAndChooseFile() {
        //Android is 11 (R) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                chooseProjectFile()
            } else {
                try {
                    val intent = Intent()
                    intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    val uri = Uri.fromParts("package", this.packageName, null)
                    intent.setData(uri)
                    storageActivityForFileSelectionResultLauncher.launch(intent)
                } catch (e: java.lang.Exception) {
                    val intent = Intent()
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storageActivityForFileSelectionResultLauncher.launch(intent)
                }
            }
        } else {
            //Below android 11
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String>(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
            )
            chooseProjectFile()
        }

    }

    private fun selectProjectFile() {
        try {
            askForExternalStorageAndChooseFile()
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(applicationContext, "No suitable File Manager was found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            STORAGE_PERMISSION_CODE -> {
                handlePermissionsRequestResult(grantResults)
            }
            else -> {}
        }
    }

    private fun chooseProjectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        val chooseFile = Intent.createChooser(intent, "Choose a file")
        chooseProjectFileActivityResultLauncher.launch(chooseFile)
    }

    private val compileGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val chooseProjectFileActivityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val path = PathUtil(applicationContext).getPathFromUri(result.data?.data)
            m_withSavedArchive = false
            setArchiveLocationValue(path, true)
        }

    private val storageActivityForFileSelectionResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //Android is 11 (R) or above
            if (Environment.isExternalStorageManager()) {
                //Manage External Storage Permissions Granted
                chooseProjectFile()
            } else {
                Toast.makeText(
                    this@CompileActivity,
                    "Storage Permissions Denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            //Below android 11, just bypass it
            chooseProjectFile()
        }
    }

    private val isValidState: Boolean
        get() = m_permissionErrorMessage == null && m_badArchiveLocation == null

    companion object {
        private const val STORAGE_PERMISSION_CODE = 23
        private const val PROJECT_LOCATION_STRING = "location"
    }
}