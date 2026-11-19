package com.psdk.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import java.io.File

/**
 * A small APK's game splash: the game's title and version, icon and font,
 * which the exporter packs under [ASSET_DIR] (exporter/lib/splash_assets.rb)
 * as extract_loader_assets.rb writes them after the download. The splash
 * shows the game from the first launch, its icon rolling while it downloads.
 */
object GameSplash {
    private const val TAG = "GameSplash"
    const val ASSET_DIR = "loader_assets_game"
    private const val ICON = "game.png"
    private const val ICON_COLOR = "icon_color.json"

    /**
     * The splash's assets in a folder for LoaderState.FromExtracted, with
     * the icon's color (extract_loader_assets.rb's detect_icon_color, which
     * needs the game's runtime), or null when the APK has none.
     */
    fun install(context: Context): File? {
        val names = runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        if (names.isEmpty()) return null
        val dir = File(context.filesDir, ASSET_DIR)
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            for (name in names) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            iconColor(File(dir, ICON))?.let { (r, g, b) ->
                File(dir, ICON_COLOR).writeText("""{"r":$r,"g":$g,"b":$b}""")
            }
            dir
        } catch (e: Exception) {
            Log.w(TAG, "game splash not installed", e)
            null
        }
    }

    /**
     * Per-channel median of the icon's opaque pixels, on a grid of at most
     * ~64 samples a side: extract_loader_assets.rb's detect_icon_color.
     * Null without an opaque pixel (the splash keeps its default colour).
     */
    internal fun iconColor(icon: File): Triple<Int, Int, Int>? {
        val bitmap = BitmapFactory.decodeFile(icon.path) ?: return null
        try {
            val step = maxOf(maxOf(bitmap.width, bitmap.height) / 64, 1)
            val rs = ArrayList<Int>()
            val gs = ArrayList<Int>()
            val bs = ArrayList<Int>()
            for (y in 0 until bitmap.height step step) {
                for (x in 0 until bitmap.width step step) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) < 128) continue
                    rs += Color.red(pixel)
                    gs += Color.green(pixel)
                    bs += Color.blue(pixel)
                }
            }
            if (rs.isEmpty()) return null
            rs.sort(); gs.sort(); bs.sort()
            val mid = rs.size / 2
            return Triple(rs[mid], gs[mid], bs[mid])
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A game version as the splash shows it (loader/util.rb's
     * format_version): PSDK's integer as its bytes, leading zeros dropped
     * (536 → "2.24"); any other version as is.
     */
    fun displayVersion(version: String): String {
        val number = version.trim().toLongOrNull() ?: return version
        if (number < 0 || number > 0xFFFFFFFFL) return version
        val bytes = listOf(24, 16, 8, 0).map { ((number shr it) and 0xFF).toInt() }
        return bytes.dropWhile { it == 0 }.ifEmpty { listOf(0) }.joinToString(".")
    }
}
