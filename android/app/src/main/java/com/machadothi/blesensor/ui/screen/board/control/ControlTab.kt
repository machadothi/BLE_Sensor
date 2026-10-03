package com.machadothi.blesensor.ui.screen.board.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.LedMode
import com.machadothi.blesensor.ble.LedState
import com.machadothi.blesensor.ble.Limits
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.ConfirmDialog
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.components.LabeledSlider
import com.machadothi.blesensor.ui.components.LedOrb
import com.machadothi.blesensor.ui.components.formatMs
import com.machadothi.blesensor.ui.components.niceRound
import com.machadothi.blesensor.ui.screen.board.BoardViewModel
import com.machadothi.blesensor.ui.theme.Amber
import com.machadothi.blesensor.ui.theme.Coral
import com.machadothi.blesensor.ui.theme.Teal
import kotlinx.coroutines.delay

private const val BLINK_MAX_MS = 3000f

@Composable
fun ControlTab(viewModel: BoardViewModel) {
    val boardLed by viewModel.led.collectAsStateWithLifecycle()
    var led by remember(boardLed) { mutableStateOf(boardLed ?: LedState(LedMode.OFF, 500, 500)) }
    var confirm by remember { mutableStateOf<Command?>(null) }

    // The orb follows the chosen mode and blink rhythm, like the real LED.
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(led) {
        when (led.mode) {
            LedMode.OFF -> lit = false
            LedMode.ON -> lit = true
            LedMode.BLINK -> while (true) {
                lit = true; delay(led.onMs.toLong())
                lit = false; delay(led.offMs.toLong())
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlowCard(Modifier.fillMaxWidth(), accent = Amber, highlighted = lit) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CardHeader(Icons.Rounded.Lightbulb, "LED", Amber)
                LedOrb(lit, size = 120.dp)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LedMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = led.mode == mode,
                            onClick = { led = led.copy(mode = mode); viewModel.setLed(led) },
                            shape = SegmentedButtonDefaults.itemShape(i, LedMode.entries.size),
                        ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
                if (led.mode == LedMode.BLINK) {
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = "On time",
                        valueText = formatMs(led.onMs),
                        value = led.onMs.toFloat(),
                        range = Limits.LED_BLINK_MIN_MS.toFloat()..BLINK_MAX_MS,
                        logarithmic = true,
                        onChange = { led = led.copy(onMs = niceRound(it).coerceAtLeast(Limits.LED_BLINK_MIN_MS)) },
                        onChangeFinished = { viewModel.setLed(led) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledSlider(
                        label = "Off time",
                        valueText = formatMs(led.offMs),
                        value = led.offMs.toFloat(),
                        range = Limits.LED_BLINK_MIN_MS.toFloat()..BLINK_MAX_MS,
                        logarithmic = true,
                        onChange = { led = led.copy(offMs = niceRound(it).coerceAtLeast(Limits.LED_BLINK_MIN_MS)) },
                        onChangeFinished = { viewModel.setLed(led) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        GlowCard(Modifier.fillMaxWidth(), accent = Teal) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardHeader(Icons.Rounded.Visibility, "Board actions", Teal)
                ActionButton(Icons.Rounded.Visibility, "Identify", "Blink the LED fast for 3 s to spot this board") {
                    viewModel.send(Command.IDENTIFY)
                }
                ActionButton(Icons.Rounded.RestartAlt, "Reboot", "Restart the board; the app reconnects when you ask") {
                    confirm = Command.REBOOT
                }
                ActionButton(Icons.Rounded.SettingsBackupRestore, "Factory reset", "Default settings and name", danger = true) {
                    confirm = Command.FACTORY_RESET
                }
            }
        }
    }

    when (confirm) {
        Command.REBOOT -> ConfirmDialog(
            "Reboot the board?", "The connection will drop while the board restarts.", "Reboot",
            onConfirm = { viewModel.send(Command.REBOOT) }, onDismiss = { confirm = null },
        )
        Command.FACTORY_RESET -> ConfirmDialog(
            "Factory reset?", "All settings and the board's name go back to their defaults.", "Reset",
            onConfirm = { viewModel.send(Command.FACTORY_RESET) }, onDismiss = { confirm = null },
        )
        else -> Unit
    }
}

@Composable
private fun ActionButton(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, tint = if (danger) Coral else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (danger) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically) { content() } }
    } else {
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically) { content() } }
    }
}
