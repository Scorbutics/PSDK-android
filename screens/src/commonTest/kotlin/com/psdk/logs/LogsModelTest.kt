package com.psdk.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogsModelTest {
    /** Files by path: their text, read count */
    private class Files : LogReader {
        val texts = mutableMapOf<String, String>()
        var failing: String? = null

        override fun read(path: String, maxBytes: Int): LogText? {
            if (path == failing) throw IllegalStateException("denied")
            val text = texts[path] ?: return null
            return LogText(text.takeLast(maxBytes), text.length > maxBytes, text)
        }
    }

    private val files = listOf(LogFile("Output", "out"), LogFile("Errors", "err"))

    @Test
    fun aLogAppearsAndGrows() {
        val disk = Files()
        val model = LogsModel(files, disk)
        assertFalse(model.loaded)
        model.refresh()
        assertTrue(model.loaded)
        assertEquals(listOf(null, null), model.texts)
        disk.texts["out"] = "line 1"
        model.refresh()
        assertEquals("line 1", model.texts[0]?.text)
        disk.texts["out"] = "line 1\nline 2"
        model.refresh()
        assertEquals("line 1\nline 2", model.texts[0]?.text)
    }

    @Test
    fun anUnchangedLogKeepsItsText() {
        val disk = Files().apply { texts["out"] = "same" }
        val model = LogsModel(files, disk)
        model.refresh()
        val first = model.texts[0]
        model.refresh()
        assertSame(first, model.texts[0])
    }

    @Test
    fun onlyTheEndOfALongLogIsKept() {
        val disk = Files().apply { texts["out"] = "x".repeat(LogsModel.MAX_BYTES + 10) }
        val model = LogsModel(files, disk)
        model.refresh()
        assertTrue(model.texts[0]!!.truncated)
        assertEquals(LogsModel.MAX_BYTES, model.texts[0]!!.text.length)
    }

    @Test
    fun anUnreadableLogIsSaidAndTheOthersStillRead() {
        val disk = Files().apply {
            texts["out"] = "ok"
            failing = "err"
        }
        val model = LogsModel(files, disk)
        model.refresh()
        assertEquals("ok", model.texts[0]?.text)
        assertTrue(model.problems[1]!!.contains("denied"))
        disk.failing = null
        model.refresh()
        assertNull(model.problems[1])
    }

    @Test
    fun theFirstTabIsKeptInRange() {
        assertEquals(1, LogsModel(files, Files(), selected = 5).selected)
        assertEquals(0, LogsModel(emptyList(), Files(), selected = 2).selected)
    }
}
