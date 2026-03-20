package com.psdk

import android.app.NativeActivity
import android.content.Intent
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.psdk.ruby.vm.RubyScript
import com.scorbutics.rubyvm.RubyVMPaths
import java.io.File
import java.io.FileWriter

object GameLauncher {
    fun launch(
        activity: ComponentActivity,
        executionLocation: String,
        releaseLocation: String,
        launcher: ActivityResultLauncher<Intent>
    ) {
        Thread {
            val paths = RubyVMPaths.getDefaultPaths(activity)
            val startScript = RubyScript.readFromAssets(activity.assets, "start.rb")
            val scriptFile = File(activity.filesDir, "start.rb")
            scriptFile.writeText(startScript)

            Os.setenv("RGSS_RUBY_BASE_DIR", paths.rubyBaseDir, true)
            Os.setenv("RGSS_NATIVE_LIBS_DIR", paths.nativeLibsDir, true)
            Os.setenv("RGSS_SCRIPT_PATH", scriptFile.absolutePath, true)
            Os.setenv("RGSS_EXECUTION_LOCATION", executionLocation, true)
            Os.setenv("RGSS_LOG_FILE", "$executionLocation/last_stdout.log", true)
            Os.setenv("RGSS_ERROR_LOG_FILE", "$releaseLocation/Error.log", true)

            FileWriter("$executionLocation/last_stdout.log", false).flush()

            activity.runOnUiThread {
                val intent = Intent(activity, NativeActivity::class.java)
                launcher.launch(intent)
            }
        }.start()
    }

    fun launchDirect(activity: ComponentActivity, executionLocation: String) {
        val releaseLocation = "$executionLocation/Release"
        Thread {
            val paths = RubyVMPaths.getDefaultPaths(activity)
            val startScript = RubyScript.readFromAssets(activity.assets, "start.rb")
            val scriptFile = File(activity.filesDir, "start.rb")
            scriptFile.writeText(startScript)

            Os.setenv("RGSS_RUBY_BASE_DIR", paths.rubyBaseDir, true)
            Os.setenv("RGSS_NATIVE_LIBS_DIR", paths.nativeLibsDir, true)
            Os.setenv("RGSS_SCRIPT_PATH", scriptFile.absolutePath, true)
            Os.setenv("RGSS_EXECUTION_LOCATION", executionLocation, true)
            Os.setenv("RGSS_LOG_FILE", "$executionLocation/last_stdout.log", true)
            Os.setenv("RGSS_ERROR_LOG_FILE", "$executionLocation/Error.log", true)

            FileWriter("$executionLocation/last_stdout.log", false).flush()

            activity.runOnUiThread {
                val intent = Intent(activity, NativeActivity::class.java)
                activity.startActivity(intent)
            }
        }.start()
    }
}
