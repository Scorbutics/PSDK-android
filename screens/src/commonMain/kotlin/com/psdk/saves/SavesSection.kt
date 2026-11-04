package com.psdk.saves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** What a saves file holds: its manifest's fields (null when it has none) and its saves */
data class SavesContents(
    val game: String?,
    val platform: String?,
    val exportedAt: String?,
    val saves: List<String>,
)

/**
 * The Saves section's state: an import waiting for the player's yes, a
 * message once done. The work itself is the platform's (SaveTransfer).
 * @param gameRunning opened from a running game, which an import closes
 */
class SavesModel(val gameRunning: Boolean) {
    /** A saves file read, to import once confirmed */
    var pending: SavesContents? by mutableStateOf(null)

    /** Done, or what went wrong */
    var message: String? by mutableStateOf(null)

    /** Reading or writing a saves file */
    var busy: Boolean by mutableStateOf(false)

    /** Saves imported into a running game's folder: it must close */
    var imported: Boolean by mutableStateOf(false)

    fun confirmText(contents: SavesContents): String {
        val from = listOfNotNull(
            contents.game?.let { "of $it" },
            contents.platform?.let { "from ${platformName(it)}" },
            contents.exportedAt?.let { "exported ${it.substringBefore('T')}" },
        ).joinToString(" ")
        val files = if (contents.saves.size == 1) "1 file" else "${contents.saves.size} files"
        return buildString {
            append("Replace this game's saves with the saves ")
            if (from.isNotEmpty()) append(from).append(' ')
            append("($files)? The saves here now are kept in a backup.")
            if (gameRunning) append(" The game closes afterwards: start it again to play.")
        }
    }

    private fun platformName(platform: String) = when (platform) {
        "android" -> "Android"
        "desktop" -> "a computer"
        else -> platform
    }
}

/**
 * Export / Import saves: moving them to or from another device (desktop or
 * Android), as a saves file.
 */
@Composable
fun SavesSection(model: SavesModel, onExport: () -> Unit, onImport: () -> Unit, onConfirmImport: () -> Unit, onImportedClosed: () -> Unit) {
    Text("Saves", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(
        "Move your saves to this game on another device, a computer or another phone. " +
            "Export writes the saves of the last time you saved in game.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onExport, enabled = !model.busy) { Text("Export saves") }
        OutlinedButton(onClick = onImport, enabled = !model.busy) { Text("Import saves") }
    }

    model.pending?.let { contents ->
        AlertDialog(
            onDismissRequest = { model.pending = null },
            title = { Text("Import saves?") },
            text = { Text(model.confirmText(contents)) },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("Import") } },
            dismissButton = { TextButton(onClick = { model.pending = null }) { Text("Cancel") } },
        )
    }
    model.message?.let { message ->
        val close = {
            model.message = null
            if (model.imported) onImportedClosed()
        }
        AlertDialog(
            onDismissRequest = close,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
    }
}
