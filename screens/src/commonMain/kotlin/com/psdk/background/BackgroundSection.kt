package com.psdk.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * In the background: keep the game running there (a notification while
 * playing makes Android less likely to close it). On by default.
 */
@Composable
fun BackgroundSection(keepAlive: Boolean, onKeepAlive: (Boolean) -> Unit) {
    Text("In the background", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep the game running", style = MaterialTheme.typography.bodyLarge)
            Text(
                "A notification shows while you play: Android is then less likely to close the game " +
                    "when you switch apps. If it still does, you can resume where you left off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = keepAlive, onCheckedChange = onKeepAlive)
    }
}
