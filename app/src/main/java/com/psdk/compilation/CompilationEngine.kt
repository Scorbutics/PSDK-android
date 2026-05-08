package com.psdk.compilation

import android.content.Context
import android.widget.Toast
import com.psdk.CompileStepData
import com.psdk.CompileStepLogs
import com.psdk.CompileStepStatus
import com.psdk.ruby.vm.ArchiveKeys
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompilationEngine(
    private val context: Context,
    private val executionLocation: String,
    private val archive: ArchiveKeys,
    private val callback: CompilationCallback
) {
    interface CompilationCallback {
        fun onStepStarted(stepIndex: Int, stepName: String)
        fun onStepCompleted(stepIndex: Int, success: Boolean)
        fun onLogMessage(stepIndex: Int, message: String)
        fun onCompilationFinished(success: Boolean, logFile: File)
    }

    private val allStepLogs = mutableListOf<CompileStepLogs>()
    private var currentLogIndex = 0
    private var interpreter: PsdkInterpreter? = null

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
        val checkEngineLogs = CompileStepLogs(CompileStepData("Check engine", CompileStepStatus.IN_PROGRESS), StringBuilder())
        val backupSavesLogs = CompileStepLogs(CompileStepData("Backing up previous Release", CompileStepStatus.READY), StringBuilder())
        val compilationLogs = CompileStepLogs(CompileStepData("Compilation", CompileStepStatus.READY), StringBuilder())
        val copySavesLogs = CompileStepLogs(CompileStepData("Copying saves", CompileStepStatus.READY), StringBuilder())
        allStepLogs.addAll(listOf(checkEngineLogs, backupSavesLogs, compilationLogs, copySavesLogs))

        currentLogIndex = 0
        callback.onStepStarted(0, checkEngineLogs.step.title)

        interpreter = PsdkInterpreter.create(
            context = context,
            listener = object : LogListener {
                override fun onLogMessage(logMessage: LogMessage) {
                    val msg = logMessage.message
                    logExecutor.execute {
                        val idx = currentLogIndex
                        allStepLogs[idx].logs.appendLine(msg)
                        callback.onLogMessage(idx, msg)
                    }
                }
            },
            onError = { e ->
                callback.onLogMessage(currentLogIndex, "Error: ${e.localizedMessage}")
            }
        )

        val location = ScriptLocation(executionLocation, archive)

        val finishWithFailure: (Int) -> Unit = { failedStepIndex ->
            interpreter?.close()
            interpreter = null
            restoreReleaseBackup(failedStepIndex)
            val logFile = saveLogsToFile()
            callback.onCompilationFinished(false, logFile)
        }

        val onCompleteCopySaves: CompletionTask = { returnCode: Int ->
            if (returnCode != 0) {
                allStepLogs[3].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(3, false)
                finishWithFailure(3)
            } else {
                allStepLogs[3].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(3, true)
                interpreter?.close()
                interpreter = null
                val logFile = saveLogsToFile()
                callback.onCompilationFinished(true, logFile)
            }
        }

        val onCompleteCompilation: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[2].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(2, false)
                finishWithFailure(2)
            } else {
                allStepLogs[2].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(2, true)
                setActiveStep(3)
                allStepLogs[3].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(3, allStepLogs[3].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, COPY_SAVES_SCRIPT, ARCHIVE_PRELUDES), location, onCompleteCopySaves)
            }
        }

        val onCompleteBackup: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[1].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(1, false)
                finishWithFailure(1)
            } else {
                allStepLogs[1].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(1, true)
                setActiveStep(2)
                allStepLogs[2].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(2, allStepLogs[2].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, RUBY_COMPILE_SCRIPT, ARCHIVE_PRELUDES), location, onCompleteCompilation)
            }
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[0].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(0, false)
                interpreter?.close()
                interpreter = null
                val logFile = saveLogsToFile()
                callback.onCompilationFinished(false, logFile)
            } else {
                allStepLogs[0].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(0, true)
                setActiveStep(1)
                allStepLogs[1].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(1, allStepLogs[1].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, BACKUP_SAVES_SCRIPT), location, onCompleteBackup)
            }
        }

        try {
            interpreter!!.enqueue(RubyScript(context.assets, CHECK_ENGINE_SCRIPT), location, onCompleteCheck)
        } catch (ex: Exception) {
            callback.onLogMessage(0, "Fatal error: ${ex.localizedMessage}")
            callback.onCompilationFinished(false, saveLogsToFile())
        }
    }

    private fun restoreReleaseBackup(failedStepIndex: Int) {
        val releaseDir = File(executionLocation, "Release")
        val backupDir = File(executionLocation, "ReleaseBackup")
        if (!backupDir.exists()) {
            callback.onLogMessage(failedStepIndex, "No Release backup to restore.")
            return
        }
        try {
            if (releaseDir.exists() && !releaseDir.deleteRecursively()) {
                callback.onLogMessage(failedStepIndex, "Warning: failed to fully clean Release folder before restore.")
            }
            if (backupDir.renameTo(releaseDir)) {
                callback.onLogMessage(failedStepIndex, "Restored previous Release folder from backup.")
            } else {
                callback.onLogMessage(failedStepIndex, "Error: failed to rename ReleaseBackup back to Release.")
            }
        } catch (ex: Exception) {
            callback.onLogMessage(failedStepIndex, "Error while restoring backup: ${ex.localizedMessage}")
        }
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

    private fun saveLogsToFile(): File {
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
        return logFile
    }

    companion object {
        private const val BACKUP_SAVES_SCRIPT = "backup_saves.rb"
        private const val COPY_SAVES_SCRIPT = "copy_saves.rb"
        private const val RUBY_COMPILE_SCRIPT = "compile.rb"
        private const val CHECK_ENGINE_SCRIPT = "check_engine.rb"
        const val COMPILATION_LOG_FILE = "last_compilation.log"

        // Ruby preludes prepended to scripts that mount the archive. They
        // define EpsaFormat, EpsaStream, and ArchiveMount so compile.rb /
        // copy_saves.rb can call ArchiveMount.mount! without a load path.
        // Order matters: epsa_stream depends on epsa_format, and
        // archive_mount depends on epsa_stream.
        private val ARCHIVE_PRELUDES = listOf(
            "lib/epsa_format.rb",
            "lib/epsa_stream.rb",
            "lib/archive_mount.rb"
        )
    }
}
