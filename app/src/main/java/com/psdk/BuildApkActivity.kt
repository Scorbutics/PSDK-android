package com.psdk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.iyxan23.zipalignjava.ZipAlign
import com.psdk.signing.Signer
import com.psdk.signing.buildDefaultSigningOptions
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.ResXmlElement
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom


class BuildApkActivity: ComponentActivity()  {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.builder)
        val changeApplicationName = findViewById<View>(R.id.changeApplicationName) as TextView
        changeApplicationName.setText(getApplicationName(applicationContext))
        val progressBarContainer = findViewById<View>(R.id.progressBarContainer) as LinearLayout
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
        /* TODO
            1. add possibility to edit the logo (replace the logo.png inside the archive)
            2. add a way to change the application name in strings.xml inside the archive
         */
        val progressBarContainer = findViewById<View>(R.id.progressBarContainer) as LinearLayout
        val progressBarTitle = findViewById<View>(R.id.progressBarTitle) as TextView
        val progressBar = findViewById<View>(R.id.progressBar) as ProgressBar
        val newApplicationName = findViewById<TextView>(R.id.changeApplicationName)
        var newPackageName = newApplicationName.text.toString().lowercase().replace("[^a-zA-Z0-9]", "")
        if (newPackageName.isEmpty() || newApplicationName.text.toString().equals(getApplicationName(applicationContext))) {
            newPackageName = generateRandomString(10)
        }
        newPackageName = "com.psdk." + newPackageName

        progressBarContainer.visibility = View.VISIBLE

        val tmpCacheExportDirectory = File(applicationContext.cacheDir.path + "/exports")
        if (tmpCacheExportDirectory.exists()) {
            deleteRecursive(tmpCacheExportDirectory)
        }
        tmpCacheExportDirectory.mkdir()

        val totalSteps = 6
        val leftOver = 5.0
        val progressIncrement = (100.0 - leftOver) / totalSteps
        var progressValue = 0
        val thread: Thread = object : Thread(null, null, "Thread-export-PSDK", 4096) {
            override fun run() {
                try {
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val tmpResultApkUnsigned = copySelfApkIntoTmpApk(progressBarTitle, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    changeApplicationId(progressBarTitle, newPackageName, tmpResultApkUnsigned, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
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

    private fun changeApplicationId(progressBarTitle: TextView, newPackageName:String, tmpResultApkUnsigned: File, tmpCacheExportDirectory: File) {
        runOnUiThread {
            progressBarTitle.setText("Modifying application identifier")
        }

        // Note: applicationId == package name (when compiled)
        val apkModule = ApkModule.loadApkFile(tmpResultApkUnsigned)
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

        apkModule.androidManifest.setApplicationLabel("LOL")

        val icon = apkModule.tableBlock.getResource(newPackageName, "drawable", "logo")


        // Write the modifications to the current zip file
        apkModule.writeApk(tmpResultApkUnsigned)
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