package com.psdk.ruby.vm

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import java.io.File
import com.scorbutics.rubyvm.ExecutionResult
import com.scorbutics.rubyvm.LibraryConfig
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.RubyVMPaths
import com.scorbutics.rubyvm.executeWithResult
import com.scorbutics.rubyvm.RubyInterpreter as KmpInterpreter

typealias CompletionTask = (Int) -> Unit

class PsdkInterpreter private constructor(
    private val paths: RubyVMPaths.Paths,
    private val assets: AssetManager,
    private val listener: LogListener,
    private val onError: ((Exception) -> Unit)? = null
) {
    private var interpreter: KmpInterpreter? = null

    fun enqueue(script: RubyScript, location: ScriptLocation, onComplete: CompletionTask) {
        Thread {
            try {
                ensureInterpreter()
                location.executionLocation?.let { Os.setenv("PSDK_EXECUTION_LOCATION", it, true) }
                location.archiveLocation?.let { Os.setenv("PSDK_ANDROID_ADDITIONAL_PARAM", it, true) }

                val wrappedContent = if (location.executionLocation != null) {
                    "Dir.chdir('${location.executionLocation}')\n${script.getContent()}"
                } else {
                    script.getContent()
                }

                val result = interpreter!!.executeWithResult(
                    scriptContent = wrappedContent,
                    timeoutSeconds = 600
                )
                val exitCode = when (result) {
                    is ExecutionResult.Success -> result.exitCode
                    is ExecutionResult.Failure -> 1
                    is ExecutionResult.Timeout -> 1
                }
                onComplete.invoke(exitCode)
            } catch (e: Exception) {
                onError?.invoke(e)
                onComplete.invoke(1)
            }
        }.start()
    }

    private fun ensureInterpreter() {
        if (interpreter == null) {
            interpreter = KmpInterpreter.create(
                appPath = ".",
                rubyBaseDir = paths.rubyBaseDir,
                nativeLibsDir = paths.nativeLibsDir,
                listener = listener
            )
            interpreter!!.enableLogging()
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        fun initialize() {
            LibraryConfig.libraryName = "rgss_runtime"
        }

        fun create(
            context: Context,
            listener: LogListener,
            onError: ((Exception) -> Unit)? = null
        ): PsdkInterpreter {
            val paths = RubyVMPaths.getDefaultPaths(context)
            return PsdkInterpreter(paths, context.assets, listener, onError)
        }

    }
}

data class ScriptLocation(
    val executionLocation: String?,
    val archiveLocation: String?
)
