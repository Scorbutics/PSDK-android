package com.psdk.ruby.vm

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import com.scorbutics.rubyvm.BuildInfo
import com.scorbutics.rubyvm.execute
import com.scorbutics.rubyvm.LibraryConfig
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.RubyVMPaths
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
                location.archive?.let { archive ->
                    Os.setenv("PSDK_EPSA_PATH",        archive.epsaPath,   true)
                    Os.setenv("PSDK_EPSA_KEY_HEX",     archive.encKeyHex,  true)
                    Os.setenv("PSDK_EPSA_MAC_KEY_HEX", archive.macKeyHex,  true)
                }

                val wrappedContent = if (location.executionLocation != null) {
                    "Dir.chdir('${location.executionLocation}')\n${script.getContent()}"
                } else {
                    script.getContent()
                }

                // No timeout: workloads (a game session, the splash loader,
                // a compile) legitimately run for hours. A timeout used to
                // report the still-running script as completed after 600 s,
                // which made GameActivity finish() the game mid-session.
                val latch = CountDownLatch(1)
                var exitCode = 1
                interpreter!!.execute(wrappedContent, latch) { exitCode = it }
                latch.await()
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
            armRemoteListeners(interpreter!!)
        }
    }

    /**
     * In debug builds, arm both the rdbg/DAP listener and the line-eval
     * console on the just-created interpreter. The KMP API supports
     * coexistence (debug listener bound first, eval injected via the FIFO
     * interpreter post-boot). Logs the result but does not fail the
     * interpreter setup — a missing port is dev-friction, not a crash.
     *
     * Latched at process scope: the underlying VM is a singleton
     * (g_global_vm) and DEBUGGER__ is a Ruby singleton, so the listeners
     * are global. Re-arming on every interpreter wastes work AND trips a
     * bug in debug gem 1.4.0 — `DEBUGGER__.open` calls reset_ui with a
     * fresh UI_TcpServer it never activates, then a third call tries to
     * deactivate that never-activated UI and dies on a nil @reader_thread.
     */
    private fun armRemoteListeners(interp: KmpInterpreter) {
        if (!RemoteListenerConfig.enabled) return
        if (!remoteListenersArmed.compareAndSet(false, true)) {
            Log.i(TAG, "remote listeners already armed for this process; skipping")
            return
        }

        val dbgRc = interp.enableRemoteDebug(
            host = RemoteListenerConfig.DEBUG_HOST,
            port = RemoteListenerConfig.debugPort,
            token = RemoteListenerConfig.TOKEN,
            sessionName = "psdk-launcher-debug",
        )
        Log.i(TAG, "enableRemoteDebug rc=$dbgRc on ${RemoteListenerConfig.DEBUG_HOST}:${RemoteListenerConfig.debugPort}")

        val evalRc = interp.enableRemoteEval(
            host = RemoteListenerConfig.EVAL_HOST,
            port = RemoteListenerConfig.evalPort,
            token = RemoteListenerConfig.TOKEN,
            sessionName = "psdk-launcher-eval",
        )
        Log.i(TAG, "enableRemoteEval rc=$evalRc on ${RemoteListenerConfig.EVAL_HOST}:${RemoteListenerConfig.evalPort}")
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        const val TAG = "PsdkInterpreter"

        private val remoteListenersArmed = AtomicBoolean(false)

        /** rgss-runtime identity for the AAR currently linked into the app. */
        val rgssRuntimeVersion: String get() = BuildInfo.VERSION
        val rgssRuntimeBuildTimestamp: String get() = BuildInfo.BUILD_TIMESTAMP
        val rgssRuntimeBanner: String
            get() = "rgss-runtime $rgssRuntimeVersion (built $rgssRuntimeBuildTimestamp)"

        fun initialize() {
            LibraryConfig.libraryName = "rgss_runtime"
            Log.i(TAG, rgssRuntimeBanner)
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

/**
 * Bundle of (path, K_enc, K_mac) needed to mount the encrypted .epsa via
 * the streaming decrypter. Keys are passed as hex strings because they
 * travel through the process environment via Os.setenv → Ruby ENV.
 */
data class ArchiveKeys(
    val epsaPath: String,
    val encKeyHex: String,
    val macKeyHex: String
)

data class ScriptLocation(
    val executionLocation: String?,
    val archive: ArchiveKeys?
)
