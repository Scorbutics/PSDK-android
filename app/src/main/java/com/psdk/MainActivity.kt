package com.psdk

import android.Manifest
import android.app.NativeActivity
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
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.psdk.ruby.RubyInfo
import com.psdk.ruby.vm.RubyScript
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream


class MainActivity : ComponentActivity() {
    internal enum class Mode {
        START_GAME, COMPILE
    }

    init {
        System.loadLibrary("jni")
    }

    private var m_mode = Mode.COMPILE
    private var m_permissionErrorMessage: String? = null
    private var m_archiveLocation: String? = null
    private var m_badArchiveLocation: String? = null
    private var m_projectPreferences: SharedPreferences? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (m_projectPreferences == null) {
            m_projectPreferences = getSharedPreferences(PROJECT_KEY, MODE_PRIVATE)
        }
        if (isTaskRoot) {
            val errorUnpackAssets = AppInstall.unpackExtraAssetsIfNeeded(this, m_projectPreferences)
            errorUnpackAssets?.let { unableToUnpackAssetsMessage(it) }
            val shouldAutoStart = AppInstall.unpackToStartGameIfRelease(this)
            if (shouldAutoStart) {
                startGame()
                return
            }
        }
        loadScreen()
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
        val clickButton = findViewById<View>(R.id.compileGame) as Button
        clickButton.isEnabled = validState
        val projectInfoLayout = findViewById<View>(R.id.informationLayout) as LinearLayout
        projectInfoLayout.visibility = if (!validState) View.INVISIBLE else View.VISIBLE
        return validState
    }

    private fun selectProjectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        val chooseFile = Intent.createChooser(intent, "Choose a file")
        try {
            askForExternalStorageAndThen { chooseProjectFileActivityResultLauncher.launch(chooseFile) }
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
    private fun handlePermissionsRequestResult(grantResults: IntArray) {
        if (Arrays.stream(grantResults).anyMatch { i: Int -> i != PackageManager.PERMISSION_GRANTED }) {
            m_permissionErrorMessage = "You must have read and write to external storage permissions in order to use PSDK"
        }
    }

    private fun askForExternalStorageAndThen(callback: (Boolean) -> Unit) {
        //Android is 11 (R) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", this.packageName, null)
                intent.setData(uri)
                buildStorageActivityResultLauncher(callback).launch(intent)
            } catch (e: java.lang.Exception) {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                buildStorageActivityResultLauncher(callback).launch(intent)
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
        }

    }

    private val buildApkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
        ) {}

    private val compileGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val startGameActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val readLogDetailsActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val chooseProjectFileActivityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val path = PathUtil(applicationContext).getPathFromUri(result.data?.data)
            m_mode = Mode.COMPILE
            val startButton = findViewById<Button>(R.id.startGame)
            startButton.visibility = View.INVISIBLE
            setArchiveLocationValue(path, true)
        }

    private fun buildStorageActivityResultLauncher(callback: (Boolean) -> Unit): ActivityResultLauncher<Intent> {
        return registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                //Android is 11 (R) or above
                if (Environment.isExternalStorageManager()) {
                    //Manage External Storage Permissions Granted
                    callback(true)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Storage Permissions Denied",
                        Toast.LENGTH_SHORT
                    ).show()
                    callback(false)
                }
            } else {
                //Below android 11, just bypass it
                callback(true)
            }
        }
    }

    private val isValidState: Boolean
        get() = m_permissionErrorMessage == null && m_badArchiveLocation == null

    private fun startGame() {
        val switchActivityIntent = Intent(this@MainActivity, NativeActivity::class.java)
        switchActivityIntent.putExtra("EXECUTION_LOCATION", executionLocation)
        switchActivityIntent.putExtra("INTERNAL_STORAGE_LOCATION", filesDir.path)
        switchActivityIntent.putExtra("EXTERNAL_STORAGE_LOCATION", getExternalFilesDir(null)!!.path)
        switchActivityIntent.putExtra("NATIVE_LIBS_LOCATION", applicationInfo.nativeLibraryDir)
        switchActivityIntent.putExtra("OUTPUT_FILENAME", gameLogOutputFile)
        FileWriter(gameLogOutputFile, false).flush()
        val startScript: String = RubyScript.Companion.readFromAssets(assets, "start.rb")
        switchActivityIntent.putExtra("START_SCRIPT", startScript)
        startGameActivityResultLauncher.launch(switchActivityIntent)
    }

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
        startButton.visibility = if (m_mode == Mode.START_GAME) View.VISIBLE else View.INVISIBLE
        val compileButton = findViewById<Button>(R.id.compileGame)
        compileButton.setOnClickListener { v: View? ->
            m_mode = Mode.COMPILE
            val compileIntent = Intent(this, CompileActivity::class.java)
            compileIntent.putExtra("EXECUTION_LOCATION", executionLocation)
            compileIntent.putExtra("ARCHIVE_LOCATION", m_archiveLocation)
            compileGameActivityResultLauncher.launch(compileIntent)
            return@setOnClickListener
        }

        startButton.setOnClickListener { v: View? ->
            try {
                startGame()
            } catch (e: Exception) {
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
        startButton.isEnabled = isValidState
        val locatePsdkButton = findViewById<View>(R.id.locatePSDK) as Button
        locatePsdkButton.setOnClickListener { v: View? -> selectProjectFile() }
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

        val shareApplication = findViewById<View>(R.id.shareApplication) as TextView
        val appFolder = File("$executionLocation/Release")
        if (appFolder.exists()) {
            shareApplication.visibility = View.VISIBLE
            shareApplication.setOnClickListener{ v: View? ->
                val buildApkIntent = Intent(this, BuildApkActivity::class.java)
                buildApkIntent.putExtra("APK_FOLDER_LOCATION", appFolder.path)
                buildApkActivityResultLauncher.launch(buildApkIntent)
                return@setOnClickListener
            }
        }

        val readLogDetails = findViewById<View>(R.id.showLogDetails) as TextView
        readLogDetails.setOnClickListener{ v: View? ->
            val readLogDetailsIntent = Intent(this, ReadLogDetailsActivity::class.java)
            readLogDetailsIntent.putExtra("GAME_LOG_FILE_LOCATION", gameLogOutputFile)
            readLogDetailsIntent.putExtra("GAME_ERROR_LOG_FILE_LOCATION", gameErrorLogOutputFile)
            readLogDetailsActivityResultLauncher.launch(readLogDetailsIntent)
            return@setOnClickListener
        }
    }

    private fun computeCurrentGameState(): Mode {
        val path = Paths.get(applicationInfo.dataDir + "/Release")
        return if (Files.exists(path)) Mode.START_GAME else Mode.COMPILE
    }

    private val executionLocation: String
        get() = applicationInfo.dataDir

    private val gameLogOutputFile: String
        get() = "$executionLocation/last_stdout.log"

    private val gameErrorLogOutputFile: String
        get() = applicationInfo.dataDir + "/Release/Error.log"

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
        private const val STORAGE_PERMISSION_CODE = 23
        private const val PROJECT_KEY = "PROJECT"
        private const val PROJECT_LOCATION_STRING = "location"
    }
}