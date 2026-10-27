package com.psdk.compilation

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.psdk.CompileStepData
import com.psdk.CompileStepLogs
import com.psdk.CompileStepStatus
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.rubyvm.LogLevel
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompilationEngine(
    private val context: Context,
    private val executionLocation: String,
    private val archive: ArchiveKeys,
    initialCallback: CompilationCallback? = null
) {
    interface CompilationCallback {
        fun onStepStarted(stepIndex: Int, stepName: String)
        fun onStepCompleted(stepIndex: Int, success: Boolean)
        fun onLogMessage(stepIndex: Int, message: String)
        fun onCompilationFinished(success: Boolean, logFile: File, errorLogFile: File)
    }

    /**
     * Snapshot of the engine's current state at the moment of a callback
     * swap. `stepLogs` is a deep copy (StringBuilders) so the consumer can
     * read it on any thread without racing further log appends.
     */
    data class Snapshot(
        val stepLogs: List<CompileStepLogs>,
        val activeStepIndex: Int,
        val finishResult: FinishResult?
    )

    data class FinishResult(val success: Boolean, val logFile: File, val errorLogFile: File)

    // Swappable so the engine can outlive its current consumer (e.g.
    // a fragment surviving a config change re-attaches via setCallback).
    // @Volatile because it's read from the Ruby-VM dispatcher thread
    // (step events) and the logExecutor thread (log events).
    @Volatile
    private var callback: CompilationCallback? = initialCallback

    private val allStepLogs = mutableListOf<CompileStepLogs>()
    private var currentLogIndex = 0
    private var interpreter: PsdkInterpreter? = null
    // Written from the Ruby dispatcher thread on completion, read from the UI
    // thread via [isFinished] when the wizard decides whether to reuse this
    // engine for a retry — needs visibility across threads.
    @Volatile
    private var finishResult: FinishResult? = null
    private var closed = false

    /**
     * True once the engine has dispatched its terminal
     * `onCompilationFinished` callback (success or failure). A finished
     * engine cannot be restarted; callers must discard it and build a new
     * one to retry compilation.
     */
    fun isFinished(): Boolean = finishResult != null

    // Log writes and step switches are serialized through this single-threaded executor.
    // Without it, logs from script N can be tagged with step N+1 because the JNI listener
    // fires from a different thread than the one bumping currentLogIndex.
    private val logExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CompilationEngine-LogDispatch").apply { isDaemon = true }
    }

    private fun setActiveStep(idx: Int) {
        logExecutor.submit { currentLogIndex = idx }.get()
    }

    private fun shutdownLogExecutor() {
        logExecutor.shutdown()
        logExecutor.awaitTermination(5, TimeUnit.SECONDS)
    }

    fun start() {
        activeEngines.add(this)
        val checkEngineLogs = CompileStepLogs(CompileStepData("Check engine", CompileStepStatus.IN_PROGRESS), StringBuilder())
        val backupSavesLogs = CompileStepLogs(CompileStepData("Backing up previous Release", CompileStepStatus.READY), StringBuilder())
        val compilationLogs = CompileStepLogs(CompileStepData("Compilation", CompileStepStatus.READY), StringBuilder())
        val copySavesLogs = CompileStepLogs(CompileStepData("Copying saves", CompileStepStatus.READY), StringBuilder())
        allStepLogs.addAll(listOf(checkEngineLogs, backupSavesLogs, compilationLogs, copySavesLogs))

        currentLogIndex = 0
        callback?.onStepStarted(0, checkEngineLogs.step.title)

        interpreter = PsdkInterpreter.create(
            context = context,
            listener = object : LogListener {
                override fun onLogMessage(logMessage: LogMessage) {
                    val msg = logMessage.message
                    val isError = logMessage.level === LogLevel.ERROR
                    logExecutor.execute {
                        val idx = currentLogIndex
                        allStepLogs[idx].logs.appendLine(msg)
                        if (isError) {
                            allStepLogs[idx].errorLogs.appendLine(msg)
                        }
                        callback?.onLogMessage(idx, msg)
                    }
                }
            },
            onError = { e ->
                callback?.onLogMessage(currentLogIndex, "Error: ${e.localizedMessage}")
            }
        )

        val location = ScriptLocation(executionLocation, archive)

        val finishWithFailure: (Int) -> Unit = { failedStepIndex ->
            interpreter?.close()
            interpreter = null
            restoreReleaseBackup(failedStepIndex)
            val (logFile, errorLogFile) = saveLogsToFile()
            finishResult = FinishResult(false, logFile, errorLogFile)
            callback?.onCompilationFinished(false, logFile, errorLogFile)
        }

        val onCompleteCopySaves: CompletionTask = { returnCode: Int ->
            if (returnCode != 0) {
                allStepLogs[3].step.status = CompileStepStatus.ERROR
                callback?.onStepCompleted(3, false)
                finishWithFailure(3)
            } else {
                allStepLogs[3].step.status = CompileStepStatus.SUCCESS
                callback?.onStepCompleted(3, true)
                interpreter?.close()
                interpreter = null
                val (logFile, errorLogFile) = saveLogsToFile()
                finishResult = FinishResult(true, logFile, errorLogFile)
                callback?.onCompilationFinished(true, logFile, errorLogFile)
            }
        }

        val onCompleteCompilation: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[2].step.status = CompileStepStatus.ERROR
                callback?.onStepCompleted(2, false)
                finishWithFailure(2)
            } else {
                allStepLogs[2].step.status = CompileStepStatus.SUCCESS
                callback?.onStepCompleted(2, true)
                setActiveStep(3)
                allStepLogs[3].step.status = CompileStepStatus.IN_PROGRESS
                callback?.onStepStarted(3, allStepLogs[3].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, COPY_SAVES_SCRIPT, ARCHIVE_PRELUDES), location, onCompleteCopySaves)
            }
        }

        val onCompleteBackup: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[1].step.status = CompileStepStatus.ERROR
                callback?.onStepCompleted(1, false)
                finishWithFailure(1)
            } else {
                allStepLogs[1].step.status = CompileStepStatus.SUCCESS
                callback?.onStepCompleted(1, true)
                setActiveStep(2)
                allStepLogs[2].step.status = CompileStepStatus.IN_PROGRESS
                callback?.onStepStarted(2, allStepLogs[2].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, RUBY_COMPILE_SCRIPT, ARCHIVE_PRELUDES), location, onCompleteCompilation)
            }
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[0].step.status = CompileStepStatus.ERROR
                callback?.onStepCompleted(0, false)
                interpreter?.close()
                interpreter = null
                val (logFile, errorLogFile) = saveLogsToFile()
                finishResult = FinishResult(false, logFile, errorLogFile)
                callback?.onCompilationFinished(false, logFile, errorLogFile)
            } else {
                allStepLogs[0].step.status = CompileStepStatus.SUCCESS
                callback?.onStepCompleted(0, true)
                setActiveStep(1)
                allStepLogs[1].step.status = CompileStepStatus.IN_PROGRESS
                callback?.onStepStarted(1, allStepLogs[1].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, BACKUP_SAVES_SCRIPT), location, onCompleteBackup)
            }
        }

        try {
            interpreter!!.enqueue(RubyScript(context.assets, CHECK_ENGINE_SCRIPT, ARCHIVE_PRELUDES), location, onCompleteCheck)
        } catch (ex: Exception) {
            callback?.onLogMessage(0, "Fatal error: ${ex.localizedMessage}")
            val (logFile, errorLogFile) = saveLogsToFile()
            finishResult = FinishResult(false, logFile, errorLogFile)
            callback?.onCompilationFinished(false, logFile, errorLogFile)
        }
    }

    /**
     * Atomically swap the callback target and snapshot the engine's
     * current step state. The snapshot reflects everything dispatched up
     * to this call; future events go through `newCallback`. The swap is
     * serialized through the same executor that dispatches log events, so
     * no log event can fall between the snapshot and `newCallback`'s
     * first invocation. Caller MUST replay the snapshot to its UI before
     * returning to the message loop — otherwise out-of-order log events
     * could overtake the replay.
     */
    fun setCallback(newCallback: CompilationCallback?): Snapshot {
        val swap = java.util.concurrent.Callable<Snapshot> {
            callback = newCallback
            // Deep-copy log buffers — caller reads on the UI thread while
            // we keep appending here. Status is an enum (atomic ref write),
            // safe to read concurrently.
            val copies = allStepLogs.map { sl ->
                CompileStepLogs(
                    step = sl.step.copy(),
                    logs = StringBuilder(sl.logs),
                    errorLogs = StringBuilder(sl.errorLogs)
                )
            }
            Snapshot(copies, currentLogIndex, finishResult)
        }
        return if (logExecutor.isShutdown) swap.call() else logExecutor.submit(swap).get()
    }

    /** Tear down the engine. Safe to call from `ViewModel.onCleared()`. */
    fun close() {
        if (closed) return
        closed = true
        activeEngines.remove(this)
        callback = null
        interpreter?.close()
        interpreter = null
        if (!logExecutor.isShutdown) {
            logExecutor.shutdown()
        }
    }

    private fun restoreReleaseBackup(failedStepIndex: Int) {
        restoreReleaseBackup(executionLocation) { callback?.onLogMessage(failedStepIndex, it) }
    }

    fun getAllLogs(): List<CompileStepLogs> = allStepLogs.toList()

    fun getLogsAsString(): String {
        val sb = StringBuilder()
        for (stepLogs in allStepLogs) {
            sb.appendLine("=== ${stepLogs.step.title} [${stepLogs.step.status}] ===")
            sb.appendLine(stepLogs.logs.toString())
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun saveLogsToFile(): Pair<File, File> {
        // Drain pending log writes before reading the buffers, otherwise the file
        // can be missing the tail of the last script's output.
        shutdownLogExecutor()
        val logFile = File(executionLocation, COMPILATION_LOG_FILE)
        logFile.bufferedWriter().use { writer ->
            for (stepLogs in allStepLogs) {
                writer.appendLine("=== ${stepLogs.step.title} [${stepLogs.step.status}] ===")
                writer.appendLine(stepLogs.logs.toString())
                writer.newLine()
            }
        }
        val errorLogFile = File(executionLocation, COMPILATION_ERROR_LOG_FILE)
        errorLogFile.bufferedWriter().use { writer ->
            for (stepLogs in allStepLogs) {
                if (stepLogs.errorLogs.isEmpty()) continue
                writer.appendLine("=== ${stepLogs.step.title} [${stepLogs.step.status}] ===")
                writer.appendLine(stepLogs.errorLogs.toString())
                writer.newLine()
            }
        }
        return logFile to errorLogFile
    }

    companion object {
        private const val BACKUP_SAVES_SCRIPT = "backup_saves.rb"
        private const val COPY_SAVES_SCRIPT = "copy_saves.rb"
        private const val RUBY_COMPILE_SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
        const val COMPILATION_LOG_FILE = "last_compilation.log"
        const val COMPILATION_ERROR_LOG_FILE = "last_compilation_errors.log"
        private const val TAG = "CompilationEngine"

        // Engines started in this process; an engine counts as running until
        // it finishes or is closed. Lets [recoverInterruptedCompilation] tell
        // a live ReleaseBackup (mid-compile) from an orphaned one.
        private val activeEngines: MutableSet<CompilationEngine> = ConcurrentHashMap.newKeySet()

        private fun isCompiling(executionLocation: String): Boolean {
            activeEngines.removeIf { it.isFinished() }
            val target = File(executionLocation).absoluteFile.normalize()
            return activeEngines.any { File(it.executionLocation).absoluteFile.normalize() == target }
        }

        /**
         * Restore the previous Release when a compilation was interrupted
         * before it could clean up (process killed, engine closed mid-run).
         * ReleaseBackup then still holds the last good build and the user
         * saves, while Release is a partial build that must not be launched.
         * No-op while a compilation is running on [executionLocation] in this
         * process. Call it before reading the Release state of a project.
         *
         * @return true if a backup was restored.
         */
        fun recoverInterruptedCompilation(executionLocation: String): Boolean {
            if (!File(executionLocation, "ReleaseBackup").isDirectory || isCompiling(executionLocation)) {
                return false
            }
            Log.w(TAG, "Interrupted compilation detected in $executionLocation, restoring the previous Release")
            return restoreReleaseBackup(executionLocation) { Log.w(TAG, it) }
        }

        private fun restoreReleaseBackup(executionLocation: String, log: (String) -> Unit): Boolean {
            val releaseDir = File(executionLocation, "Release")
            val backupDir = File(executionLocation, "ReleaseBackup")
            if (!backupDir.exists()) {
                log("No Release backup to restore.")
                return false
            }
            try {
                if (releaseDir.exists() && !releaseDir.deleteRecursively()) {
                    log("Warning: failed to fully clean Release folder before restore.")
                }
                if (backupDir.renameTo(releaseDir)) {
                    log("Restored previous Release folder from backup.")
                    return true
                }
                log("Error: failed to rename ReleaseBackup back to Release.")
            } catch (ex: Exception) {
                log("Error while restoring backup: ${ex.localizedMessage}")
            }
            return false
        }

        // Ruby preludes prepended to scripts that mount the archive. They
        // define EpsaFormat, EpsaStream, and ArchiveMount so check_engine.rb
        // / compile.rb / copy_saves.rb / extract_loader_assets.rb can call
        // ArchiveMount.mount! without a load path. Order matters:
        // epsa_stream depends on epsa_format, archive_mount on epsa_stream.
        //
        // Made `internal` (was `private`) so ArchiveValidator can reuse
        // the same bundle for its one-shot extract_loader_assets.rb pass.
        internal val ARCHIVE_PRELUDES = listOf(
            "lib/epsa_format.rb",
            "lib/epsa_stream.rb",
            "lib/archive_mount.rb"
        )
    }
}
