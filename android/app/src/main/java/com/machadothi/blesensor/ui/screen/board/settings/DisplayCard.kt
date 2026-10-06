package com.machadothi.blesensor.ui.screen.board.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Co2
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.machadothi.blesensor.ui.components.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.machadothi.blesensor.ble.BoardInfo
import com.machadothi.blesensor.ble.DisplayPage
import com.machadothi.blesensor.ble.DisplayState
import com.machadothi.blesensor.ble.Limits
import com.machadothi.blesensor.ui.components.LabeledSlider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.theme.Lime

private val DisplayPage.icon: ImageVector
    get() = when (this) {
        DisplayPage.TEMPERATURE -> Icons.Rounded.Thermostat
        DisplayPage.HUMIDITY -> Icons.Rounded.WaterDrop
        DisplayPage.LIGHT -> Icons.Rounded.LightMode
        DisplayPage.UV -> Icons.Rounded.WbSunny
        DisplayPage.MAGNETIC -> Icons.Rounded.Explore
        DisplayPage.SOUND -> Icons.Rounded.GraphicEq
        DisplayPage.SUPPLY -> Icons.Rounded.Bolt
        DisplayPage.CHIP_TEMPERATURE -> Icons.Rounded.Memory
        DisplayPage.ORIENTATION -> Icons.Rounded._3dRotation
        DisplayPage.BUTTON -> Icons.Rounded.TouchApp
        DisplayPage.AIR_QUALITY -> Icons.Rounded.Air
        DisplayPage.ECO2 -> Icons.Rounded.Co2
        DisplayPage.TVOC -> Icons.Rounded.Science
        DisplayPage.DEW_POINT -> Icons.Rounded.Opacity
        DisplayPage.SENSOR_DETAILS -> Icons.Rounded.Memory
        DisplayPage.SYSTEM -> Icons.Rounded.DeveloperBoard
    }

// Half seconds up to 10 s, whole seconds above: readable values on the slider.
private fun roundPageMs(ms: Float): Int {
    val step = if (ms < 10_000f) 500 else 1_000
    return (Math.round(ms / step) * step).coerceIn(Limits.displayPageMs)
}

private fun formatSeconds(ms: Int): String = "%.1f s".format(ms / 1000f).replace(".0 s", " s")

/**
 * Which readings the OLED on the board cycles through, and for how long. Each switch takes
 * effect on the display right away (at its next page change) and is stored
 * on the board. Pages this board can't show are greyed out.
 */
@Composable
fun DisplayCard(
    display: DisplayState,
    info: BoardInfo?,
    onPagesChanged: (Set<DisplayPage>) -> Unit,
    onPageMsChanged: (Int) -> Unit,
    onRotatedChanged: (Boolean) -> Unit = {},
) {
    val available = DisplayPage.entries.filter { info == null || it.isAvailableOn(info) }.toSet()
    // Thunderboards list all their pages (absent sensors greyed out); the ESP32 Air board only its own.
    val airPages = setOf(
        DisplayPage.AIR_QUALITY, DisplayPage.ECO2, DisplayPage.TVOC,
        DisplayPage.DEW_POINT, DisplayPage.SENSOR_DETAILS, DisplayPage.SYSTEM,
    )
    val thunderboard = info?.isThunderboard != false
    val listed = DisplayPage.entries.filter { if (thunderboard) it !in airPages else it in available }
    // Follows the slider while dragging; sent to the board when released.
    var pageMs by remember(display.pageMs) { mutableIntStateOf(display.pageMs) }

    GlowCard(Modifier.fillMaxWidth(), accent = Lime) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardHeader(Icons.Rounded.Tv, "Display", Lime, Modifier.weight(1f))
                TextButton(onClick = { onPagesChanged(display.pages + available) }, enabled = display.present) { Text("All") }
                // The ESP32 Air board needs at least one page.
                if (thunderboard) {
                    TextButton(onClick = { onPagesChanged(emptySet()) }, enabled = display.present) { Text("None") }
                }
            }
            AnimatedVisibility(visible = !display.present) {
                Text(
                    if (thunderboard) {
                        "No display found when the board started. Connect an SSD1306 OLED to EXP pins 15 (SCL), " +
                            "16 (SDA), 20 (3.3 V) and 1 (GND), then reboot the board."
                    } else {
                        "No display found when the board started. Check the OLED's wiring (GPIO21 SDA, GPIO22 SCL), then reboot the board."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            // Only boards that can turn the picture from the app (ESP32 Air) send this.
            display.rotated?.let { rotated ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ScreenRotation, null, tint = if (display.present) Lime else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Upside down", style = MaterialTheme.typography.bodyLarge)
                        Text("Turns the picture 180°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppSwitch(checked = rotated, enabled = display.present, onCheckedChange = onRotatedChanged)
                }
            }
            LabeledSlider(
                label = "Each reading for",
                valueText = formatSeconds(pageMs),
                value = pageMs.toFloat(),
                range = Limits.displayPageMs.first.toFloat()..Limits.displayPageMs.last.toFloat(),
                logarithmic = true,
                onChange = { pageMs = roundPageMs(it) },
                onChangeFinished = { onPageMsChanged(pageMs) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                "Shown one at a time, ${display.pages.count { it in available }} selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listed.forEach { page ->
                val canShow = page in available
                val enabled = display.present && canShow
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        page.icon, null,
                        tint = if (enabled) Lime else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            page.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!canShow) {
                            Text("Not on this board", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val lastOne = !thunderboard && display.pages.count { it in available } == 1 && page in display.pages
                    AppSwitch(
                        checked = canShow && page in display.pages,
                        enabled = enabled && !lastOne,
                        onCheckedChange = { on -> onPagesChanged(if (on) display.pages + page else display.pages - page) },
                    )
                }
            }
        }
    }
}
