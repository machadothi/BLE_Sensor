package com.machadothi.blesensor.ui.screen.board.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.machadothi.blesensor.ble.UpdateStatus
import com.machadothi.blesensor.ui.components.AppSwitch
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.theme.Coral
import com.machadothi.blesensor.ui.theme.Lime

/**
 * Firmware updates of boards that update themselves (ESP32 Air): the version,
 * "Check now", the available update with Install / Later, and auto-install.
 * Checking and installing restart the board; the app reconnects by itself.
 */
@Composable
fun UpdateCard(
    update: UpdateStatus,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onAuto: (Boolean) -> Unit,
) {
    val available = update.state == UpdateStatus.State.AVAILABLE && update.availableVersion != null
    GlowCard(Modifier.fillMaxWidth(), accent = Lime, highlighted = available) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader(Icons.Rounded.SystemUpdate, "Firmware", Lime)
            Text("Version ${update.currentVersion}", style = MaterialTheme.typography.bodyLarge)
            Text(
                when (update.state) {
                    UpdateStatus.State.AVAILABLE -> "Version ${update.availableVersion} is available"
                    UpdateStatus.State.FAILED -> update.error ?: "The last check failed"
                    UpdateStatus.State.UP_TO_DATE -> "Up to date"
                    UpdateStatus.State.IDLE -> "Checked every night at 03:30 when online"
                    else -> update.state.label
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (update.state == UpdateStatus.State.FAILED) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(available) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    update.notes?.let { ReleaseNotes(it, Modifier.heightIn(max = 160.dp)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onInstall) { Text("Install ${update.availableVersion}") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onLater) { Text("Later") }
                    }
                }
            }
            if (!available) {
                FilledTonalButton(onClick = onCheck, enabled = update.state != UpdateStatus.State.CHECKING) {
                    Text("Check now")
                }
                Text(
                    "The board restarts to check (about 30 s); the app reconnects by itself.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Install automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (update.auto) "New versions install at night without asking"
                        else "You're asked first",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(checked = update.auto, onCheckedChange = onAuto)
            }
        }
    }
}

/** Asks before installing an update the board found. */
@Composable
fun UpdateDialog(update: UpdateStatus, onInstall: () -> Unit, onLater: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = Lime) },
        title = { Text("Update to ${update.availableVersion}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The board runs ${update.currentVersion}.")
                update.notes?.let { ReleaseNotes(it, Modifier.heightIn(max = 220.dp)) }
                Text(
                    "The board restarts, downloads the update (about a minute, no readings meanwhile) " +
                        "and goes back to the old version by itself if the new one doesn't start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

@Composable
private fun ReleaseNotes(notes: String, modifier: Modifier = Modifier) {
    Text(
        notes.trim(),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.verticalScroll(rememberScrollState()),
    )
}
