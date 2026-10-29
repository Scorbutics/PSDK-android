package com.psdk.ui

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Single source of truth for the splash loader's on-disk layout.
 *
 * The loader interpreter (Ruby's loader.rb) chdir's to [executionLocation]
 * and resolves its asset filenames relative to that directory — see
 * loader.rb's `DEFAULT_FONT_PATH`, `ICON_PATH`, etc.
 *
 * Cut 2 pins the loader to a process-global location (the app's private
 * data dir) so the splash is the same across the hub and the wizard, and
 * the wizard can swap assets in/out at runtime without having to restart
 * with a per-project chdir.
 */
object LoaderPaths {
    private const val LOADER_ASSETS_DIR = "loader_assets"
    private const val LOADER_STATUS_FILE_NAME = ".loader_status"

    /** Bundled default asset dir under `assets/` inside the APK. */
    const val DEFAULT_ASSETS_APK_DIR = "loader_assets_default"

    /** loader.rb's chdir target — must match [statusFile]'s parent. */
    fun executionLocation(context: Context): String = context.applicationInfo.dataDir

    /**
     * True iff the project's archive is a compiled release (no Ruby sources
     * to run in Debug): extract_loader_assets.rb marks it at import, in the
     * project's own loader_assets.
     */
    fun isCompiledRelease(projectDir: String): Boolean =
        File(projectDir, "$LOADER_ASSETS_DIR/$COMPILED_RELEASE_MARKER").exists()

    private const val COMPILED_RELEASE_MARKER = "compiled_release"

    /** Where loader.rb reads game.png / infos_config.json / icon_color.json from. */
    fun assetsDir(context: Context): File = File(executionLocation(context), LOADER_ASSETS_DIR)

    /** The polled status file; CompileProgressFragment writes here. */
    fun statusFile(context: Context): File = File(executionLocation(context), LOADER_STATUS_FILE_NAME)

    /**
     * Wipe [assetsDir] and reinstall the APK-bundled defaults. Called at
     * activity launch AND every time the splash transitions back to the
     * default state — a prior session may have copied a project's
     * loader_assets into the same dir via [replaceAssetsWith], and the
     * defaults wouldn't be restored on their own.
     *
     * Returns true if every bundled file was written successfully.
     */
    fun resetToDefaults(context: Context): Boolean {
        val target = assetsDir(context)
        target.deleteRecursively()
        if (!target.mkdirs()) {
            Log.w(TAG, "could not create $target")
            return false
        }
        var ok = true
        val assets = context.assets
        val names = try {
            assets.list(DEFAULT_ASSETS_APK_DIR).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "could not list APK $DEFAULT_ASSETS_APK_DIR", e)
            ok = false
            emptyArray()
        }
        for (name in names) {
            val dest = File(target, name)
            try {
                assets.open("$DEFAULT_ASSETS_APK_DIR/$name").use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "default asset copy failed: $name", e)
                ok = false
            }
        }
        return ok
    }

    /**
     * Replace the loader's assets dir contents with the files from
     * [source]. Used by the wizard after archive validation has produced a
     * project-specific `loader_assets/` so the next loader (re)start picks
     * up the project's title / icon / colour.
     *
     * Only touches files that exist in [source]; files in the target dir
     * that have no counterpart in the source survive (e.g. the default
     * font we don't ship yet — irrelevant today, defensive against future
     * additions).
     */
    fun replaceAssetsWith(context: Context, source: File) {
        if (!source.exists() || !source.isDirectory) return
        val target = assetsDir(context)
        target.mkdirs()
        source.listFiles()?.forEach { src ->
            if (!src.isFile) return@forEach
            try {
                src.copyTo(File(target, src.name), overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "asset swap failed: ${src.name}", e)
            }
        }
    }

    /** Read by the loader (Loader::Assets::PREPARING_PATH). */
    private const val PREPARING_FILE_NAME = "preparing.txt"

    /** Show [message] instead of the hub/project splash (see LoaderState.Preparing). */
    fun writePreparingMessage(context: Context, message: String) {
        try {
            File(assetsDir(context), PREPARING_FILE_NAME).writeText(message)
        } catch (e: Exception) {
            Log.w(TAG, "could not write preparing message", e)
        }
    }

    /**
     * Back to the normal splash. Needed for every other state: replacing
     * assets and the archive prelude keep files they don't know about.
     */
    fun clearPreparingMessage(context: Context) {
        File(assetsDir(context), PREPARING_FILE_NAME).delete()
    }

    private const val TAG = "LoaderPaths"
}
