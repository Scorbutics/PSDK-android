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
import com.apk.axml.aXMLDecoder
import com.apk.axml.aXMLEncoder
import com.psdk.signing.Signer
import com.psdk.signing.buildDefaultSigningOptions
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


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

        val totalSteps = 5
        val leftOver = 5.0
        val progressIncrement = (100.0 - leftOver) / totalSteps
        var progressValue = 0
        val thread: Thread = object : Thread() {
            override fun run() {
                try {
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val tmpResultApkUnsigned = copySelfApkIntoTmpApk(progressBarTitle, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    changeApplicationId(progressBarTitle, newPackageName, tmpResultApkUnsigned, tmpCacheExportDirectory)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    bundleCompiledGame(progressBarTitle, appFolder, tmpResultApkUnsigned)
                    progressValue = incrementProgress(progressBar, progressValue, progressIncrement)
                    val resultSignedApk = signApk(progressBarTitle, tmpResultApkUnsigned, tmpCacheExportDirectory, newPackageName)
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

        val manifestFilename = "AndroidManifest.xml"
        val zipFile = ZipFile(tmpResultApkUnsigned.path)
        val fileHeader = zipFile.getFileHeader(manifestFilename)
        val androidManifestStream: InputStream = zipFile.getInputStream(fileHeader)
        val decodedManifest = aXMLDecoder().decode(androidManifestStream)
        val editedManifest = parseAndReplaceApplicationId(decodedManifest, newPackageName)
        val recodedManifest = aXMLEncoder().encodeString(this.applicationContext, editedManifest)

        val resultManifest = File.createTempFile("manifest", null, tmpCacheExportDirectory);
        Files.write(resultManifest.toPath(), recodedManifest)
        val zipParameters = ZipParameters()
        zipParameters.fileNameInZip  = manifestFilename
        zipParameters.isOverrideExistingFilesInZip = true
        zipFile.addFile(resultManifest)
        resultManifest.delete()
    }

    private fun parseAndReplaceApplicationId(decodedManifest: String, newPackageName: String): String {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(decodedManifest.byteInputStream(Charsets.UTF_8))
        // Normalize the XML structure
        doc.documentElement.normalize()
        // Get the manifest element
        val manifestElement = doc.getElementsByTagName("manifest").item(0)

        // Replace the package attribute
        if (manifestElement != null && manifestElement.attributes != null) {
            val packageNode = manifestElement.attributes.getNamedItem("package")
            packageNode.nodeValue = newPackageName
        }

        // Write the updated document back to the buffer
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val source = DOMSource(doc)
        val buffer = ByteArrayOutputStream()
        transformer.transform(source, StreamResult(buffer))
        val bytes = buffer.toByteArray()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun bundleCompiledGame(progressBarTitle: TextView, appFolder: File, tmpResultApkUnsigned: File) {
        runOnUiThread {
            progressBarTitle.setText("Bundling compiled game")
        }

        val zipParameters = ZipParameters()
        zipParameters.rootFolderNameInZip = "assets"
        ZipFile(tmpResultApkUnsigned).addFolder(appFolder, zipParameters)
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
                "com.psdk.starter.provider",
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