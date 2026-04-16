package com.psdk.compilation

import android.content.Context
import android.widget.Toast
import com.psdk.CompileStepData
import com.psdk.CompileStepLogs
import com.psdk.CompileStepStatus
import com.psdk.ruby.vm.CompletionTask
import com.psdk.ruby.vm.PsdkInterpreter
import com.psdk.ruby.vm.RubyScript
import com.psdk.ruby.vm.ScriptLocation
import com.scorbutics.rubyvm.LogListener
import com.scorbutics.rubyvm.LogMessage
import java.io.File

class CompilationEngine(
    private val context: Context,
    private val executionLocation: String,
    private val archiveLocation: String,
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

    fun start() {
        val backupSavesLogs = CompileStepLogs(CompileStepData("Backing up saves", CompileStepStatus.IN_PROGRESS), StringBuilder())
        val checkEngineLogs = CompileStepLogs(CompileStepData("Check engine", CompileStepStatus.READY), StringBuilder())
        val compilationLogs = CompileStepLogs(CompileStepData("Compilation", CompileStepStatus.READY), StringBuilder())
        val copySavesLogs = CompileStepLogs(CompileStepData("Copying saves", CompileStepStatus.READY), StringBuilder())
        allStepLogs.addAll(listOf(backupSavesLogs, checkEngineLogs, compilationLogs, copySavesLogs))

        currentLogIndex = 0
        callback.onStepStarted(0, backupSavesLogs.step.title)

        interpreter = PsdkInterpreter.create(
            context = context,
            listener = object : LogListener {
                override fun onLogMessage(logMessage: LogMessage) {
                    allStepLogs[currentLogIndex].logs.appendLine(logMessage.message)
                    callback.onLogMessage(currentLogIndex, logMessage.message)
                }
            },
            onError = { e ->
                callback.onLogMessage(currentLogIndex, "Error: ${e.localizedMessage}")
            }
        )

        val location = ScriptLocation(executionLocation, archiveLocation)

        val onCompleteCopySaves: CompletionTask = { returnCode: Int ->
            allStepLogs[3].step.status = if (returnCode == 0) CompileStepStatus.SUCCESS else CompileStepStatus.ERROR
            callback.onStepCompleted(3, returnCode == 0)
            interpreter?.close()
            interpreter = null
            val logFile = saveLogsToFile()
            callback.onCompilationFinished(returnCode == 0, logFile)
        }

        val onCompleteCompilation: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[2].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(2, false)
                interpreter?.close()
                interpreter = null
                val logFile = saveLogsToFile()
                callback.onCompilationFinished(false, logFile)
            } else {
                allStepLogs[2].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(2, true)
                currentLogIndex = 3
                allStepLogs[3].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(3, allStepLogs[3].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, COPY_SAVES_SCRIPT), location, onCompleteCopySaves)
            }
        }

        val onCompleteCheck: CompletionTask = { returnCode: Int ->
            Thread.sleep(1000)
            if (returnCode != 0) {
                allStepLogs[1].step.status = CompileStepStatus.ERROR
                callback.onStepCompleted(1, false)
                interpreter?.close()
                interpreter = null
                val logFile = saveLogsToFile()
                callback.onCompilationFinished(false, logFile)
            } else {
                allStepLogs[1].step.status = CompileStepStatus.SUCCESS
                callback.onStepCompleted(1, true)
                currentLogIndex = 2
                allStepLogs[2].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(2, allStepLogs[2].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, RUBY_COMPILE_SCRIPT), location, onCompleteCompilation)
            }
        }

        val onCompleteBackup: CompletionTask = { returnCode: Int ->
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
                currentLogIndex = 1
                allStepLogs[1].step.status = CompileStepStatus.IN_PROGRESS
                callback.onStepStarted(1, allStepLogs[1].step.title)
                interpreter!!.enqueue(RubyScript(context.assets, CHECK_ENGINE_SCRIPT), location, onCompleteCheck)
            }
        }

        try {
            interpreter!!.enqueue(RubyScript(context.assets, BACKUP_SAVES_SCRIPT), location, onCompleteBackup)
        } catch (ex: Exception) {
            callback.onLogMessage(0, "Fatal error: ${ex.localizedMessage}")
            callback.onCompilationFinished(false, saveLogsToFile())
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
    }
}
