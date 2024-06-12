package com.psdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.iyxan23.zipalignjava.ZipAlign
import com.psdk.signing.Signer
import com.psdk.signing.buildDefaultSigningOptions
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom


class BuildApkActivity: ComponentActivity()  {

    private var m_applicationLogo: InputStream? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.builder)

        val changeApplicationLogo = findViewById<ImageButton>(R.id.changeApplicationLogo)
        changeApplicationLogo.setOnClickListener {
            chooseApplicationLogo()
        }
        val changeApplicationName = findViewById<TextView>(R.id.changeApplicationName)
        changeApplicationName.setText(getApplicationName(applicationContext) + " fan game")
        val progressBarContainer = findViewById<LinearLayout>(R.id.progressBarContainer)
        progressBarContainer.visibility = View.INVISIBLE
        val exportGameButton = findViewById<View>(R.id.export) as TextView
        val appFolder = intent.getStringExtra("APK_FOLDER_LOCATION")
        if (appFolder != null) {
            exportGameButton.setOnClickListener {
                shareApplicationOutput(File(appFolder))
            }
        }
    }
    private fun getApplicationName(context: Context): String {
        val applicationInfo = context.applicationInfo
        val stringId = applicationInfo.labelRes
        return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else context.getString(
            stringId
        )
    }
    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) for (child in fileOrDirectory.listFiles()!!) deleteRecursive(
            child
        )

        fileOrDirectory.delete()
    }

    private val CHARACTERS = "abcdefghijklmnopqrstuvwxyz"
    private val RANDOM = SecureRandom()
    private fun generateRandomString(length: Int): String {
        val stringBuilder = StringBuilder(length)
        for (i in 0 until length) {
            val randomIndex = RANDOM.nextInt(CHARACTERS.length)
            stringBuilder.append(CHARACTERS[randomIndex])
        }
        return stringBuilder.toString()
    }

    private fun shareApplicationOutput(appFolder: File) {
        val progressBarContainer = findViewById<View>(R.id.progressBarContainer) as LinearLayout
        val progressBarTitle = findViewById<View>(R.id.progressBarTitle) as TextView
        val progressBar = findViewById<View>(R.id.progressBar) as ProgressBar
        val newApplicationName = findViewById<TextView>(R.id.changeApplicationName)

        var newPackageName = newApplicationName.text.toString().lowercase().replace(Regex("[^a-z0-9]"), "")
        if (newPackageName.isEmpty() || newApplicationName.text.toString().equals(getApplicationName(applicationContext))) {
            newPackageName = generateRandomString(10)
        }
        newPackageName = "com.psdk.$newPackageName"

        progressBarContainer.visibility = View.VISIBLE

        val tmpCacheExportDirectory = File(applicationContext.cacheDir.path + "/exports")
        if (tmpCacheExportDirectory.exists()) {
            deleteRecursive(tmpCacheExportDirectory)
        }
        tmpCacheExportDirectory.mkdir()

        val totalSteps = 7
        val leftOver = 5.0
        val progressIncrement = (100.0 - leftOver) / totalSteps
        var progressValue = 0
        val thread: Thread = object : Thread(null, null, "Thread-export-PSDK", 4096 * 2) {
            override fun run() {
                try {
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val tmpResultApkUnsigned = copySelfApkIntoTmpApk(progressBarTitle, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)

                    val apkModule = ApkModule.loadApkFile(tmpResultApkUnsigned)
                    changeApplicationId(progressBarTitle, newPackageName, apkModule)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    changeApplicationNameAndLogo(
                        progressBarTitle,
                        apkModule,
                        newPackageName,
                        newApplicationName.text.toString(),
                        m_applicationLogo
                    )
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    // Write the modifications to the current zip file
                    apkModule.writeApk(tmpResultApkUnsigned)
                    apkModule.close()

                    bundleCompiledGame(progressBarTitle, appFolder, tmpResultApkUnsigned)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val tmpAlignedApk = zipalign(progressBarTitle, tmpResultApkUnsigned, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val resultSignedApk = signApk(progressBarTitle, tmpAlignedApk, tmpCacheExportDirectory, newPackageName)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    shareApk(progressBarTitle, resultSignedApk)
                } finally {
                    runOnUiThread {
                        progressBarContainer.visibility = View.INVISIBLE
                    }
                }
            }
        }
        thread.start()
    }

    private fun zipalign(progressBarTitle: TextView, apk: File, tmpCacheExportDirectory: File): File {
        runOnUiThread {
            progressBarTitle.setText("Zip aligning")
        }

        val alignedApk = File.createTempFile(
            "app-output-aligned",
            ".apk",
            tmpCacheExportDirectory
        )

        ZipAlign.alignZip(RandomAccessFile(apk, "r"), alignedApk.outputStream(), 4);
        return alignedApk;
    }

    private fun incrementProgress(progressBar: ProgressBar, progressValue: Int, progressIncrement: Double): Int {
        val result = progressValue + progressIncrement.toInt()
        runOnUiThread {
            progressBar.setProgress(0)
            progressBar.setProgress(
                result,
                true
            )
        }
        return result
    }

    private fun changeApplicationNameAndLogo(progressBarTitle: TextView, apkModule: ApkModule, newPackageName: String, newLabel: String, newLogo: InputStream?) {
        runOnUiThread {
            progressBarTitle.setText("Modifying application name and logo")
        }

        apkModule.androidManifest.setApplicationLabel(newLabel)
        apkModule.tableBlock.listPackages().filter {
                packageBlock -> packageBlock.name == newPackageName
        }.forEach { packageBlock ->
            val appName = packageBlock.getOrCreate("", "string", "app_name")
            appName.setValueAsString(newLabel)
        }

        val logo = apkModule.tableBlock.getResource(newPackageName, "drawable", "logo")
        val logoFilePath = logo.get().resValue
        val logoSource = apkModule.zipEntryMap.getInputSource(logoFilePath.valueAsString)

        // If logo is null, just copy the actual app icon
        val byteStream = ByteArrayOutputStream()
        streamToStream(newLogo ?: logoSource.openStream(), byteStream)

        // Build the logo
        val newLogoSource = ByteInputSource(byteStream.toByteArray(), logoFilePath.valueAsString)

        // Remove the old logo
        apkModule.zipEntryMap.remove(logoSource)

        // Add the new logo
        apkModule.add(newLogoSource)
    }

    private fun changeApplicationId(progressBarTitle: TextView, newPackageName:String, apkModule: ApkModule) {
        runOnUiThread {
            progressBarTitle.setText("Modifying application identifier")
        }

        // Note: applicationId == package name (when compiled)

        val oldPackageName = apkModule.androidManifest.packageName
        // Resetting package name in AndroidManifest.xml
        apkModule.androidManifest.packageName = newPackageName

        // Resetting package name in the table block of resources.arsc
        apkModule.tableBlock.listPackages().filter {
            packageBlock -> packageBlock.name == oldPackageName
        }.forEach { packageBlock ->
            packageBlock.name = newPackageName
        }

        // Updating every file provider to start with the new package name
        val itProvider = apkModule.androidManifest.applicationElement.getElements("provider")
        while (itProvider.hasNext()) {
            val provider = itProvider.next()
            val authorities = provider.listAttributes().find { attribute -> attribute.name == "authorities" }
            if (authorities != null && authorities.valueString.startsWith(oldPackageName)) {
                authorities.valueAsString = authorities.valueString.replace(oldPackageName, newPackageName)
            }
        }

    }

    private fun streamToStream(ins: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var len: Int = ins.read(buffer)
        while (len != -1) {
            out.write(buffer, 0, len)
            len = ins.read(buffer)
        }
    }

    private fun bundleCompiledGame(progressBarTitle: TextView, appFolder: File, tmpResultApkUnsigned: File) {
        runOnUiThread {
            progressBarTitle.setText("Bundling compiled game")
        }

        val zipParameters = ZipParameters()
        zipParameters.rootFolderNameInZip = "assets"
        val bundledApk = ZipFile(tmpResultApkUnsigned)
        bundledApk.addFolder(appFolder, zipParameters)
        bundledApk.close()
    }

    private fun copySelfApkIntoTmpApk(progressBarTitle: TextView, tmpCacheExportDirectory: File): File {
        runOnUiThread {
            progressBarTitle.setText("Copying myself into a temporary Apk")
        }
        Thread.sleep(500)
        val tmpResultApkUnsigned = File.createTempFile(
            "app-output-unsigned",
            ".apk",
            tmpCacheExportDirectory
        )
        File(applicationContext.applicationInfo.publicSourceDir).copyTo(
            tmpResultApkUnsigned,
            true
        )
        return tmpResultApkUnsigned
    }

    private val shareGameAppActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private fun shareApk(progressBarTitle: TextView, signedApk: File) {
        runOnUiThread {
            progressBarTitle.setText("Starting export intent")
        }

        Thread.sleep(1000)

        runOnUiThread {
            val share = Intent(Intent.ACTION_SEND)
            share.type = "application/vnd.android.package-archive"
            val finalApp = FileProvider.getUriForFile(
                this@BuildApkActivity,
                applicationContext.packageName + ".provider",
                signedApk
            )
            share.putExtra(Intent.EXTRA_STREAM, finalApp)
            shareGameAppActivityResultLauncher.launch(Intent.createChooser(share, "Share App"))
        }
    }

    private fun chooseApplicationLogo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        val chooseFile = Intent.createChooser(intent, "Choose a logo")
        chooseApplicationLogoActivityResultLauncher.launch(chooseFile)
    }

    private val chooseApplicationLogoActivityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
                val changeApplicationLogo = findViewById<ImageButton>(R.id.changeApplicationLogo)
                val uri = result.data?.data!!
                changeApplicationLogo.setImageURI(uri)
                m_applicationLogo?.close()
                m_applicationLogo = contentResolver.openInputStream(uri)
            } else {
                Toast.makeText(applicationContext, "No file selected", Toast.LENGTH_LONG).show()
            }
        }

    private fun signApk(progressBarTitle: TextView, inApk: File, tmpCacheExportDirectory: File, outputName: String): File {
        runOnUiThread {
            progressBarTitle.setText("Signing the final apk")
        }

        val resultSignedApk = File.createTempFile(
            outputName,
            ".apk",
            tmpCacheExportDirectory
        )
        val signingOptions = buildDefaultSigningOptions(application)
        Signer(signingOptions).signApk(inApk, resultSignedApk)
        return resultSignedApk
    }
}