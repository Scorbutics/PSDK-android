package com.psdk.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.psdk.AppModeDetector
import com.psdk.R
import com.psdk.compilation.CompilationEngine
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.zip.EpsaArchive
import com.scorbutics.rubyvm.RubyVMPaths
import java.io.File

class ClassicSetupActivity : AppCompatActivity() {

    private var logoPulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classic_setup)

        val gameIcon = findViewById<ImageView>(R.id.gameIcon)
        val gameTitle = findViewById<TextView>(R.id.gameTitle)
        val statusText = findViewById<TextView>(R.id.statusText)
        val progressBar = findViewById<LinearProgressIndicator>(R.id.setupProgressBar)
        val viewLogsButton = findViewById<MaterialButton>(R.id.viewLogsButton)

        gameTitle.text = applicationInfo.loadLabel(packageManager)
        progressBar.isIndeterminate = true

        logoPulse = ObjectAnimator.ofPropertyValuesHolder(
            gameIcon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.12f)
        ).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        Thread {
            try {
                // Step 1: Initialize Ruby VM
                updateStatus(statusText, "Initializing engine...")
                RubyVMPaths.getDefaultPaths(this@ClassicSetupActivity)

                // Step 2: Extract .epsa from assets
                updateStatus(statusText, "Extracting archive...")
                val epsaAssetName = AppModeDetector.findEpsaInAssets(this) ?: throw Exception("No .epsa found in assets")
                val executionLocation = applicationInfo.dataDir
                val stagingFile = File(executionLocation, "archive.staging")

                assets.open(epsaAssetName).use { input ->
                    stagingFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Step 3: Validate the v4 header and derive K_enc / K_mac.
                // No on-disk decryption — the keys are passed to Ruby via env
                // vars and used by EpsaStream to decrypt on the fly.
                updateStatus(statusText, "Verifying archive...")
                val archive = when (val r = EpsaArchive.resolve(this@ClassicSetupActivity, stagingFile)) {
                    is EpsaArchive.Result.Failure -> throw Exception("Archive verification failed: ${r.message}")
                    is EpsaArchive.Result.Ok      -> ArchiveKeys(
                        epsaPath  = r.keys.epsaPath,
                        encKeyHex = r.keys.encKey.toHex(),
                        macKeyHex = r.keys.macKey.toHex()
                    )
                }

                // Step 4: Compile
                updateStatus(statusText, "Compiling game data...")
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 33
                }

                val compilationComplete = Object()
                var compilationSuccess = false
                var compilationLogFile: File? = null
                var compilationErrorLogFile: File? = null

                val engine = CompilationEngine(
                    context = this,
                    executionLocation = executionLocation,
                    archive = archive,
                    callback = object : CompilationEngine.CompilationCallback {
                        override fun onStepStarted(stepIndex: Int, stepName: String) {
                            updateStatus(statusText, stepName + "...")
                            runOnUiThread {
                                progressBar.progress = STEP_BASE_PROGRESS + (stepIndex * STEP_PROGRESS_SPAN)
                            }
                        }

                        override fun onStepCompleted(stepIndex: Int, success: Boolean) {}

                        override fun onLogMessage(stepIndex: Int, message: String) {
                            if (stepIndex != COMPILATION_STEP_INDEX) return
                            val sub = inferCompilationSubStep(message) ?: return
                            updateStatus(statusText, sub.label)
                            val base = STEP_BASE_PROGRESS + COMPILATION_STEP_INDEX * STEP_PROGRESS_SPAN
                            val target = base + (STEP_PROGRESS_SPAN * sub.fraction).toInt()
                            runOnUiThread {
                                if (target > progressBar.progress) progressBar.progress = target
                            }
                        }

                        override fun onCompilationFinished(success: Boolean, logFile: File, errorLogFile: File) {
                            compilationSuccess = success
                            compilationLogFile = logFile
                            compilationErrorLogFile = errorLogFile
                            synchronized(compilationComplete) {
                                compilationComplete.notify()
                            }
                        }
                    }
                )

                engine.start()

                // Wait for compilation to finish
                synchronized(compilationComplete) {
                    compilationComplete.wait()
                }

                if (!compilationSuccess) {
                    val logPath = compilationLogFile?.absolutePath
                    val errorLogPath = compilationErrorLogFile?.absolutePath
                    runOnUiThread {
                        statusText.text = "Compilation failed"
                        statusText.setTextColor(getColor(R.color.colorError))
                        progressBar.setIndicatorColor(getColor(R.color.colorError))
                        progressBar.progress = 100
                        if (logPath != null) {
                            viewLogsButton.visibility = View.VISIBLE
                            viewLogsButton.setOnClickListener {
                                LogBottomSheetFragment.newInstance(logPath, errorLogPath, "Compilation Logs")
                                    .show(supportFragmentManager, "compilation_logs")
                            }
                        }
                    }
                    return@Thread
                }

                // Step 5: Save state and prompt restart
                runOnUiThread {
                    progressBar.progress = 100
                }
                updateStatus(statusText, "Setup complete!")

                val prefs = getSharedPreferences(CLASSIC_MODE_PREFS, MODE_PRIVATE)
                val currentVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
                prefs.edit()
                    .putString(CLASSIC_MODE_STATE_KEY, STATE_SETUP_COMPLETE)
                    .putLong("VERSION_CODE", currentVersionCode)
                    .apply()

                Thread.sleep(500)

                runOnUiThread {
                    showRestartDialog()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Setup failed: ${e.localizedMessage}"
                    statusText.setTextColor(getColor(R.color.colorError))
                    progressBar.setIndicatorColor(getColor(R.color.colorError))
                    progressBar.progress = 100
                }
            }
        }.start()
    }

    override fun onDestroy() {
        logoPulse?.cancel()
        logoPulse = null
        super.onDestroy()
    }

    private fun updateStatus(statusText: TextView, text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun showRestartDialog() {
        MaterialAlertDialogBuilder(this, R.style.Theme_Psdk_Dialog)
            .setTitle("Setup Complete")
            .setMessage("The game has been set up successfully. The app needs to restart to launch the game.")
            .setCancelable(false)
            .setPositiveButton("Restart Now") { _, _ -> restartApp() }
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private data class SubStep(val label: String, val fraction: Float)

    // Fractions are calibrated from observed compile timings (~56s total): loading
    // dominates the first half, graphics + audio copy dominate the rest.
    private fun inferCompilationSubStep(message: String): SubStep? = when {
        message.startsWith("Loading Game")                       -> SubStep("Loading game data...",       0.00f)
        message.startsWith("Loading /data/")                     -> SubStep("Loading project scripts...", 0.05f)
        message.startsWith("Progress: Start script compilation") -> SubStep("Compiling scripts...",       0.45f)
        message.startsWith("Progress: Make graphics resources")  -> SubStep("Processing graphics...",     0.55f)
        message.startsWith("Progress: Make data")                -> SubStep("Building data files...",     0.80f)
        message.startsWith("Progress: Copy lib")                 -> SubStep("Copying libraries...",       0.82f)
        message.startsWith("Progress: Copy audio")               -> SubStep("Copying audio assets...",    0.84f)
        message.startsWith("Progress: Copy binaries")            -> SubStep("Copying binaries...",        0.98f)
        message.startsWith("Compilation done!")                  -> SubStep("Finalizing...",              1.00f)
        else -> null
    }

    companion object {
        const val CLASSIC_MODE_PREFS = "CLASSIC_MODE"
        const val CLASSIC_MODE_STATE_KEY = "CLASSIC_MODE_STATE"
        const val STATE_NEEDS_SETUP = "NEEDS_SETUP"
        const val STATE_SETUP_COMPLETE = "SETUP_COMPLETE"
        const val STATE_READY = "READY"

        private const val STEP_BASE_PROGRESS = 33
        private const val STEP_PROGRESS_SPAN = 22
        private const val COMPILATION_STEP_INDEX = 2
    }
}
