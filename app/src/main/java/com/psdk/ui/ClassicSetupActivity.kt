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
import com.psdk.compilation.ArchiveValidator
import com.psdk.compilation.CompilationEngine
import com.psdk.compilation.CompileSubStepMapper
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import com.scorbutics.rubyvm.RubyVMPaths
import java.io.File

class ClassicSetupActivity : AppCompatActivity() {

    private var logoPulse: ObjectAnimator? = null

    // Splash interpreter — runs loader.rb in its own Ruby Thread. No
    // archive bound: loader.rb reads its assets from
    // <executionLocation>/loader_assets/, which ArchiveValidator
    // populated during the "Verifying archive..." step. Lifecycle: created
    // once the GL surface is ready AND the execution location is known;
    // closed in onDestroy.
    private var loaderInterpreter: PsdkInterpreter? = null

    private val loaderLaunchLock = Any()
    private var loaderSurfaceReady = false
    private var loaderExecutionLocation: String? = null
    private var loaderLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classic_setup)

        val gameIcon = findViewById<ImageView>(R.id.gameIcon)
        val gameTitle = findViewById<TextView>(R.id.gameTitle)
        val statusText = findViewById<TextView>(R.id.statusText)
        val progressBar = findViewById<LinearProgressIndicator>(R.id.setupProgressBar)
        val viewLogsButton = findViewById<MaterialButton>(R.id.viewLogsButton)
        val glSurface = findViewById<LiteRgssSurfaceView>(R.id.glSurface)

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

        // Reveal the GL surface immediately so the splash interpreter
        // can start as soon as Android lays out the SurfaceView. The
        // compile thread runs in parallel inside its own PsdkInterpreter;
        // both share the process Ruby VM via the fifo_interpreter
        // dispatcher (one thread per interpreter, cooperative GVL).
        //
        // alpha=0 until loader.rb emits LOADER_READY_SENTINEL (its first
        // @window.update has flipped a frame onto EGL) — hides the
        // SurfaceView's black punch-through between attach and first draw.
        glSurface.alpha = 0f
        glSurface.onSurfaceReady = { w, h ->
            android.util.Log.i(LOADER_TAG, "surface ready (${w}x${h})")
            synchronized(loaderLaunchLock) {
                loaderSurfaceReady = true
                tryLaunchLoaderLocked(glSurface)
            }
        }
        glSurface.visibility = View.VISIBLE

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

                // Step 3: Validate the v4 header, derive K_enc / K_mac,
                // and extract loader_assets/ for the splash. The Ruby
                // extraction pass is part of validation now — its failure
                // (e.g. no font in Fonts/) means the archive is unusable.
                updateStatus(statusText, "Verifying archive...")
                val validation = ArchiveValidator.validate(this@ClassicSetupActivity, stagingFile, executionLocation)
                val archive = if (validation.isValid && validation.archive != null) {
                    validation.archive
                } else {
                    throw Exception("Archive verification failed: ${validation.error ?: "unknown error"}")
                }

                // Publish the execution location so the splash interpreter
                // can launch once the surface is ready. Whichever event
                // fires last actually starts loader.rb.
                synchronized(loaderLaunchLock) {
                    loaderExecutionLocation = executionLocation
                    android.util.Log.i(LOADER_TAG, "archive valid (execLoc=$executionLocation epsa=${archive.epsaPath})")
                    tryLaunchLoaderLocked(glSurface)
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
                    initialCallback = object : CompilationEngine.CompilationCallback {
                        override fun onStepStarted(stepIndex: Int, stepName: String) {
                            updateStatus(statusText, stepName + "...")
                            runOnUiThread {
                                progressBar.progress = STEP_BASE_PROGRESS + (stepIndex * STEP_PROGRESS_SPAN)
                            }
                        }

                        override fun onStepCompleted(stepIndex: Int, success: Boolean) {}

                        override fun onLogMessage(stepIndex: Int, message: String) {
                            if (stepIndex != COMPILATION_STEP_INDEX) return
                            val sub = CompileSubStepMapper.fromLog(message) ?: return
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

        loaderInterpreter?.close()
        loaderInterpreter = null

        super.onDestroy()
    }

    private fun updateStatus(statusText: TextView, text: String) {
        runOnUiThread { statusText.text = text }
    }

    /**
     * Fire loader.rb once the SurfaceView has reported its dimensions
     * AND the execution location is known. Caller MUST hold
     * loaderLaunchLock.
     */
    private fun tryLaunchLoaderLocked(glSurface: LiteRgssSurfaceView) {
        if (loaderLaunched) return
        if (!loaderSurfaceReady) return
        val executionLocation = loaderExecutionLocation ?: return

        android.util.Log.i(LOADER_TAG, "launching loader.rb (execLoc=$executionLocation)")
        val listener = object : LogListener {
            override fun onLogMessage(logMessage: LogMessage) {
                android.util.Log.i(LOADER_TAG, logMessage.message)
                if (logMessage.message.contains(LOADER_READY_SENTINEL)) {
                    glSurface.post {
                        glSurface.animate()
                            .alpha(1f)
                            .setDuration(LOADER_FADE_IN_MS)
                            .start()
                    }
                }
            }
        }
        val onErr: (Exception) -> Unit = { e ->
            android.util.Log.e(LOADER_TAG, "loader error", e)
        }

        val loader = PsdkInterpreter.create(this, listener, onErr)
        loaderInterpreter = loader
        loader.enqueue(
            script = RubyScript(assets, LOADER_SCRIPT),
            location = ScriptLocation(executionLocation = executionLocation, archive = null),
            onComplete = { exitCode ->
                android.util.Log.i(LOADER_TAG, "loader.rb exited (code=$exitCode)")
            }
        )

        loaderLaunched = true
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

    companion object {
        const val CLASSIC_MODE_PREFS = "CLASSIC_MODE"
        const val CLASSIC_MODE_STATE_KEY = "CLASSIC_MODE_STATE"
        const val STATE_NEEDS_SETUP = "NEEDS_SETUP"
        const val STATE_SETUP_COMPLETE = "SETUP_COMPLETE"
        const val STATE_READY = "READY"

        private const val STEP_BASE_PROGRESS = 33
        private const val STEP_PROGRESS_SPAN = 22
        private const val COMPILATION_STEP_INDEX = 2

        private const val LOADER_SCRIPT = "loader.rb"

        /** Single tag for the splash interpreter lifecycle —
         *  searchable in logcat with `adb logcat -s 'ClassicSetup-loader'`. */
        private const val LOADER_TAG = "ClassicSetup-loader"

        /** Matches Loader::READY_SENTINEL in loader.rb. */
        private const val LOADER_READY_SENTINEL = "[loader-ready]"
        private const val LOADER_FADE_IN_MS = 180L
    }
}
