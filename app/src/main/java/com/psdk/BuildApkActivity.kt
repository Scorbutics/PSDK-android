package com.psdk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.psdk.signing.Signer
import com.psdk.signing.buildDefaultSigningOptions
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.File


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

    private fun shareApplicationOutput(appFolder: File) {
        /* TODO
            1. add possibility to edit the logo (replace the logo.png inside the archive)
            2. add a way to change the application name in strings.xml inside the archive (using XmlPullParser to parse and edit)
            3. change the applicationId
         */
        val progressBarContainer = findViewById<View>(R.id.progressBarContainer) as LinearLayout
        val progressBarTitle = findViewById<View>(R.id.progressBarTitle) as TextView
        val progressBar = findViewById<View>(R.id.progressBar) as ProgressBar
        progressBarContainer.visibility = View.VISIBLE
        val thread: Thread = object : Thread() {
            override fun run() {
                try {
                    runOnUiThread{
                        progressBarTitle.setText("Copying myself into temporary Apk")
                        progressBar.setProgress(10, true)
                    }
                    sleep(500)
                    val tmpResultApkUnsigned = File.createTempFile(
                        "app-output-unsigned",
                        ".apk",
                        applicationContext.cacheDir
                    )
                    File(applicationContext.applicationInfo.publicSourceDir).copyTo(
                        tmpResultApkUnsigned,
                        true
                    )

                    runOnUiThread{
                        progressBarTitle.setText("Bundling compiled game")
                        progressBar.setProgress(40, true)
                    }

                    val zipParameters = ZipParameters()
                    zipParameters.rootFolderNameInZip = "assets"
                    ZipFile(tmpResultApkUnsigned).addFolder(appFolder, zipParameters)

                    runOnUiThread{
                        progressBarTitle.setText("Signing final apk")
                        progressBar.setProgress(80, true)
                    }

                    val resultSignedApk = File.createTempFile(
                        "app-output-signed",
                        ".apk",
                        applicationContext.cacheDir
                    )
                    signApk(tmpResultApkUnsigned, resultSignedApk)

                    runOnUiThread{
                        progressBarTitle.setText("Starting export intent")
                        progressBar.setProgress(100, true)
                    }

                    sleep(1000)

                    runOnUiThread {
                        val share = Intent(Intent.ACTION_SEND)
                        share.type = "application/vnd.android.package-archive"
                        val finalApp = FileProvider.getUriForFile(
                            this@BuildApkActivity,
                            "com.psdk.starter.provider",
                            resultSignedApk
                        )
                        share.putExtra(Intent.EXTRA_STREAM, finalApp)
                        startActivity(Intent.createChooser(share, "Share App"))
                    }
                } finally {
                    runOnUiThread {
                        progressBarContainer.visibility = View.INVISIBLE
                    }
                }
            }
        }
        thread.start()
    }

    private fun signApk(apk: File, outApk: File) {
        val signingOptions = buildDefaultSigningOptions(application)
        Signer(signingOptions).signApk(apk, outApk)
    }
}