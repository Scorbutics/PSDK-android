package com.psdk

import java.io.*
import java.lang.Exception
import java.lang.RuntimeException
import java.util.ArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtility {
    fun zip(sourceDirectoryPath: String?, destinationFilePath: String?) {
        val f = File(destinationFilePath)
        val out: ZipOutputStream
        out = try {
            ZipOutputStream(FileOutputStream(f))
        } catch (fnfe: FileNotFoundException) {
            throw ZipException(fnfe)
        }
        val allFiles = getFileList(File(sourceDirectoryPath))
        try {
            for (file in allFiles) {
                var relativeFilePath = file.split(sourceDirectoryPath!!).toTypedArray()[1]
                if (relativeFilePath.startsWith("/")) {
                    relativeFilePath = relativeFilePath.substring(1)
                }
                out.putNextEntry(ZipEntry(relativeFilePath))
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        out.write(buffer, 0, length)
                    }
                }
                out.closeEntry()
            }
            out.close()
        } catch (ex: IOException) {
            throw ZipException(ex)
        }
    }

    @Throws(IOException::class, IllegalAccessException::class)
    fun addFilesToExistingZip(zipFileInput: InputStream?, zipOutName: String?, ins: Array<InputStream>, names: Array<String?>) {
        if (ins.size != names.size) {
            throw IllegalAccessException("There must be as many input streams as names")
        }
        val zin = ZipInputStream(zipFileInput)
        val out = ZipOutputStream(FileOutputStream(zipOutName))
        val buf = ByteArray(1024)
        var entry = zin.nextEntry
        while (entry != null) {
            out.putNextEntry(ZipEntry(entry.name))
            var len: Int
            while (zin.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            out.closeEntry()
            entry = zin.nextEntry
        }
        zin.close()

        // Compress the files
        for (i in ins.indices) {
            val `in` = ins[i]
            // Add ZIP entry to output stream.
            out.putNextEntry(ZipEntry(names[i]))
            // Transfer bytes from the file to the ZIP file
            var len: Int
            while (`in`.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            // Complete the entry
            out.closeEntry()
            `in`.close()
        }
        // Complete the ZIP file
        out.close()
    }

    private fun getFileList(directory: File): List<String> {
        val filenames: MutableList<String> = ArrayList()
        addFileList(filenames, directory)
        return filenames
    }

    private fun addFileList(filenames: MutableList<String>, directory: File) {
        val files = directory.listFiles()
        if (files != null && files.size > 0) {
            for (file in files) {
                if (file.isFile) {
                    filenames.add(file.absolutePath)
                } else {
                    addFileList(filenames, file)
                }
            }
        }
    }

    internal class ZipException(e: Exception?) : RuntimeException(e)
}