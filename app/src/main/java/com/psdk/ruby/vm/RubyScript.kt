package com.psdk.ruby.vm

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader

class RubyScript(assetManager: AssetManager, assetScriptName: String) {
    private val scriptContent: String = readFromAssets(assetManager, assetScriptName)

    fun getContent(): String = scriptContent

    companion object {
        fun readFromAssets(assets: AssetManager, assetScriptName: String): String {
            return BufferedReader(InputStreamReader(assets.open(assetScriptName))).use { reader ->
                reader.readText()
            }
        }
    }
}
