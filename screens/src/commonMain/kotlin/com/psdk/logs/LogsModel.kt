package com.psdk.logs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A log the screen shows in a tab. [fromEnd]: shown from its end (an
 * output); else from its start (an error report, its message first).
 */
data class LogFile(val label: String, val path: String, val fromEnd: Boolean = true)

/**
 * The end of a log, at most a reader's maximum: [truncated] when the
 * beginning was left out. [stamp] changes whenever the file does (its size
 * and modification time).
 */
data class LogText(val text: String, val truncated: Boolean, val stamp: String)

/** Reads the end of a log file. */
fun interface LogReader {
    /** @return null when there is no such file (yet) */
    fun read(path: String, maxBytes: Int): LogText?
}

/**
 * What the Logs screen shows: one tab per log, read by [refresh]. A file
 * read again unchanged keeps its text, and the screen its place in it.
 */
class LogsModel(val files: List<LogFile>, private val reader: LogReader, selected: Int = 0) {

    var selected by mutableStateOf(selected.coerceIn(0, (files.size - 1).coerceAtLeast(0)))

    /** Per tab, the last text read: null while the file does not exist. */
    var texts by mutableStateOf<List<LogText?>>(List(files.size) { null })
        private set

    /** Why a log could not be read, per tab. */
    var problems by mutableStateOf<List<String?>>(List(files.size) { null })
        private set

    /** Once the logs were read: "no log yet" is only said then. */
    var loaded by mutableStateOf(false)
        private set

    /** Any thread: the state is snapshot state. */
    fun refresh() {
        val texts = texts.toMutableList()
        val problems = problems.toMutableList()
        files.forEachIndexed { i, file ->
            try {
                val text = reader.read(file.path, MAX_BYTES)
                if (text?.stamp != texts[i]?.stamp) texts[i] = text
                problems[i] = null
            } catch (e: Exception) {
                problems[i] = "Not read: ${e.message ?: e::class.simpleName}"
            }
        }
        if (texts != this.texts) this.texts = texts
        if (problems != this.problems) this.problems = problems
        loaded = true
    }

    companion object {
        /** The end of a log this long at most: the screen stays responsive. */
        const val MAX_BYTES = 256 * 1024
    }
}
