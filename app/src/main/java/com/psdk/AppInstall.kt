package com.psdk

import android.Manifest
import android.os.Build
import android.os.Environment
import android.app.Activity
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.provider.Settings
import com.psdk.zip.UnzipUtility
import java.io.*
import java.lang.Exception
import java.util.ArrayList
import java.util.Arrays
import kotlin.streams.toList

object AppInstall {
    private const val INSTALL_NEEDED = "APP_INSTALL_NEEDED"
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

    fun unpackToStartGameIfRelease(activity: Activity): Boolean {
        try {
            val gameDir = "Release"
            val gameFiles = recursivelyListAllFiles(activity.assets, gameDir) ?: return false
            if (gameFiles.isEmpty()) {
                return false
            }
            val dataDir = File(activity.application.applicationInfo.dataDir + "/" + gameDir)
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

    private fun recursivelyListAllFiles(assetManager: AssetManager, dir: String): List<String> {
        val gameFiles = assetManager.list(dir)
        if (gameFiles.isNullOrEmpty()) {
            return listOf(dir)
        }
        return Arrays.stream(gameFiles).flatMap { file ->
            recursivelyListAllFiles(assetManager, "$dir/$file").stream()
        }.toList()
    }

    fun unpackExtraAssetsIfNeeded(activity: Activity, preferences: SharedPreferences?): String? {
        if (preferences != null && preferences.getBoolean(INSTALL_NEEDED, true)) {
            val internalWriteablePath = activity.filesDir.absolutePath
            val rubyArchive = activity.assets.open("ruby-stdlib.zip")
            UnzipUtility.unzip(rubyArchive, internalWriteablePath)
            copyAsset(activity.assets, internalWriteablePath, "ruby_physfs_patch.rb")
            val edit = preferences.edit()
            edit.putBoolean(INSTALL_NEEDED, false)
            edit.apply()
        }
        return null
    }

}