package com.psdk

import android.app.NativeActivity
import android.content.Intent
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.psdk.ruby.vm.RemoteListenerConfig
import com.psdk.ruby.vm.RubyScript
import com.scorbutics.rubyvm.RubyVMPaths
import java.io.File
import java.io.FileWriter

object GameLauncher {

    /**
     * In debug builds, publish the RGSS_REMOTE_* env vars so the
     * rgss_runtime native wrapper (native_main.c) reads them at boot
     * and arms the rdbg + line-eval listeners. Picked up by
     * ExecRubyScriptInlineWithRemote which routes through the same
     * boot-time arming as the JNI path.
     */
    private fun publishRemoteListenerEnv() {
        if (!RemoteListenerConfig.enabled) return
        Os.setenv("RGSS_REMOTE_DEBUG_HOST",    RemoteListenerConfig.DEBUG_HOST,           true)
        Os.setenv("RGSS_REMOTE_DEBUG_PORT",    RemoteListenerConfig.DEBUG_PORT.toString(), true)
        Os.setenv("RGSS_REMOTE_DEBUG_TOKEN",   RemoteListenerConfig.TOKEN,                true)
        Os.setenv("RGSS_REMOTE_DEBUG_SESSION", "psdk-game-debug",                          true)
        Os.setenv("RGSS_REMOTE_EVAL_HOST",     RemoteListenerConfig.EVAL_HOST,            true)
        Os.setenv("RGSS_REMOTE_EVAL_PORT",     RemoteListenerConfig.EVAL_PORT.toString(),  true)
        Os.setenv("RGSS_REMOTE_EVAL_TOKEN",    RemoteListenerConfig.TOKEN,                true)
        Os.setenv("RGSS_REMOTE_EVAL_SESSION",  "psdk-game-eval",                           true)
    }
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
            publishRemoteListenerEnv()
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
            publishRemoteListenerEnv()
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
