package com.psdk.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psdk.screens.PsdkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter

/**
 * A developer's logs (the game's output, its Error.log, a compile's): one
 * tab each, shown from the end of an output, an error report's start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    title: String,
    model: LogsModel,
    onCopy: (String) -> Unit,
    onShare: (LogFile, String) -> Unit,
    onClose: () -> Unit,
) {
    val file = model.files.getOrNull(model.selected)
    val log = model.texts.getOrNull(model.selected)
    PsdkTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (file != null && log != null && log.text.isNotEmpty()) {
                            TextButton(onClick = { onCopy(log.text) }) { Text("Copy") }
                            IconButton(onClick = { onShare(file, log.text) }) { Icon(Icons.Filled.Share, "Share") }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (model.files.size > 1) {
                    PrimaryTabRow(selectedTabIndex = model.selected) {
                        model.files.forEachIndexed { i, tab ->
                            Tab(selected = i == model.selected, onClick = { model.selected = i }, text = { Text(tab.label) })
                        }
                    }
                }
                if (model.loaded && file != null) Log(file, log, model.problems.getOrNull(model.selected), model.selected)
            }
        }
    }
}

@Composable
private fun Log(file: LogFile, log: LogText?, problem: String?, tab: Int) {
    val scroll = rememberScrollState()
    // A log shown from its end opens there (a new tab too), and stays there
    // when read again longer, unless the reader had scrolled away from it;
    // one shown from its start stays there. maxValue is Int.MAX_VALUE until
    // laid out.
    LaunchedEffect(tab) {
        if (!file.fromEnd) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        var end = 0
        snapshotFlow { scroll.maxValue }.filter { it != Int.MAX_VALUE }.collect { newEnd ->
            if (scroll.value >= end - FOLLOW_SLACK) {
                try {
                    scroll.scrollTo(newEnd)
                } catch (e: CancellationException) {
                    // The reader's own scroll took over; following goes on unless it was this effect's end
                    currentCoroutineContext().ensureActive()
                }
            }
            end = newEnd
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            log == null -> Text("No log yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.text.isEmpty() -> Text("Empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                if (log.truncated) {
                    Text(
                        "Only the end of this log is shown: Share sends the whole file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer {
                    Text(log.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** Pixels from the end still counted as "at the end". */
private const val FOLLOW_SLACK = 48
