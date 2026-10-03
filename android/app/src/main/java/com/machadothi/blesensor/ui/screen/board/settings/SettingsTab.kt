package com.machadothi.blesensor.ui.screen.board.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.Limits
import com.machadothi.blesensor.ble.Protocol
import com.machadothi.blesensor.ble.Sensor
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.components.LabeledSlider
import com.machadothi.blesensor.ui.components.formatMs
import com.machadothi.blesensor.ui.components.niceRound
import com.machadothi.blesensor.ui.screen.board.BoardViewModel
import com.machadothi.blesensor.ui.theme.Amber
import com.machadothi.blesensor.ui.theme.Sky
import com.machadothi.blesensor.ui.theme.Teal
import com.machadothi.blesensor.ui.theme.Violet
import kotlin.math.roundToInt

@Composable
fun SettingsTab(viewModel: BoardViewModel) {
    val saved by viewModel.config.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val savedName by viewModel.name.collectAsStateWithLifecycle()
    val current = saved ?: return

    // Edits are a draft until "Apply"; a new board config resets the draft.
    var draft by remember(current) { mutableStateOf(current) }
    var nameDraft by remember(savedName) { mutableStateOf(savedName.orEmpty()) }
    val available = info?.available ?: emptySet()
    val changes = countChanges(current, draft)
    val nameBytes = nameDraft.trim().encodeToByteArray().size

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlowCard(Modifier.fillMaxWidth(), accent = Violet) {
                Column {
                    CardHeader(Icons.Rounded.Badge, "Name", Violet)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            singleLine = true,
                            isError = nameBytes !in 1..Protocol.NAME_MAX_BYTES,
                            supportingText = { Text("$nameBytes / ${Protocol.NAME_MAX_BYTES} bytes · advertised after disconnect") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { viewModel.setName(nameDraft) },
                            enabled = nameDraft.trim() != savedName && nameBytes in 1..Protocol.NAME_MAX_BYTES,
                        ) { Text("Save") }
                    }
                }
            }

            GlowCard(Modifier.fillMaxWidth(), accent = Teal) {
                Column {
                    CardHeader(Icons.Rounded.Sensors, "Sensors", Teal)
                    Sensor.entries.forEach { sensor ->
                        val present = sensor in available
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sensor.label, style = MaterialTheme.typography.bodyLarge, color = if (present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!present) Text("Not on this board", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = present && sensor in draft.sensors,
                                enabled = present,
                                onCheckedChange = { on -> draft = draft.copy(sensors = if (on) draft.sensors + sensor else draft.sensors - sensor) },
                            )
                        }
                    }
                }
            }

            GlowCard(Modifier.fillMaxWidth(), accent = Sky) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeader(Icons.Rounded.Timer, "Sampling", Sky)
                    LabeledSlider(
                        label = "Environment every",
                        valueText = formatMs(draft.envPeriodMs),
                        value = draft.envPeriodMs.toFloat(),
                        range = Limits.envPeriodMs.first.toFloat()..Limits.envPeriodMs.last.toFloat(),
                        logarithmic = true,
                        onChange = { draft = draft.copy(envPeriodMs = niceRound(it).coerceIn(Limits.envPeriodMs)) },
                    )
                    LabeledSlider(
                        label = "Motion rate",
                        valueText = "%.0f Hz".format(1000f / draft.motionPeriodMs),
                        value = draft.motionPeriodMs.toFloat(),
                        range = Limits.motionPeriodMs.first.toFloat()..Limits.motionPeriodMs.last.toFloat(),
                        logarithmic = true,
                        hint = "Every ${draft.motionPeriodMs} ms",
                        onChange = { draft = draft.copy(motionPeriodMs = it.roundToInt().coerceIn(Limits.motionPeriodMs)) },
                    )
                    LabeledSlider(
                        label = "Magnet alert above",
                        valueText = "%.1f mT".format(draft.hallThresholdMt),
                        value = draft.hallThresholdMt,
                        range = Limits.hallThresholdMt,
                        logarithmic = true,
                        onChange = { draft = draft.copy(hallThresholdMt = (it * 10).roundToInt() / 10f) },
                    )
                }
            }

            GlowCard(Modifier.fillMaxWidth(), accent = Amber) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardHeader(Icons.Rounded.CellTower, "Radio", Amber)
                    LabeledSlider(
                        label = "Transmit power",
                        valueText = "%.1f dBm".format(draft.txPowerDbm),
                        value = draft.txPowerDbm,
                        range = Limits.txPowerDbm,
                        hint = "Higher reaches farther, uses more power. Applied after disconnect.",
                        onChange = { draft = draft.copy(txPowerDbm = (it * 2).roundToInt() / 2f) },
                    )
                    LabeledSlider(
                        label = "Advertising interval",
                        valueText = formatMs(draft.advIntervalMs),
                        value = draft.advIntervalMs.toFloat(),
                        range = Limits.advIntervalMs.first.toFloat()..Limits.advIntervalMs.last.toFloat(),
                        logarithmic = true,
                        hint = "Shorter is found faster, uses more power. Applied after disconnect.",
                        onChange = { draft = draft.copy(advIntervalMs = niceRound(it).coerceIn(Limits.advIntervalMs)) },
                    )
                }
            }
        }

        // Apply bar slides up when there are unsaved changes.
        AnimatedVisibility(
            visible = changes > 0,
            enter = slideInVertically { it } ,
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (changes == 1) "1 unsaved change" else "$changes unsaved changes",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { draft = current }) { Text("Revert") }
                    Button(onClick = { viewModel.setConfig(draft) }, enabled = draft.isValid) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Apply")
                    }
                }
            }
        }
    }
}

private fun countChanges(a: BoardConfig, b: BoardConfig): Int = listOf(
    a.sensors != b.sensors,
    a.envPeriodMs != b.envPeriodMs,
    a.motionPeriodMs != b.motionPeriodMs,
    a.txPowerDbm != b.txPowerDbm,
    a.advIntervalMs != b.advIntervalMs,
    a.hallThresholdMt != b.hallThresholdMt,
).count { it }
