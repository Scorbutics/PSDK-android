package com.psdk

import android.os.Build
import android.os.Environment
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import android.content.ActivityNotFoundException
import android.app.NativeActivity
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import android.widget.*
import java.io.*
import java.lang.Exception
import java.lang.NullPointerException
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    internal enum class Mode {
        START_GAME, COMPILE
    }

    private var m_mode = Mode.COMPILE
    private var m_permissionErrorMessage: String? = null
    private var m_archiveLocation: String? = null
    private var m_badArchiveLocation: String? = null
    private var m_projectPreferences: SharedPreferences? = null
    private var m_noExternalPermissions = false
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (m_projectPreferences == null) {
            m_projectPreferences = getSharedPreferences(PROJECT_KEY, MODE_PRIVATE)
        }
        if (isTaskRoot) {
            val errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, m_projectPreferences)
            errorUnpackAssets?.let { unableToUnpackAssetsMessage(it) }

            // We do not really need those permissions...
            // So why always keep asking for them ?
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                m_noExternalPermissions = !AppInstall.requestPermissionsIfNeeded(this, ACCEPT_PERMISSIONS_REQUESTCODE, ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE)
            }
        }
        loadScreen()
    }

    private fun shareApplicationOutput(appPath: String) {
        // TODO
        //signApk(appPath)
        val share = Intent(Intent.ACTION_SEND)
        share.type = "image/jpeg"
        val finalApp = FileProvider.getUriForFile(
                this@MainActivity,
                "com.psdk.starter.provider",
                File(appPath))
        share.putExtra(Intent.EXTRA_STREAM, finalApp)
        startActivity(Intent.createChooser(share, "Share App"))
    }

    private fun signApk(apk: File, outApk: File) {
        val signingOptions = buildDefaultSigningOptions(application)
        Signer(signingOptions).signApk(apk, outApk)
    }

    private fun setArchiveLocationValue(location: String?, triggerEvent: Boolean) {
        m_archiveLocation = location?.trim { it <= ' ' }
        val psdkLocation = findViewById<View>(R.id.psdkLocation) as EditText
        if (triggerEvent) {
            psdkLocation.setText(m_archiveLocation)
        }
        m_badArchiveLocation = checkFilepathValid(m_archiveLocation)
        val validState = lockScreenIfInvalidState()
        if (!validState) {
            val lastErrorLog = findViewById<View>(R.id.projectLastError) as TextView
            try {
                val encoded = Files.readAllBytes(Paths.get(applicationInfo.dataDir + "/Release/Error.log"))
                lastErrorLog.text = String(encoded, StandardCharsets.UTF_8)
            } catch (e: IOException) {
                // File does not exist
                lastErrorLog.text = "No log"
            }
            val projectVersion = findViewById<View>(R.id.projectVersion) as TextView
            if (m_mode == Mode.COMPILE) {
                projectVersion.text = "Unknown (game uncompiled)"
            } else {
                try {
                    val encoded = Files.readAllBytes(Paths.get(applicationInfo.dataDir + "/Release/pokemonsdk/version.txt"))
                    var versionNumeric: Long = 0
                    try {
                        versionNumeric = java.lang.Long.valueOf(String(encoded, StandardCharsets.UTF_8).trim { it <= ' ' })
                    } catch (nfe: NumberFormatException) {
                        projectVersion.text = "INVALID"
                    }
                    val majorVersion = versionNumeric shr 8
                    val versionStr = majorVersion.toString() + "." + (versionNumeric - (majorVersion shl 8) - 256).toString()
                    projectVersion.text = versionStr
                } catch (e: IOException) {
                    projectVersion.text = "INVALID"
                }
            }
        } else {
            val edit = m_projectPreferences!!.edit()
            edit.putString(PROJECT_LOCATION_STRING, m_archiveLocation)
            edit.apply()
        }
        val projectEngineHealth = findViewById<TextView>(R.id.projectEngineHealth)
        projectEngineHealth.text = if (isValidState) "" else "$m_permissionErrorMessage $m_badArchiveLocation"
        projectEngineHealth.setBackgroundResource(if (!isValidState) R.drawable.edterr else R.drawable.edtnormal)
    }

    private fun lockScreenIfInvalidState(): Boolean {
        val validState = isValidState
        val psdkLocation = findViewById<View>(R.id.psdkLocation) as EditText
        psdkLocation.setBackgroundResource(if (!validState) R.drawable.edterr else R.drawable.edtnormal)
        val psdkLocationValid = findViewById<View>(R.id.psdkLocationValid) as CheckedTextView
        psdkLocationValid.isChecked = validState
        val clickButton = findViewById<View>(R.id.startGame) as Button
        clickButton.isEnabled = validState
        val projectInfoLayout = findViewById<View>(R.id.informationLayout) as LinearLayout
        projectInfoLayout.visibility = if (!validState) View.INVISIBLE else View.VISIBLE
        val errorLogLayout = findViewById<View>(R.id.compilationLogLayout) as LinearLayout
        errorLogLayout.visibility = if (!validState) View.INVISIBLE else View.VISIBLE
        val lastEngineDebugLogLayout = findViewById<View>(R.id.lastEngineDebugLogLayout) as LinearLayout
        lastEngineDebugLogLayout.visibility = if (!validState) View.INVISIBLE else View.VISIBLE
        return validState
    }

    private fun openFile(mimeType: String?) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = mimeType
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        val chooseFile = Intent.createChooser(intent, "Choose a file")
        try {
            startActivityForResult(chooseFile, CHOOSE_FILE_REQUESTCODE)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(applicationContext, "No suitable File Manager was found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            ACCEPT_PERMISSIONS_REQUESTCODE -> {
                if (Arrays.stream(grantResults).anyMatch { i: Int -> i != PackageManager.PERMISSION_GRANTED }) {
                    m_permissionErrorMessage = "You must have read and write to external storage permissions in order to use PSDK"
                }
                AppInstall.requestActivityPermissions(this, ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE)
            }
            else -> {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        val lastErrorLog = findViewById<View>(R.id.projectLastError) as TextView
        when (requestCode) {
            ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        m_permissionErrorMessage = "You must have all file access permissions in order to use PSDK"
                    }
                }
                loadScreen()
            }
            CHOOSE_FILE_REQUESTCODE -> {
                if (resultCode != RESULT_OK) {
                    return
                }
                val path = PathUtil(applicationContext).getPathFromUri(data.data)
                m_mode = Mode.COMPILE
                val startButton = findViewById<Button>(R.id.startGame)
                startButton.text = if (m_mode == Mode.START_GAME) "start" else "compile"
                setArchiveLocationValue(path, true)
            }
            COMPILE_GAME_REQUESTCODE, START_GAME_REQUESTCODE -> if (resultCode != RESULT_OK) {
                lastErrorLog.text = "Error starting game activity, code : $resultCode"
                return
            }
            else -> {}
        }
    }

    private val isValidState: Boolean
        get() = m_permissionErrorMessage == null && m_badArchiveLocation == null

    private fun loadScreen() {
        setContentView(R.layout.main)
        if (m_projectPreferences == null) {
            throw NullPointerException("Bad application initialization: unable to get valid project preferences")
        }
        val psdkLocation = m_projectPreferences!!.getString(PROJECT_LOCATION_STRING,
                Environment.getExternalStorageDirectory().absolutePath + "/PSDK/")
        setArchiveLocationValue(psdkLocation, true)
        m_mode = computeCurrentGameState()
        val startButton = findViewById<Button>(R.id.startGame)
        startButton.text = if (m_mode == Mode.START_GAME) "start" else "compile"
        startButton.setOnClickListener { v: View? ->
            if (m_mode == Mode.COMPILE) {
                val compileIntent = Intent(this, CompileActivity::class.java)
                compileIntent.putExtra("EXECUTION_LOCATION", executionLocation)
                compileIntent.putExtra("ARCHIVE_LOCATION", m_archiveLocation)
                compileIntent.putExtra("OUTPUT_ARCHIVE_LOCATION", fullAppLocation)
                startActivityForResult(compileIntent, COMPILE_GAME_REQUESTCODE)
                return@setOnClickListener
            }
            val switchActivityIntent = Intent(this@MainActivity, NativeActivity::class.java)
            switchActivityIntent.putExtra("EXECUTION_LOCATION", executionLocation)
            switchActivityIntent.putExtra("INTERNAL_STORAGE_LOCATION", filesDir.path)
            switchActivityIntent.putExtra("EXTERNAL_STORAGE_LOCATION", getExternalFilesDir(null)!!.path)
            val outputFilename = "$executionLocation/last_stdout.log"
            switchActivityIntent.putExtra("OUTPUT_FILENAME", outputFilename)
            try {
                val fw = FileWriter(outputFilename, false)
                fw.flush()
                val startScript: String = PsdkProcess.Companion.readFromAssets(this, "start.rb")
                switchActivityIntent.putExtra("START_SCRIPT", startScript)
                this@MainActivity.startActivityForResult(switchActivityIntent, START_GAME_REQUESTCODE)
            } catch (e: Exception) {
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
        startButton.isEnabled = isValidState
        val locatePsdkButton = findViewById<View>(R.id.locatePSDK) as Button
        locatePsdkButton.setOnClickListener { v: View? -> openFile("*/*") }
        val psdkLocationText = findViewById<View>(R.id.psdkLocation) as EditText
        psdkLocationText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                setArchiveLocationValue(psdkLocationText.text.toString(), false)
            }

            override fun afterTextChanged(s: Editable) {}
        })
        val sb = StringBuilder()
        for (abi in Build.SUPPORTED_ABIS) {
            sb.append("$abi/ ")
        }
        val abiVersion = findViewById<View>(R.id.deviceAbiVersion) as TextView
        abiVersion.text = sb.toString()
        val rubyVersion = findViewById<View>(R.id.engineRubyVersion) as TextView
        rubyVersion.text = RubyInfo.rubyVersion
        val rubyPlatform = findViewById<View>(R.id.engineRubyPlatform) as TextView
        rubyPlatform.text = RubyInfo.rubyPlatform
        val lastEngineDebugLogs = findViewById<View>(R.id.lastEngineDebugLogs) as TextView
        val lastStdoutLog = Paths.get("$executionLocation/last_stdout.log")
        if (Files.exists(lastStdoutLog)) {
            try {
                lastEngineDebugLogs.text = String(Files.readAllBytes(lastStdoutLog), StandardCharsets.UTF_8)
            } catch (exception: Exception) {
                lastEngineDebugLogs.text = "Unable to read last stdout log: " + exception.localizedMessage
            }
        } else {
            lastEngineDebugLogs.text = "No log"
        }
        val shareApplication = findViewById<View>(R.id.shareApplication) as TextView

        fullAppLocation?.let {
            val app = File(it)
            if (app.exists()) {
                shareApplication.visibility = View.VISIBLE
                shareApplication.setOnClickListener { v: View? -> shareApplicationOutput(it) }
            } else {
                lastEngineDebugLogs.text = "No log"
            }
        }
    }

    private fun computeCurrentGameState(): Mode {
        val path = Paths.get(applicationInfo.dataDir + "/Release")
        return if (Files.exists(path)) Mode.START_GAME else Mode.COMPILE
    }

    private val executionLocation: String
        get() = applicationInfo.dataDir
    private val fullAppLocation: String?
        get() = if (m_archiveLocation != null) m_archiveLocation!!.substring(0, m_archiveLocation!!.lastIndexOf('/')) + "/game-compiled.zip" else null

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

    private fun unableToUnpackAssetsMessage(error: String) {
        Toast.makeText(applicationContext, "Unable to unpack application assets : $error", Toast.LENGTH_LONG).show()
    }

    companion object {
        init {
            System.loadLibrary("jni")
        }

        private const val CHOOSE_FILE_REQUESTCODE = 8777
        private const val START_GAME_REQUESTCODE = 8700
        private const val COMPILE_GAME_REQUESTCODE = 8000
        private const val ACCEPT_PERMISSIONS_REQUESTCODE = 8007
        private const val ACTIVITY_ACCEPT_ALL_PERMISSIONS_REQUESTCODE = 8070
        private const val PROJECT_KEY = "PROJECT"
        private const val PROJECT_LOCATION_STRING = "location"
    }
}