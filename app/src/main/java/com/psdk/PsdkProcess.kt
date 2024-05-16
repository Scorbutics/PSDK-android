package com.psdk

import android.content.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.StringBuilder

class PsdkProcess internal constructor(context: Context, assetScriptName: String?) {
    class InputData(val internalWriteablePath: String?, val executionLocation: String?, val archiveLocation: String?)

    private val scriptContent: String

    init {
        scriptContent = readFromAssets(context, assetScriptName)
    }

    fun run(fifo: String, processData: InputData): Int {
        return exec(scriptContent, fifo, processData.internalWriteablePath, processData.executionLocation, processData.archiveLocation)
    }

    companion object {
        private external fun exec(scriptContent: String, fifo: String, internalWriteablePath: String?, executionLocation: String?, additionalParam: String?): Int
        @Throws(IOException::class)
        fun readFromAssets(context: Context, assetScriptName: String?): String {
            val asset = BufferedReader(InputStreamReader(context.assets.open(assetScriptName!!)))
            val scriptContent = StringBuilder()
            var s: String?
            while (asset.readLine().also { s = it } != null) {
                scriptContent.append(s).append("\n")
            }
            return scriptContent.toString()
        }
    }
}