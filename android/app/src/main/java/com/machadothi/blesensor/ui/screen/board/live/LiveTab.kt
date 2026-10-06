package com.machadothi.blesensor.ui.screen.board.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Co2
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.AirReading
import com.machadothi.blesensor.ble.AirState
import com.machadothi.blesensor.ble.ButtonState
import com.machadothi.blesensor.ble.Derived
import com.machadothi.blesensor.ble.Env
import com.machadothi.blesensor.ble.SystemInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.machadothi.blesensor.ble.Sensor
import com.machadothi.blesensor.ui.components.AnimatedNumber
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.components.Sparkline
import com.machadothi.blesensor.ui.screen.board.BoardViewModel
import com.machadothi.blesensor.ui.theme.Amber
import com.machadothi.blesensor.ui.theme.Coral
import com.machadothi.blesensor.ui.theme.Lime
import com.machadothi.blesensor.ui.theme.Sky
import com.machadothi.blesensor.ui.theme.Teal
import com.machadothi.blesensor.ui.theme.Violet

/** One sensor card: what it shows and where its numbers come from. */
private data class Metric(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val unit: String,
    val value: Float?,
    val format: (Float) -> String,
    val trend: List<Float> = emptyList(),
    val alert: Boolean = false,
    val note: String? = null,
)

