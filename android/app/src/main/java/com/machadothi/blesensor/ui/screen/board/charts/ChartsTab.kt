package com.machadothi.blesensor.ui.screen.board.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.screen.board.BoardViewModel
import com.machadothi.blesensor.ui.theme.Amber
import com.machadothi.blesensor.ui.theme.Coral
import com.machadothi.blesensor.ui.theme.Lime
import com.machadothi.blesensor.ui.theme.Sky
import com.machadothi.blesensor.ui.theme.Teal
import com.machadothi.blesensor.ui.theme.Violet

private data class Series(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val unit: String,
    val values: List<Float>,
    val format: String,
    val periodMs: Int,
)

@Composable
fun ChartsTab(viewModel: BoardViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val envMs = config?.envPeriodMs ?: 1000
    val motionMs = config?.motionPeriodMs ?: 50

    val series = listOf(
        Series("Temperature", Icons.Rounded.Thermostat, Coral, "°C", history.temperatureC, "%.2f", envMs),
        Series("Humidity", Icons.Rounded.WaterDrop, Sky, "%", history.humidityPct, "%.1f", envMs),
        Series("Light", Icons.Rounded.LightMode, Amber, "lx", history.lux, "%.0f", envMs),
        Series("Magnetic field", Icons.Rounded.Explore, Teal, "mT", history.hallMt, "%.3f", envMs),
        Series("Acceleration |a|", Icons.Rounded.Speed, Violet, "g", history.accelG, "%.3f", motionMs),
        Series("Rotation |ω|", Icons.Rounded._3dRotation, Lime, "°/s", history.gyroDps, "%.1f", motionMs),
    ).filter { it.values.isNotEmpty() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (series.isEmpty()) {
            item { Text("Collecting data…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(series, key = { it.title }) { s -> ChartCard(s) }
    }
}

@Composable
private fun ChartCard(series: Series) {
    GlowCard(Modifier.fillMaxWidth(), accent = series.color) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardHeader(series.icon, series.title, series.color, Modifier.weight(1f))
                Text(
                    "${series.format.format(series.values.last())} ${series.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = series.color,
                )
            }
            Spacer(Modifier.height(10.dp))
            LineChart(series.values, series.color, series.format, Modifier.fillMaxWidth().height(140.dp))
            val seconds = series.values.size * series.periodMs / 1000f
            Text(
                "last %.0f s".format(seconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Line chart with dashed min/max guides and labels. */
@Composable
private fun LineChart(values: List<Float>, color: Color, format: String, modifier: Modifier = Modifier) {
    val guide = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minV = values.min()
    val maxV = values.max()
    Row(modifier) {
        Column(Modifier.fillMaxSize().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                if (values.size < 2) return@Canvas
                val span = (maxV - minV).takeIf { it > 1e-6f } ?: 1f
                val top = size.height * 0.08f
                val usable = size.height * 0.84f
                fun y(v: Float) = top + usable - (v - minV) / span * usable
                val stepX = size.width / (values.size - 1)

                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                drawLine(guide, Offset(0f, y(maxV)), Offset(size.width, y(maxV)), pathEffect = dash)
                drawLine(guide, Offset(0f, y(minV)), Offset(size.width, y(minV)), pathEffect = dash)

                val line = Path()
                values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * stepX, y(v)) }
                val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
                drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val last = Offset(size.width, y(values.last()))
                drawCircle(color.copy(alpha = 0.3f), 8.dp.toPx(), last)
                drawCircle(color, 4.dp.toPx(), last)
            }
        }
        Column(Modifier.fillMaxSize().weight(0.18f), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text(format.format(maxV), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(format.format(minV), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}
