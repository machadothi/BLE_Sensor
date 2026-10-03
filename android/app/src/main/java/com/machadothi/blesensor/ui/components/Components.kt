package com.machadothi.blesensor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.machadothi.blesensor.ui.theme.NumberStyle

/** Card with a soft accent glow along its border; the glow brightens when [highlighted]. */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val glow by animateFloatAsState(if (highlighted) 0.9f else 0.25f, tween(400), label = "glow")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = glow), accent.copy(alpha = 0.04f)))),
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

/** Number that glides to each new value instead of jumping. */
@Composable
fun AnimatedNumber(
    value: Float?,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val animated by animateFloatAsState(value ?: 0f, tween(450, easing = FastOutSlowInEasing), label = "number")
    Text(
        text = if (value == null) "—" else format(animated),
        style = NumberStyle,
        color = color,
        modifier = modifier,
        maxLines = 1,
    )
}

/** Small line chart with a gradient fill, for trends inside cards. */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1e-6f } ?: 1f
        val stepX = size.width / (values.size - 1)
        fun y(v: Float) = size.height - (v - min) / span * size.height * 0.9f - size.height * 0.05f

        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, y(values.last())))
    }
}

/** Four bars showing signal strength from an RSSI in dBm. */
@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val level = when {
        rssi == null -> 0
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val muted = MaterialTheme.colorScheme.outline
    Row(modifier.height(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            val barColor by animateColorAsState(if (i <= level) color else muted, label = "bar")
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 * i).dp)
                    .drawBehind { drawRoundRect(barColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)) },
            )
        }
    }
}

/** A glowing orb that mimics the board's LED: off, steady, or blinking with the given timing. */
@Composable
fun LedOrb(lit: Boolean, modifier: Modifier = Modifier, size: Dp = 96.dp, color: Color = Color(0xFFFFC24B)) {
    val brightness by animateFloatAsState(if (lit) 1f else 0f, tween(120), label = "led")
    val halo = rememberInfiniteTransition(label = "halo")
    val breathe by halo.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.55f * brightness), Color.Transparent), radius = r * breathe),
            radius = r * breathe,
        )
        drawCircle(Color(0xFF2A3350), radius = r * 0.38f)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = brightness), color.copy(alpha = brightness), color.copy(alpha = 0.15f + 0.85f * brightness)), radius = r * 0.36f),
            radius = r * 0.36f,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** Icon + label + value row used inside cards. */
@Composable
fun CardHeader(icon: ImageVector, title: String, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun Labeled(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
