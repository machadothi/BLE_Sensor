package com.machadothi.blesensor.ui.screen.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.machadothi.blesensor.ble.ScannedBoard
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radar with a rotating sweep. Each board is a blip: its angle comes from its
 * address (stable between scans), its distance from the center from its RSSI
 * (stronger signal = closer).
 */
@Composable
fun Radar(boards: List<ScannedBoard>, scanning: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
    val blipPulse by transition.animateFloat(0.7f, 1.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "blip")

    Canvas(modifier) {
        val radius = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)

        // Range rings and cross-hair
        for (i in 1..4) drawCircle(grid.copy(alpha = 0.6f), radius * i / 4, center, style = Stroke(1.dp.toPx()))
        drawLine(grid.copy(alpha = 0.4f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
        drawLine(grid.copy(alpha = 0.4f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))

        // Sweep: a cone that fades behind the leading edge
        if (scanning) {
            rotate(sweep, center) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1f to primary.copy(alpha = 0.45f),
                        center = center,
                    ),
                    radius,
                    center,
                )
                drawLine(primary, center, Offset(center.x + radius, center.y), strokeWidth = 2.dp.toPx())
            }
        }

        // Blips
        boards.forEach { board ->
            val angle = Math.toRadians(((board.address.hashCode() and 0x7FFFFFFF) % 360).toDouble())
            val strength = ((board.rssi + 100) / 60f).coerceIn(0f, 1f)        // -100..-40 dBm → 0..1
            val distance = radius * (0.9f - 0.7f * strength)
            val p = Offset(center.x + (cos(angle) * distance).toFloat(), center.y + (sin(angle) * distance).toFloat())
            drawCircle(primary.copy(alpha = 0.25f), 14.dp.toPx() * blipPulse, p)
            drawCircle(primary, 5.dp.toPx(), p)
        }
        drawCircle(primary, 3.dp.toPx(), center)
    }
}
