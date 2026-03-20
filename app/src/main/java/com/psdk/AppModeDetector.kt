package com.psdk

import android.content.Context

object AppModeDetector {
    enum class AppMode {
        SUPER_USER,
        CLASSIC_USER
    }

    fun detect(context: Context): AppMode {
        val epsaFile = findEpsaInAssets(context)
        return if (epsaFile != null) AppMode.CLASSIC_USER else AppMode.SUPER_USER
    }

    fun findEpsaInAssets(context: Context): String? {
        return try {
            val files = context.assets.list("") ?: return null
            files.firstOrNull { it.endsWith(".epsa") }
        } catch (e: Exception) {
            null
        }
    }
}
