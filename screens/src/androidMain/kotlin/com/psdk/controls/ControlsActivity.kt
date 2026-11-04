package com.psdk.controls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.psdk.saves.SaveTransfer
import com.psdk.saves.SavesModel
import com.psdk.saves.SavesSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Controls screen of one game: its player's layout file in
 * [EXTRA_EXECUTION_LOCATION]. Every change is saved at once; a running game
 * loads the file again once this closes (the opener's job).
 *
 * With [Saves], it also exports and imports the game's saves (SaveTransfer),
 * through the system's file picker. Saves imported under a running game
 * leave SaveTransfer's marker: the opener closes the game on it, before it
 * saves over them.
 */
class ControlsActivity : ComponentActivity() {
    private var saves: Saves? = null
    private var savesModel: SavesModel? = null

    /** The saves file picked to import, until the player confirms */
    private var pendingImport: Uri? = null

    private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(::export)
    }
    private val importFrom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::inspect)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val executionLocation = intent.getStringExtra(EXTRA_EXECUTION_LOCATION) ?: return finish()
        // The screen is dark whatever the system's theme
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val model = ControlsModel(FileControlsStore(File(executionLocation)), AndroidLabelRenderer())
        @Suppress("DEPRECATION") // getParcelableExtra(name, class) needs API 33
        val logs = intent.getParcelableExtra<Intent>(EXTRA_LOGS)
        val onLogs = logs?.let { { startActivity(it) } }
        saves = intent.getStringExtra(EXTRA_SAVE_ROOT)?.let { root ->
            Saves(
                File(root),
                File(intent.getStringExtra(EXTRA_SAVES_BACKUP) ?: File(executionLocation, BACKUP_DIR).path),
                intent.getStringExtra(EXTRA_GAME_TITLE),
            )
        }
        val savesModel = saves?.let { SavesModel(gameRunning = intent.getBooleanExtra(EXTRA_GAME_RUNNING, false)) }
        this.savesModel = savesModel
        val savesSection: (@androidx.compose.runtime.Composable () -> Unit)? = savesModel?.let {
            {
                SavesSection(
                    it,
                    onExport = ::pickExport,
                    onImport = { importFrom.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                    onConfirmImport = ::import,
                    onImportedClosed = ::finish,
                )
            }
        }
        setContent { ControlsScreen(model, onClose = ::close, onLogs = onLogs, saves = savesSection) }
        // Not closed half-way through the saves: an import under a running
        // game must be done (its marker written) before the game hears back
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
    }

    private fun close() {
        if (savesModel?.busy != true) finish()
    }

    /** The file picker, once there are saves to write: it creates the file */
    private fun pickExport() = work { saves, _ ->
        if (SaveTransfer.files(saves.root).isEmpty()) throw SaveTransfer.Error(NO_SAVES)
        withContext(Dispatchers.Main) { exportTo.launch(SaveTransfer.defaultName(saves.game)) }
    }

    private fun export(uri: Uri) = work { saves, model ->
        val count = try {
            contentResolver.openOutputStream(uri, "wt")?.use { SaveTransfer.export(saves.root, it, saves.game).size }
                ?: throw SaveTransfer.Error("The file can't be written")
        } catch (e: java.io.IOException) {
            // Not a broken saves file left behind
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            throw e
        }
        model.message = "Saves exported ($count files). Import them on the other device: in this game, Settings > Saves, " +
            "or on a computer, as the file's README.txt says."
    }

    private fun inspect(uri: Uri) = work { _, model ->
        val contents = contentResolver.openInputStream(uri)?.use(SaveTransfer::inspect)
            ?: throw SaveTransfer.Error("The file can't be read")
        pendingImport = uri
        model.pending = contents
    }

    private fun import() {
        val uri = pendingImport ?: return
        pendingImport = null
        savesModel?.pending = null
        val resolver = applicationContext.contentResolver
        work { saves, model ->
            // Not cancelled half-way by the screen going away: the saves move as a whole
            val imported = withContext(NonCancellable) {
                resolver.openInputStream(uri)?.use { SaveTransfer.import(it, saves.root, saves.backup) }
            } ?: throw SaveTransfer.Error("The file can't be read")
            model.imported = model.gameRunning
            model.message = buildString {
                append("Saves imported (${imported.saves.size} files).")
                imported.backup?.let { append(" Your previous saves are kept in ${it.name}.") }
                if (model.gameRunning) append(" The game closes now: start it again to play them.")
            }
        }
    }

    /** [block] off the main thread, its error as the section's message */
    private fun work(block: suspend (Saves, SavesModel) -> Unit) {
        val saves = saves ?: return
        val model = savesModel ?: return
        model.busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block(saves, model) }
            } catch (e: java.io.IOException) {
                model.message = e.message ?: "The saves file can't be used"
            } catch (e: SecurityException) {
                model.message = "The file can't be opened"
            } catch (e: RuntimeException) {
                // A provider's own failure: a message, not the app gone
                android.util.Log.e("ControlsActivity", "saves file", e)
                model.message = "The saves file can't be used (${e.javaClass.simpleName})"
            } finally {
                model.busy = false
            }
        }
    }

    /** Where the game's saves are, where an import puts the saves it replaces, the game's name */
    private class Saves(val root: File, val backup: File, val game: String?)

    companion object {
        const val EXTRA_EXECUTION_LOCATION = "EXECUTION_LOCATION"
        private const val EXTRA_LOGS = "LOGS"
        private const val EXTRA_SAVE_ROOT = "SAVE_ROOT"
        private const val EXTRA_SAVES_BACKUP = "SAVES_BACKUP"
        private const val EXTRA_GAME_TITLE = "GAME_TITLE"
        private const val EXTRA_GAME_RUNNING = "GAME_RUNNING"
        private const val BACKUP_DIR = "SavesBackup"
        private const val NO_SAVES = "No saves yet: save in game first."

        /**
         * @param logs opens the game's logs, from a running game: a link to it
         * @param saveRoot the game's save root (Release/; debug mode: the game's folder): the Saves section, null hides it
         * @param gameTitle names the saves file
         * @param gameRunning opened from a running game, which an import closes
         */
        fun intent(
            context: Context,
            executionLocation: String,
            logs: Intent? = null,
            saveRoot: File? = null,
            gameTitle: String? = null,
            gameRunning: Boolean = false,
        ): Intent =
            Intent(context, ControlsActivity::class.java)
                .putExtra(EXTRA_EXECUTION_LOCATION, executionLocation)
                .putExtra(EXTRA_LOGS, logs)
                .putExtra(EXTRA_SAVE_ROOT, saveRoot?.path)
                .putExtra(EXTRA_SAVES_BACKUP, File(executionLocation, BACKUP_DIR).path)
                .putExtra(EXTRA_GAME_TITLE, gameTitle)
                .putExtra(EXTRA_GAME_RUNNING, gameRunning)
    }
}
