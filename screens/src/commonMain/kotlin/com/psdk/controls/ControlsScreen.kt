package com.psdk.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.psdk.screens.PsdkTheme
import com.scorbutics.litergss.overlay.ActionLayout
import com.scorbutics.litergss.overlay.Keys
import com.scorbutics.litergss.overlay.PlayerButton
import com.scorbutics.litergss.overlay.PlayerLayout
import kotlin.math.roundToInt

/**
 * The player's Controls: the gamepad's size, opacity, button spacing and
 * A / B order, the player's own buttons, and the way back to the game's
 * controls. Places are changed in game (the gamepad's tools button, Edit
 * controls).
 * @param onLogs the game's logs, opened in game: null hides them
 * @param saves the game's Saves section (export / import): null hides it
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsScreen(
    model: ControlsModel,
    onClose: () -> Unit,
    onLogs: (() -> Unit)? = null,
    saves: (@Composable () -> Unit)? = null,
) {
    PsdkTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Controls") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                model.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Gamepad(model)
                HorizontalDivider()
                GamepadKeys(model)
                HorizontalDivider()
                Buttons(model)
                HorizontalDivider()
                Reset(model)
                onLogs?.let {
                    HorizontalDivider()
                    Section("Logs")
                    OutlinedButton(onClick = it) { Text("Game logs") }
                }
                saves?.let {
                    HorizontalDivider()
                    it()
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Gamepad(model: ControlsModel) {
    val layout = model.layout
    Section("Gamepad")
    // A size the file has but the screen doesn't offer selects none
    ChoiceRow("Size", SIZES, layout.scale, model::setScale)
    StepSlider(
        "Opacity", layout.opacity, PlayerLayout.OPACITIES, PlayerLayout.OPACITY_STEP,
        format = { "${(it * 100).roundToInt()}%" }, onDone = model::setOpacity,
    )
    // Of the buttons of each cross: the D-pad's and A, B, X, Y
    StepSlider(
        "Button spacing", layout.gap, PlayerLayout.GAPS, PlayerLayout.GAP_STEP,
        format = { "${(it * 100).roundToInt()}%" }, onDone = model::setGap,
    )
    ChoiceRow(
        "A and B buttons", listOf(ActionLayout.NINTENDO to "A on the right", ActionLayout.XBOX to "A at the bottom"),
        layout.actionLayout, model::setActionLayout,
    )
    ChoiceRow(
        "X and Y buttons", listOf(ActionLayout.XBOX to "X on the left", ActionLayout.NINTENDO to "X at the top"),
        layout.xyLayout, model::setXyLayout,
    )
}

@Composable
private fun <T> ChoiceRow(title: String, choices: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onPick(value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text(label) }
        }
    }
}

/** Saved once the finger leaves it: each save rewrites the file. */
@Composable
private fun StepSlider(
    title: String,
    value: Double,
    range: ClosedRange<Double>,
    step: Double,
    format: (Double) -> String,
    onDone: (Double) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(format(current.toDouble()), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = current,
        onValueChange = { current = it },
        // A touch that changes nothing writes nothing over the game's value
        onValueChangeFinished = { if (current != value.toFloat()) onDone(current.toDouble()) },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
    )
}

@Composable
private fun Buttons(model: ControlsModel) {
    // The dialog: a new button, or the name of the one changed
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Section("My buttons")
    Text(
        "New buttons appear where the screen is free. Move them in game: " +
            "tap the gamepad's gear button, then Edit controls.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    model.layout.buttons.forEach { button ->
        Row(
            Modifier.fillMaxWidth().clickable { editing = button.name }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(button.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 72.dp))
            Text(Keys.label(button.key), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { model.deleteButton(button.name) }) { Icon(Icons.Filled.Delete, "Delete ${button.label}") }
        }
    }
    OutlinedButton(onClick = { adding = true }) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Add a button")
    }
    val edited = editing?.let { name -> model.layout.buttons.find { it.name == name } }
    if (adding || edited != null) {
        val close = {
            adding = false
            editing = null
        }
        ButtonDialog(
            edited,
            onSave = { label, key ->
                model.saveButton(edited?.name, label, key)
                close()
            },
            onDismiss = close,
        )
    }
}

/** @param button the one changed, null for a new one */
@Composable
private fun ButtonDialog(button: PlayerButton?, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf(button?.label ?: "") }
    var key by rememberSaveable { mutableStateOf(button?.key ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (button == null) "New button" else "Button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(ControlsModel.LABEL_MAX) },
                    label = { Text("Label") },
                    singleLine = true,
                )
                KeyField("Key it sends", key.ifEmpty { null }, onPick = { key = it.orEmpty() })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, key) }, enabled = label.isNotBlank() && key.isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The key each gamepad button sends: the game's, or the player's choice. */
@Composable
private fun GamepadKeys(model: ControlsModel) {
    val remaps = model.layout.remaps
    Section("Gamepad keys")
    Text(
        "Each gamepad button sends the game's key, or the one you choose here: the game's key settings no longer change that button.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PlayerLayout.GAMEPAD.forEach { (name, shown) ->
        KeyField(shown, remaps[name], gameDefault = true, onPick = { model.setRemap(name, it) })
    }
}

/**
 * A key: the field shows it, a tap opens every key, by kind (Keys.GROUPS).
 * @param gameDefault offers "Game's key" (null) too
 */
@Composable
private fun KeyField(
    title: String,
    key: String?,
    onPick: (String?) -> Unit,
    gameDefault: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = key?.let(Keys::label) ?: if (gameDefault) GAME_KEY else ""
    Box {
        // Drawn only: the box over it is the one control (its click, focus, TalkBack)
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(title) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }.clearAndSetSemantics {},
        )
        Box(
            Modifier.matchParentSize()
                .clickable(role = Role.Button, onClickLabel = "Choose") { open = true }
                .semantics { contentDescription = "$title: ${shown.ifEmpty { "none" }}" },
        )
    }
    if (open) {
        KeyDialog(title, key, gameDefault, onPick = {
            onPick(it)
            open = false
        }, onDismiss = { open = false })
    }
}

@Composable
private fun KeyDialog(title: String, key: String?, gameDefault: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (gameDefault) {
                    FilterChip(selected = key == null, onClick = { onPick(null) }, label = { Text(GAME_KEY) })
                }
                Keys.GROUPS.forEach { (group, keys) ->
                    Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEach { (name, shown) ->
                            FilterChip(selected = name == key, onClick = { onPick(name) }, label = { Text(shown) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val GAME_KEY = "Game's key"

/** The gamepad sizes offered (PlayerLayout.scale) */
private val SIZES = listOf(0.5 to "Small", 1.0 to "Medium", 1.5 to "Large")

@Composable
private fun Reset(model: ControlsModel) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { confirming = true }) { Text("Reset controls") }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset controls?") },
            text = { Text("The game's controls come back: your buttons, places, keys and settings are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    model.reset()
                    confirming = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
