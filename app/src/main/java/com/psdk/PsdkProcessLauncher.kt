package com.psdk

import com.psdk.PsdkProcess.InputData
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.lang.Exception
import java.lang.RuntimeException

typealias CompletionTask = (Int) -> () -> Unit;

abstract class PsdkProcessLauncher(private val applicationPath: String?) {
    private var mainThread: Thread? = null
    private var loggingThread: Thread? = null
    fun runAsync(process: PsdkProcess, processData: InputData, onComplete: CompletionTask) {
        val fifoFilename = applicationPath + "/" + FIFO_NAME
        val fifo = File(fifoFilename)
        if (fifo.exists()) {
            fifo.delete()
        }
        mainThread = Thread {
            val returnCode = process.run(fifoFilename, processData)
            onComplete.invoke(returnCode)
        }
        loggingThread = Thread {
            try {
                while (!fifo.exists()) {
                }
                val `in` = BufferedReader(FileReader(fifo))
                var msg: String?
                while (`in`.readLine().also { msg = it } != null) {
                    accept(msg)
                }
                `in`.close()
            } catch (e: IOException) {
                e.printStackTrace()
                onLogError(e)
                throw RuntimeException(e)
            }
        }
        mainThread!!.start()
        loggingThread!!.start()
    }

    val isAlive: Boolean
        get() = mainThread != null && mainThread!!.isAlive

    fun killCurrentProcess() {
        Process.sendSignal(Process.myPid(), Process.SIGNAL_KILL)
    }

    @Throws(InterruptedException::class)
    fun join() {
        if (mainThread != null) {
            mainThread!!.join()
            mainThread = null
        }
        if (loggingThread != null) {
            loggingThread!!.join()
            loggingThread = null
        }
    }

    protected abstract fun accept(lineMessage: String?)
    protected open fun onLogError(e: Exception) {}

    companion object {
        private const val FIFO_NAME = "psdk_fifo"
    }
}