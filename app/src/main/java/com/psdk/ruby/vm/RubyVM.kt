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
            exec(main.getContent(), fifoLogsFilename, fifoCommandsFilename, fifoReturnFilename, location.internalWriteablePath, location.executionLocation, location.nativeLibsLocation, location.archiveLocation)
        }
        logReaderThread = Thread {
            try {
                while (!fifoLogs!!.exists()) {
                }
                val `in` = BufferedReader(FileReader(fifoLogs))
                var msg: String?
                do {
                    msg = `in`.readLine()
                    if (msg != null) {
                        accept(msg)
                    }
                } while (msg != null)
                `in`.close()
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
                val `in` = BufferedReader(FileReader(fifoReturnFile!!))
                // First line sent => script EOF reached, we quit
                var resultLine: String? = null;
                do {
                   resultLine = `in`.readLine()
                } while (resultLine == null);
                `in`.close()
                onComplete.invoke(if ("0".equals(resultLine.trim())) 0 else 1)
            } catch (e: IOException) {
                e.printStackTrace()
                onLogError(e)
                onComplete.invoke(1)
            }
        }

        scriptThread.start()
        val writer = FileWriter(fifoCommands)
        writer.append(script.getContent())
        writer.close()
    }

    companion object {
        private external fun exec(scriptContent: String, fifoLogs: String, fifoCommands: String, fifoReturn: String, internalWriteablePath: String?, executionLocation: String?, nativeLibsDirLocation: String?, additionalParam: String?): Int
        private const val FIFO_LOGS_NAME = "psdk_fifo_logs"
        private const val FIFO_COMMANDS_NAME = "psdk_fifo_commands"
    }
}