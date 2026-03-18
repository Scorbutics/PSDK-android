package com.psdk

import android.app.Activity
import android.content.res.AssetManager
import java.io.*
import java.util.Arrays
import java.util.stream.Collectors

object AppInstall {
    @Throws(IOException::class)
    private fun copyAsset(assetManager: AssetManager, internalWriteablePath: String, filepath: String) {
        assetManager.open(filepath).use { input ->
            val outFile = File(internalWriteablePath, filepath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { out ->
                val buffer = ByteArray(1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }

        }
    }

    fun unpackToStartGameIfRelease(activity: Activity, releaseFolderInAssets: String, releaseLocation: String): Boolean {
        try {
            val gameFiles = recursivelyListAllFiles(activity.assets, releaseFolderInAssets, 0)
            if (gameFiles.isEmpty()) {
                return false
            }
            val dataDir = File(releaseLocation)
            if (!dataDir.exists()) {
                dataDir.mkdir()
            }
            gameFiles.forEach { file ->
                copyAsset(activity.assets, activity.application.applicationInfo.dataDir, file)
            }
            return true
        } catch (exception: IOException) {
            return false
        }
    }

    private fun recursivelyListAllFiles(assetManager: AssetManager, dirOrFile: String, depth: Int): List<String> {
        val gameFiles = assetManager.list(dirOrFile)
        if (gameFiles.isNullOrEmpty()) {
            return if (depth == 0) listOf() else listOf(dirOrFile)
        }
        return Arrays.stream(gameFiles).flatMap { file ->
            recursivelyListAllFiles(assetManager, "$dirOrFile/$file", depth + 1).stream()
        }.collect(Collectors.toList())
    }
}
