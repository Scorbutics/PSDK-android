package com.psdk

import android.content.Context

object AppModeDetector {
    enum class AppMode {
        SUPER_USER,
        CLASSIC_USER
    }

    /**
     * Filename `export_android.rb` writes into `assets/` when bundling a
     * project into a classic-mode APK (see plugins/export_android.rb:431).
     * The probe-by-name fallback in [findEpsaInAssets] depends on this
     * being a stable convention; rename here AND in the export plugin if
     * you ever change it.
     */
    const val EXPORT_ANDROID_EPSA_NAME = "game.epsa"

    fun detect(context: Context): AppMode {
        val epsaFile = findEpsaInAssets(context)
        return if (epsaFile != null) AppMode.CLASSIC_USER else AppMode.SUPER_USER
    }

    /**
     * Look for a bundled `.epsa` in APK assets. Returns the asset-relative
     * filename (e.g. "game.epsa") or null if none is bundled.
     *
     * Two-step on purpose:
     *
     * 1. `AssetManager.list("")` — works for gradle-built APKs where the
     *    AAPT asset index correctly indexes every entry. Cheap, returns
     *    arbitrary filenames so a manually-renamed archive still flies.
     *
     * 2. Probe [EXPORT_ANDROID_EPSA_NAME] directly via `open()`. Needed
     *    because apktool-rebuilt APKs with very large injected assets
     *    (export_android.rb writes a half-gigabyte .epsa straight into
     *    assets/) can drop the entry from the AAPT index even though
     *    the ZIP entry is intact — `list()` then misses it but `open()`
     *    falls back to walking the ZIP directly and succeeds.
     */
    fun findEpsaInAssets(context: Context): String? {
        runCatching {
            context.assets.list("")?.firstOrNull { it.endsWith(".epsa") }
        }.getOrNull()?.let { return it }

        return runCatching {
            context.assets.open(EXPORT_ANDROID_EPSA_NAME).close()
            EXPORT_ANDROID_EPSA_NAME
        }.getOrNull()
    }
}
