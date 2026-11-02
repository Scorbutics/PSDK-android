package com.psdk.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A game's or a compile's logs: the files of [intent], one tab each, read
 * whenever the screen shows. A game
 * behind it lost its surface and waits for it: what it may still write
 * shows once the screen shows again.
 */
class LogsActivity : ComponentActivity() {
    private lateinit var model: LogsModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val labels = intent.getStringArrayExtra(EXTRA_LABELS) ?: return finish()
        val paths = intent.getStringArrayExtra(EXTRA_PATHS) ?: return finish()
        val fromEnd = intent.getBooleanArrayExtra(EXTRA_FROM_END) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Logs"
        val selected = savedInstanceState?.getInt(STATE_SELECTED) ?: intent.getIntExtra(EXTRA_SELECTED, 0)
        val files = labels.indices.map { LogFile(labels[it], paths[it], fromEnd[it]) }
        model = LogsModel(files, FileLogReader, selected)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            LogsScreen(title, model, onCopy = ::copy, onShare = { file, text -> share(title, file, text) }, onClose = ::finish)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) { model.refresh() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::model.isInitialized) outState.putInt(STATE_SELECTED, model.selected)
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * The whole file, copied into the cache the app's FileProvider exposes
     * (a game's folder may be outside what it covers); the text shown when
     * the copy fails.
     */
    private fun share(title: String, file: LogFile, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "$title — ${file.label}")
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val copy = File(File(cacheDir, SHARED_DIR).apply { mkdirs() }, "${file.label}.log")
                    File(file.path).copyTo(copy, overwrite = true)
                    FileProvider.getUriForFile(this@LogsActivity, "$packageName.provider", copy)
                }.getOrNull()
            }
            if (uri != null) {
                intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.label}"))
        }
    }

    companion object {
        private const val EXTRA_TITLE = "TITLE"
        private const val EXTRA_LABELS = "LABELS"
        private const val EXTRA_PATHS = "PATHS"
        private const val EXTRA_FROM_END = "FROM_END"
        private const val EXTRA_SELECTED = "SELECTED"
        private const val STATE_SELECTED = "selected"
        /** In the cache: logs being shared */
        private const val SHARED_DIR = "shared_logs"

        /** @param selected the tab shown first */
        fun intent(context: Context, title: String, files: List<LogFile>, selected: Int = 0): Intent =
            Intent(context, LogsActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_LABELS, files.map { it.label }.toTypedArray())
                .putExtra(EXTRA_PATHS, files.map { it.path }.toTypedArray())
                .putExtra(EXTRA_FROM_END, files.map { it.fromEnd }.toBooleanArray())
                .putExtra(EXTRA_SELECTED, selected)
    }
}