@Composable
fun LiveTab(viewModel: BoardViewModel) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val button by viewModel.button.collectAsStateWithLifecycle()
    val air by viewModel.air.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()

    val available = info?.available ?: emptySet()
    // Boards without a Config characteristic (ESP32 Air) always run all their sensors.
    val enabled = config?.sensors
    fun shows(sensor: Sensor) = sensor in available
    fun offNote(sensor: Sensor) = if (enabled != null && sensor !in enabled) "Off in settings" else null

    val e = env
    val metrics = buildList {
        if (shows(Sensor.RHT)) {
            add(Metric("Temperature", Icons.Rounded.Thermostat, Coral, "°C", e?.temperatureC, { "%.1f".format(it) }, history.temperatureC, note = offNote(Sensor.RHT)))
            add(Metric("Humidity", Icons.Rounded.WaterDrop, Sky, "%", e?.humidityPct, { "%.1f".format(it) }, history.humidityPct, note = offNote(Sensor.RHT)))
            val dew = Derived.dewPoint(e?.temperatureC, e?.humidityPct)
            add(Metric("Dew point", Icons.Rounded.Opacity, Teal, "°C", dew, { "%.1f".format(it) }, note = Derived.comfort(dew)))
            add(Metric("Absolute humidity", Icons.Rounded.Water, Sky, "g/m³", Derived.absoluteHumidity(e?.temperatureC, e?.humidityPct), { "%.1f".format(it) }))
        }
        if (shows(Sensor.LIGHT)) {
            add(Metric("Light", Icons.Rounded.LightMode, Amber, "lx", e?.lux, { "%.0f".format(it) }, history.lux, note = offNote(Sensor.LIGHT)))
            if (info?.board == "BRD4184A") {
                add(Metric("UV index", Icons.Rounded.WbSunny, Violet, "", e?.uvIndex, { "%.2f".format(it) }, note = offNote(Sensor.LIGHT)))
            }
        }
        if (shows(Sensor.HALL)) {
            val note = when {
                e?.hallTamper == true -> "Tamper level!"
                e?.hallAlert == true -> "Magnet detected"
                else -> offNote(Sensor.HALL)
            }
            add(Metric("Magnetic field", Icons.Rounded.Explore, Teal, "mT", e?.hallMt, { "%.2f".format(it) }, history.hallMt, alert = e?.hallAlert == true, note = note))
        }
        if (shows(Sensor.SOUND)) {
            add(Metric("Sound", Icons.Rounded.GraphicEq, Lime, "dB", e?.soundDb, { "%.0f".format(it) }, note = offNote(Sensor.SOUND)))
        }
        if (shows(Sensor.SUPPLY)) {
            add(Metric("Supply", Icons.Rounded.Bolt, Amber, "V", e?.supplyV, { "%.2f".format(it) }, note = offNote(Sensor.SUPPLY)))
        }
        if (shows(Sensor.AIR)) {
            val a = air
            val waiting = a?.state?.takeIf { it != AirState.NORMAL }?.label
            add(Metric("eCO2", Icons.Rounded.Co2, Sky, "ppm", a?.eco2Ppm?.toFloat(), { "%.0f".format(it) }, history.eco2Ppm,
                note = waiting ?: Derived.eco2Rating(a?.eco2Ppm)))
            val ugm3 = Derived.tvocUgm3(a?.tvocPpb, e?.temperatureC)
            add(Metric("TVOC", Icons.Rounded.Science, Violet, "ppb", a?.tvocPpb?.toFloat(), { "%.0f".format(it) }, history.tvocPpb,
                note = waiting ?: ugm3?.let { "≈ %.0f µg/m³ (ethanol)".format(it) }))
        }
        if (info?.isThunderboard == true) {
            add(Metric("Chip temperature", Icons.Rounded.Memory, Coral, "°C", e?.dieTemperatureC, { "%.1f".format(it) }))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shows(Sensor.AIR)) {
            item(span = { GridItemSpan(maxLineSpan) }) { AirQualityCard(air) }
        }
        if (button != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { ButtonCard(button) }
        }
        items(metrics, key = { it.title }) { MetricCard(it, Modifier.animateItem()) }
        if (shows(Sensor.AIR)) {
            item(span = { GridItemSpan(maxLineSpan) }) { SensorDetailsCard(air, e, system) }
        }
        system?.let { info -> item(span = { GridItemSpan(maxLineSpan) }) { BoardStatusCard(info) } }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    // An active alert makes the card's border breathe.
    val pulse by rememberInfiniteTransition(label = "alert")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    GlowCard(modifier = modifier.fillMaxWidth(), accent = metric.accent, highlighted = metric.alert && pulse > 0.5f) {
        Column {
            CardHeader(metric.icon, metric.title, metric.accent)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(metric.value, metric.format)
                if (metric.unit.isNotEmpty()) {
                    Text(
                        " ${metric.unit}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            if (metric.note != null) {
                Text(metric.note, style = MaterialTheme.typography.labelSmall, color = if (metric.alert) metric.accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (metric.trend.size > 1) {
                Spacer(Modifier.height(8.dp))
                Sparkline(metric.trend, metric.accent, Modifier.fillMaxWidth().height(36.dp))
            }
        }
    }
}

@Composable
private fun ButtonCard(button: ButtonState?) {
    val ring = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    // Every new press sends a ring out from the dot and pops the counter.
    LaunchedEffect(button?.pressCount) {
        if (button == null || button.pressCount == 0L) return@LaunchedEffect
        ring.snapTo(0f)
        pop.snapTo(1.25f)
        coroutineScope {
            launch { pop.animateTo(1f, tween(350)) }
            ring.animateTo(1f, tween(700))
        }
    }
    val accent = Amber
    GlowCard(Modifier.fillMaxWidth(), accent = accent, highlighted = button?.pressed == true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(accent.copy(alpha = (1 - ring.value) * 0.8f), radius = r * (0.4f + 0.6f * ring.value), style = Stroke(3.dp.toPx()))
                    drawCircle(if (button?.pressed == true) accent else accent.copy(alpha = 0.25f), radius = r * 0.38f)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                CardHeader(Icons.Rounded.TouchApp, "Button", accent)
                Text(
                    if (button?.pressed == true) "Pressed" else "Press BTN0 on the board",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${button?.pressCount ?: 0}", style = MaterialTheme.typography.displaySmall, modifier = Modifier.scale(pop.value))
                Text("presses", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** ENS160 rating (UBA scale 1-5) as a word and a five-segment bar; the current segment glows. */
@Composable
private fun AirQualityCard(air: AirReading?) {
    val labels = listOf("Excellent", "Good", "Moderate", "Poor", "Unhealthy")
    val colors = listOf(Teal, Lime, Amber, Coral, Violet)
    val index = air?.aqi?.minus(1)
    val accent by animateColorAsState(index?.let { colors[it] } ?: MaterialTheme.colorScheme.outline, tween(600), label = "aqi")
    GlowCard(Modifier.fillMaxWidth(), accent = accent, highlighted = index != null && index >= 3) {
        Column {
            CardHeader(Icons.Rounded.Air, "Air quality", accent)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    air == null -> "—"
                    index == null -> air.state.label
                    else -> labels[index]
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                when {
                    air == null -> "Waiting for the sensor"
                    air.state == AirState.WARM_UP -> "The ENS160 needs about 3 minutes after power-up"
                    air.state == AirState.START_UP -> "A new ENS160's first hour of operation"
                    index == null -> ""
                    index >= 3 -> "Open a window"
                    index == 2 -> "Ventilate soon"
                    else -> "Index ${index + 1} of 5"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                colors.forEachIndexed { i, color ->
                    val alpha by animateFloatAsState(if (i == index) 1f else 0.22f, tween(500), label = "segment")
                    Box(Modifier.weight(1f).height(8.dp).background(color.copy(alpha = alpha), RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** Everything the two sensors report beyond the main readings (datasheet details). */
@Composable
private fun SensorDetailsCard(air: AirReading?, env: Env?, system: SystemInfo?) {
    GlowCard(Modifier.fillMaxWidth(), accent = Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader(Icons.Rounded.Memory, "Air sensor details", Violet)
            DetailSection("ENS160 · gas sensor")
            DetailRow("State", air?.state?.label ?: "—")
            DetailRow("Firmware", air?.firmware ?: "—")
            DetailRow("Status register", air?.statusRegister?.let { "0x%02X".format(it) } ?: "—")
            DetailRow("Resistance R1", air?.r1Ohms?.let(::formatOhms) ?: "—")
            DetailRow("Resistance R4", air?.r4Ohms?.let(::formatOhms) ?: "—")
            DetailRow(
                "Compensation in use",
                if (air?.compensationC == null) "—" else "%.1f °C · %.1f %%".format(air.compensationC, air.compensationPct),
            )
            DetailRow("Checksum errors", system?.integrityErrors?.toString() ?: "—")
            DetailSection("AHT21 · temperature & humidity")
            DetailRow("Accuracy (datasheet)", "±0.3 °C · ±2 %RH typical")
            DetailRow("Time above 80 %RH", system?.humidSeconds?.let(::formatDuration) ?: "—")
            if ((system?.humidSeconds ?: 0) > 60 * 60) {
                Text(
                    "Long stays above 80 %RH can make humidity read up to 3 % high until the sensor recovers.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
            }
            DetailRow("Sensor errors", system?.sensorErrors?.toString() ?: "—")
            Text(
                "R1/R4 are the raw metal-oxide resistances; they swing widely by design. " +
                    "Compensation should match the temperature and humidity above.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The ESP32 itself: network, memory, chip temperature, firmware. */
@Composable
private fun BoardStatusCard(info: SystemInfo) {
    GlowCard(Modifier.fillMaxWidth(), accent = Sky) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader(Icons.Rounded.DeveloperBoard, "Board", Sky)
            DetailRow("Wi-Fi", if (info.wifi) "${info.wifiRssi ?: "—"} dBm · ${info.ip ?: "—"}" else "offline")
            DetailRow("MQTT", if (info.mqtt) "connected" else "offline")
            DetailRow("Uptime", formatDuration(info.uptimeS))
            DetailRow("Last reset", info.resetCause)
            DetailRow("Chip temperature", info.chipTemperatureC?.let { "%.1f °C".format(it) } ?: "—")
            DetailRow("Free memory", "%.0f KB".format(info.freeRam / 1024f))
            DetailRow("CPU", "${info.cpuMhz} MHz")
            DetailRow("MicroPython", info.micropython)
        }
    }
}

@Composable
private fun DetailSection(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatOhms(ohms: Double): String = when {
    ohms >= 1e6 -> "%.2f MΩ".format(ohms / 1e6)
    ohms >= 1e3 -> "%.1f kΩ".format(ohms / 1e3)
    else -> "%.0f Ω".format(ohms)
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 86_400} d ${seconds % 86_400 / 3600} h"
}
