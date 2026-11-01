package com.psdk.logs

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLogReaderTest {
    private val dir = kotlin.io.path.createTempDirectory().toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun readsTheEndOfAFile() {
        val file = File(dir, "log").apply { writeText("0123456789") }
        assertEquals(LogText("0123456789", false, FileLogReader.read(file.path, 100)!!.stamp), FileLogReader.read(file.path, 100))
        val end = FileLogReader.read(file.path, 4)!!
        assertEquals("6789", end.text)
        assertTrue(end.truncated)
    }

    @Test
    fun aGrowingFileChangesItsStamp() {
        val file = File(dir, "log").apply { writeText("a") }
        val before = FileLogReader.read(file.path, 100)!!.stamp
        file.appendText("b")
        assertNotEquals(before, FileLogReader.read(file.path, 100)!!.stamp)
    }

    @Test
    fun noFileIsNoLog() {
        assertNull(FileLogReader.read(File(dir, "missing").path, 100))
        assertNull(FileLogReader.read(dir.path, 100))
    }

    @Test
    fun aCharacterCutAtTheStartIsReplaced() {
        val file = File(dir, "log").apply { writeText("éa") }
        val end = FileLogReader.read(file.path, 2)!!
        assertFalse(end.text.contains('é'))
        assertTrue(end.text.endsWith("a"))
    }
}
