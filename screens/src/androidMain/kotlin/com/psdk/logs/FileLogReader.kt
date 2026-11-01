package com.psdk.logs

import java.io.File
import java.io.RandomAccessFile

/** The end of a log file, decoded as UTF-8 (a character cut at the start is replaced). */
object FileLogReader : LogReader {
    override fun read(path: String, maxBytes: Int): LogText? {
        val file = File(path)
        if (!file.isFile) return null
        val modified = file.lastModified()
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            val bytes = ByteArray((length - start).toInt())
            input.seek(start)
            // The game may cut the file meanwhile (a new session): what is there
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count < 0) break
                read += count
            }
            return LogText(bytes.decodeToString(0, read), truncated = start > 0, stamp = "$length/$modified")
        }
    }
}
