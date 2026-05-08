package com.psdk.ruby.vm

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ruby script loaded from assets, optionally prefixed with one or more
 * "prelude" assets concatenated in order. Preludes define shared Ruby
 * classes/modules (e.g. EpsaStream, ArchiveMount) that the main script
 * relies on; concatenating them at build-content time avoids the need to
 * expose a load path / `require` mechanism for VM-bundled libs.
 */
class RubyScript(
    assetManager: AssetManager,
    assetScriptName: String,
    preludes: List<String> = emptyList()
) {
    private val scriptContent: String = run {
        val sb = StringBuilder()
        for (prelude in preludes) {
            sb.append(readFromAssets(assetManager, prelude))
            sb.append('\n')
        }
        sb.append(readFromAssets(assetManager, assetScriptName))
        sb.toString()
    }

    fun getContent(): String = scriptContent

    companion object {
        fun readFromAssets(assets: AssetManager, assetScriptName: String): String {
            return BufferedReader(InputStreamReader(assets.open(assetScriptName))).use { reader ->
                reader.readText()
            }
        }
    }
}
