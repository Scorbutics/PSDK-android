package com.psdk.controls

import com.scorbutics.litergss.overlay.PlayerLayout
import java.io.File

/** The player's layout file and its images in the game's folder [dir]. */
class FileControlsStore(private val dir: File) : ControlsStore {
    private val file = File(dir, PlayerLayout.FILE_NAME)

    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(json: String) = replace(file) { it.writeText(json) }

    override fun writeImage(path: String, png: ByteArray) {
        val image = File(dir, path)
        image.parentFile?.mkdirs()
        replace(image) { it.writeBytes(png) }
    }

    override fun deleteImage(path: String) {
        File(dir, path).delete()
    }

    override fun putAside() {
        if (file.exists() && !file.renameTo(File(file.path + ".broken"))) throw java.io.IOException("$file not set aside")
    }

    override fun reset() {
        if (file.exists() && !file.delete()) throw java.io.IOException("$file not deleted")
        File(dir, PlayerLayout.IMAGE_DIR).deleteRecursively()
    }

    /** Written beside, then renamed over: whole or not at all */
    private fun replace(target: File, write: (File) -> Unit) {
        val tmp = File(target.path + ".tmp")
        try {
            write(tmp)
            if (!tmp.renameTo(target)) throw java.io.IOException("$target not written")
        } finally {
            tmp.delete()
        }
    }
}
