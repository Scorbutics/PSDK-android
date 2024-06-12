package com.psdk.ruby.vm

import android.content.*
import android.content.res.AssetManager
import java.io.*
import java.lang.StringBuilder

class RubyScript internal constructor(assetManager: AssetManager, assetScriptName: String?) {
    class ScriptCurrentLocation(val rubyBaseDirectory: String?, val executionLocation: String?, val nativeLibsLocation: String?, val archiveLocation: String?)

    private val scriptContent: String

    init {
        scriptContent = readFromAssets(assetManager, assetScriptName)
    }

    fun getContent(): String {
        return scriptContent
    }

    companion object {
        @Throws(IOException::class)
        fun readFromAssets(assets: AssetManager, assetScriptName: String?): String {
            val asset = BufferedReader(InputStreamReader(assets.open(assetScriptName!!)))
            val scriptContent = StringBuilder()
            var s: String?
            while (asset.readLine().also { s = it } != null) {
                scriptContent.append(s).append("\n")
            }
            return scriptContent.toString()
        }
    }
}