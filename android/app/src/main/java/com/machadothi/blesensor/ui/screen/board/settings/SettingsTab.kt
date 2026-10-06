package com.machadothi.blesensor.ui.screen.board.settings

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.AnimatedContent
import com.machadothi.blesensor.ble.CalibrationStatus
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.runtime.mutableFloatStateOf
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
import com.machadothi.blesensor.ui.components.AppSwitch
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
    val display by viewModel.display.collectAsStateWithLifecycle()
    val current = saved
    if (current == null) {
        // Boards without a Config characteristic (ESP32 Air): name and display only.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NameCard(savedName, viewModel::setName)
            val calibration by viewModel.calibration.collectAsStateWithLifecycle()
            val env by viewModel.env.collectAsStateWithLifecycle()
            calibration?.let {
                CalibrationCard(
                    it, env?.temperatureC,
                    onOffset = viewModel::setTemperatureOffset,
                    onAuto = viewModel::setAutoCalibration,
                    onStart = viewModel::startCalibration,
                    onCancel = viewModel::cancelCalibration,
                )
            }
            display?.let { state ->
                DisplayCard(state, info, onPagesChanged = viewModel::setDisplayPages, onPageMsChanged = viewModel::setDisplayPageMs, onRotatedChanged = viewModel::setDisplayRotated)
            }
        }
        return
    }

    // Edits are a draft until "Apply"; a new board config resets the draft.
    var draft by remember(current) { mutableStateOf(current) }
    val available = info?.available ?: emptySet()
    val changes = countChanges(current, draft)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NameCard(savedName, viewModel::setName)

            GlowCard(Modifier.fillMaxWidth(), accent = Teal) {
                Column {
                    CardHeader(Icons.Rounded.Sensors, "Sensors", Teal)
                    // Only the sensors Config can switch (not air quality: ESP32 Air board only).
                    Sensor.entries.filter { it.switchable }.forEach { sensor ->
                        val present = sensor in available
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sensor.label, style = MaterialTheme.typography.bodyLarge, color = if (present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!present) Text("Not on this board", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppSwitch(
                                checked = present && sensor in draft.sensors,
                                enabled = present,
                                onCheckedChange = { on -> draft = draft.copy(sensors = if (on) draft.sensors + sensor else draft.sensors - sensor) },
                            )
                        }
                    }
                }
            }

            // Only with firmware that supports the OLED display.
            display?.let { state ->
                DisplayCard(
                    state, info,
                    onPagesChanged = viewModel::setDisplayPages,
                    onPageMsChanged = viewModel::setDisplayPageMs,
                    onRotatedChanged = viewModel::setDisplayRotated,
                )
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

/** The board's Bluetooth name; saved on the board, advertised after the next disconnect. */
@Composable
private fun NameCard(savedName: String?, onSave: (String) -> Unit) {
    var nameDraft by remember(savedName) { mutableStateOf(savedName.orEmpty()) }
    val nameBytes = nameDraft.trim().encodeToByteArray().size
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
                    onClick = { onSave(nameDraft) },
                    enabled = nameDraft.trim() != savedName && nameBytes in 1..Protocol.NAME_MAX_BYTES,
                ) { Text("Save") }
            }
        }
    }
}

/**
 * Temperature offset (ESP32 Air): the AHT21 shares its board with the ENS160,
 * whose heaters warm it. Set it by hand against a trusted thermometer, or let
 * the board measure it: it puts the ENS160 to sleep and sees how far the
 * temperature drops. Humidity and the ENS160's compensation follow.
 */
@Composable
private fun CalibrationCard(
    status: CalibrationStatus,
    shownC: Float?,
    onOffset: (Float) -> Unit,
    onAuto: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(status.offsetC) { mutableFloatStateOf(status.offsetC) }
    val cooling = status.state == CalibrationStatus.State.COOLING
    GlowCard(Modifier.fillMaxWidth(), accent = Amber, highlighted = cooling) {
        Column {
            CardHeader(Icons.Rounded.Thermostat, "Calibration", Amber)
            LabeledSlider(
                label = "Temperature offset",
                valueText = "%+.1f °C".format(draft),
                value = draft,
                range = -5f..5f,
                onChange = { draft = Math.round(it * 10) / 10f },
                onChangeFinished = { onOffset(draft) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (shownC != null && !cooling) {
                Text(
                    "Sensor measures %.1f °C, shown as %.1f °C".format(shownC - status.offsetC, shownC - status.offsetC + draft),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.canMeasure) {
                Spacer(Modifier.height(12.dp))
                Text("Measure the offset automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The board puts its air sensor to sleep, waits until the temperature stops falling " +
                        "(5–${status.cooldownS / 60} min) and uses the drop as the offset. No air readings meanwhile, " +
                        "then 3 min warm-up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(targetState = cooling, label = "calibration") { running ->
                    if (running) {
                        Column(Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = { if (status.cooldownS > 0) status.elapsedS / status.cooldownS.toFloat() else 0f },
                                color = Amber,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            val drop = if (status.warmC != null && status.nowC != null) status.nowC - status.warmC else null
                            Text(
                                "Cooling for ${status.elapsedS / 60} min ${status.elapsedS % 60} s · " +
                                    "warm %.2f °C → now %.2f °C".format(status.warmC ?: 0f, status.nowC ?: 0f) +
                                    (drop?.let { " (%+.2f)".format(it) } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onCancel) { Text("Cancel") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = onStart) { Text("Measure now") }
                            Spacer(Modifier.weight(1f))
                            Text("Daily", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            AppSwitch(checked = status.auto == true, onCheckedChange = onAuto)
                        }
                    }
                }
                val last = when (status.state) {
                    CalibrationStatus.State.DONE -> status.resultC?.let { r ->
                        "Last measurement: %+.1f °C".format(r) + (status.resultAgeS?.let { " · ${formatAge(it)} ago" } ?: "") +
                            " (now in use)"
                    }
                    CalibrationStatus.State.FAILED -> "Last measurement failed: ${status.failure ?: "unknown reason"}"
                    else -> null
                }
                if (last != null) {
                    Text(last, style = MaterialTheme.typography.labelMedium, color = Amber, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                Text(
                    "The air sensor next to it heats the board. After 30 minutes, compare with a thermometer you trust " +
                        "and set the difference (e.g. board 26.0 °C, room 24.0 °C: −2.0). Humidity is corrected to match.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h"
    else -> "${seconds / 86_400} d"
}
