package com.machadothi.blesensor.ui.screen.board.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
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
import com.machadothi.blesensor.ble.ButtonState
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

    val available = info?.available ?: emptySet()
    val enabled = config?.sensors ?: emptySet()
    fun shows(sensor: Sensor) = sensor in available
    fun offNote(sensor: Sensor) = if (sensor !in enabled) "Off in settings" else null

    val e = env
    val metrics = buildList {
        if (shows(Sensor.RHT)) {
            add(Metric("Temperature", Icons.Rounded.Thermostat, Coral, "°C", e?.temperatureC, { "%.1f".format(it) }, history.temperatureC, note = offNote(Sensor.RHT)))
            add(Metric("Humidity", Icons.Rounded.WaterDrop, Sky, "%", e?.humidityPct, { "%.1f".format(it) }, history.humidityPct, note = offNote(Sensor.RHT)))
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
        add(Metric("Chip temperature", Icons.Rounded.Memory, Coral, "°C", e?.dieTemperatureC, { "%.1f".format(it) }))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { ButtonCard(button) }
        items(metrics, key = { it.title }) { MetricCard(it, Modifier.animateItem()) }
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
