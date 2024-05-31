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
        if (preferences != null) {
            //if (preferences.getBoolean(INSTALL_NEEDED, true)) {
                val internalWriteablePath = activity.filesDir.absolutePath
                val rubyArchive = activity.assets.open("ruby-stdlib.zip")
                UnzipUtility.unzip(rubyArchive, internalWriteablePath)
                copyAsset(activity.assets, internalWriteablePath, "ruby_physfs_patch.rb")
                val edit = preferences!!.edit()
                edit.putBoolean(INSTALL_NEEDED, false)
                edit.apply()
            //}
        }
        return null
    }

    fun requestPermissionsIfNeeded(activity: Activity, requestCode: Int, acceptAllRequestCode: Int): Boolean {
        val quickPermissions: MutableList<String> = ArrayList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            quickPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                quickPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        var allAccess = requestPermissionsNeeded(activity, quickPermissions.toTypedArray(), requestCode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestActivityPermissions(activity, acceptAllRequestCode)
                allAccess = false
            }
        }
        return allAccess
    }

    private fun checkNotGrantedPermissions(activity: Activity, permissions: Array<String>): List<String> {
        val notGrantedPermissions: MutableList<String> = ArrayList()
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                notGrantedPermissions.add(permission)
            }
        }
        return notGrantedPermissions
    }

    private fun requestPermissionsNeeded(activity: Activity, permissions: Array<String>, requestCode: Int): Boolean {
        val notGrantedPermissions = checkNotGrantedPermissions(activity, permissions)
        if (!notGrantedPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(activity, notGrantedPermissions.toTypedArray(), requestCode)
            return false
        }
        return true
    }

    fun requestActivityPermissions(activity: Activity, requestCode: Int) {
        try {
            val uri = Uri.parse("package:" + BuildConfig.APPLICATION_ID)
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            activity.startActivityForResult(intent, requestCode)
        } catch (ex: Exception) {
            val intent = Intent()
            intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            activity.startActivityForResult(intent, requestCode)
        }
    }
}