package com.psdk.ruby.vm

import com.psdk.ruby.vm.RubyScript.ScriptCurrentLocation
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.lang.RuntimeException

abstract class RubyVM(private val applicationPath: String?, private val main: RubyScript):
    LogListener {
    private var mainThread: Thread? = null
    private var logReaderThread: Thread? = null

    private var fifoCommands: File? = null
    private var fifoReturnFile: File? = null
    private var fifoLogs: File? = null

    fun startVM(location: ScriptCurrentLocation) {
        val fifoLogsFilename = "$applicationPath/$FIFO_LOGS_NAME"
        fifoLogs = File(fifoLogsFilename)
        if (fifoLogs!!.exists()) {
            fifoLogs!!.delete()
        }
        val fifoCommandsFilename = "${applicationPath}/${FIFO_COMMANDS_NAME}_in"
        val fifoCommandsFile = File(fifoCommandsFilename)

        val fifoReturnFilename = "${applicationPath}/${FIFO_COMMANDS_NAME}_out"
        fifoReturnFile = File(fifoReturnFilename)
        if (fifoReturnFile!!.exists()) {
            fifoReturnFile!!.delete()
        }

        fifoCommands = fifoCommandsFile
        if (fifoCommands!!.exists()) {
            fifoCommands!!.delete()
        }

        mainThread = Thread {
            exec(main.getContent(),
                fifoLogsFilename,
                fifoCommandsFilename,
                fifoReturnFilename,
                location.rubyBaseDirectory,
                location.executionLocation,
                location.nativeLibsLocation,
                location.archiveLocation)
        }
        logReaderThread = Thread {
            try {
                while (!fifoLogs!!.exists()) {
                }
                BufferedReader(FileReader(fifoLogs)).use { reader ->
                    var msg: String?
                    do {
                        msg = reader.readLine()
                        if (msg != null) {
                            accept(msg)
                        }
                    } while (msg != null)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                onLogError(e)
                throw RuntimeException(e)
            }
        }
        mainThread!!.start()
        logReaderThread!!.start()

        while(!fifoReturnFile!!.exists() || !fifoCommands!!.exists()) {}
    }

    fun enqueue(script: RubyScript, onComplete: CompletionTask) {
        val scriptThread = Thread {
            try {
                var resultLine: String?;
                BufferedReader(FileReader(fifoReturnFile!!)).use { reader ->
                    // First line sent => script EOF reached, we quit
                    do {
                        resultLine = reader.readLine()
                    } while (resultLine == null);
                }
                onComplete.invoke(if ("0" == resultLine?.trim()) 0 else 1)
            } catch (e: IOException) {
                e.printStackTrace()
                onLogError(e)
                onComplete.invoke(1)
            }
        }

        scriptThread.start()
        FileWriter(fifoCommands).use { writer ->
            writer.append(script.getContent())
        }
    }

    fun update(executionLocation: String, archiveLocation: String) {
        updateVmLocation(executionLocation, archiveLocation)
    }

    companion object {
        private external fun exec(scriptContent: String, fifoLogs: String, fifoCommands: String, fifoReturn: String, rubyBaseDirectory: String?, executionLocation: String?, nativeLibsDirLocation: String?, additionalParam: String?): Int
        private external fun updateVmLocation(executionLocation: String, archiveLocation: String): Int
        private const val FIFO_LOGS_NAME = "psdk_fifo_logs"
        private const val FIFO_COMMANDS_NAME = "psdk_fifo_commands"
    }
}