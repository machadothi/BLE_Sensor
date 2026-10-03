package com.machadothi.blesensor.ui.screen.board.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.Sensor
import com.machadothi.blesensor.ble.Vec3
import com.machadothi.blesensor.ui.components.CardHeader
import com.machadothi.blesensor.ui.components.ConfirmDialog
import com.machadothi.blesensor.ui.components.GlowCard
import com.machadothi.blesensor.ui.screen.board.BoardViewModel
import com.machadothi.blesensor.ui.theme.Amber
import com.machadothi.blesensor.ui.theme.Coral
import com.machadothi.blesensor.ui.theme.Lime
import com.machadothi.blesensor.ui.theme.Sky
import com.machadothi.blesensor.ui.theme.Teal
import kotlin.math.abs

private val AxisColors = listOf(Coral, Lime, Sky)   // X, Y, Z

@Composable
fun MotionTab(viewModel: BoardViewModel) {
    val motion by viewModel.motion.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    var confirmCalibrate by remember { mutableStateOf(false) }
    val imuOff = config?.sensors?.contains(Sensor.IMU) == false

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlowCard(Modifier.fillMaxWidth(), accent = Teal) {
            Column {
                CardHeader(Icons.Rounded._3dRotation, "Orientation", Teal)
                TiltBoard(motion?.orientationDeg, Modifier.fillMaxWidth().aspectRatio(1.3f))
                val o = motion?.orientationDeg
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Angle("Roll", o?.x, AxisColors[0])
                    Angle("Pitch", o?.y, AxisColors[1])
                    Angle("Yaw", o?.z, AxisColors[2])
                }
                if (imuOff) {
                    Text("The IMU is off. Turn it on in Settings.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        GlowCard(Modifier.fillMaxWidth(), accent = Amber) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardHeader(Icons.Rounded.Speed, "Acceleration (g)", Amber)
                AxisBars(motion?.accelG, range = 2f, format = "%+.2f")
                Spacer(Modifier.height(8.dp))
                CardHeader(Icons.Rounded._3dRotation, "Rotation rate (°/s)", Amber)
                AxisBars(motion?.gyroDps, range = 250f, format = "%+.0f")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { confirmCalibrate = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Speed, null)
                Spacer(Modifier.width(6.dp))
                Text("Calibrate gyro")
            }
            FilledTonalButton(onClick = { viewModel.send(Command.RESET_ORIENTATION) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.CenterFocusStrong, null)
                Spacer(Modifier.width(6.dp))
                Text("Zero angles")
            }
        }
    }

    if (confirmCalibrate) {
        ConfirmDialog(
            title = "Calibrate the gyroscope",
            text = "Put the board on a table and don't touch it. Calibration takes about a second.",
            confirmLabel = "Calibrate",
            onConfirm = { viewModel.send(Command.CALIBRATE_GYRO) },
            onDismiss = { confirmCalibrate = false },
        )
    }
}

@Composable
private fun Angle(label: String, value: Float?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value?.let { "%+.0f°".format(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
    }
}

/** A drawing of the Thunderboard that rotates in 3D with the board's orientation. */
@Composable
private fun TiltBoard(orientation: Vec3?, modifier: Modifier = Modifier) {
    val springSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
    val roll by animateFloatAsState(orientation?.x ?: 0f, springSpec, label = "roll")
    val pitch by animateFloatAsState(orientation?.y ?: 0f, springSpec, label = "pitch")
    val yaw by animateFloatAsState(orientation?.z ?: 0f, springSpec, label = "yaw")
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        // Floor shadow: shrinks as the board tilts away from flat.
        val tilt = (abs(roll) + abs(pitch)).coerceAtMost(120f) / 120f
        Canvas(Modifier.fillMaxSize()) {
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)),
                topLeft = Offset(size.width * (0.2f + 0.1f * tilt), size.height * 0.78f),
                size = Size(size.width * (0.6f - 0.2f * tilt), size.height * 0.12f),
            )
        }
        Canvas(
            Modifier
                .fillMaxHeight(0.72f)
                .aspectRatio(0.62f)
                .graphicsLayer {
                    rotationX = -pitch
                    rotationY = roll
                    rotationZ = -yaw
                    cameraDistance = 14f * density
                },
        ) {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(w * 0.08f)
            // PCB
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF1C2B4D), Color(0xFF0F1A33))), cornerRadius = corner)
            drawRoundRect(accent.copy(alpha = 0.6f), cornerRadius = corner, style = Stroke(2.dp.toPx()))
            // EFR32 chip
            val chip = w * 0.34f
            drawRoundRect(Color(0xFF0A0F1C), Offset((w - chip) / 2, h * 0.38f), Size(chip, chip), CornerRadius(4.dp.toPx()))
            drawRoundRect(accent.copy(alpha = 0.5f), Offset((w - chip) / 2, h * 0.38f), Size(chip, chip), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            // USB connector at the top
            drawRoundRect(Color(0xFF8C9AB8), Offset(w * 0.38f, -h * 0.02f), Size(w * 0.24f, h * 0.07f), CornerRadius(3.dp.toPx()))
            // LED and button
            drawCircle(Amber, w * 0.05f, Offset(w * 0.25f, h * 0.82f))
            drawCircle(Color(0xFF3A4A6E), w * 0.08f, Offset(w * 0.72f, h * 0.82f))
            // Antenna trace
            drawLine(accent.copy(alpha = 0.7f), Offset(w * 0.15f, h * 0.15f), Offset(w * 0.85f, h * 0.15f), strokeWidth = 3.dp.toPx())
        }
    }
}

/** Three centered bars (X, Y, Z) that grow left or right with the sign of the value. */
@Composable
private fun AxisBars(values: Vec3?, range: Float, format: String) {
    val axes = listOf("X" to values?.x, "Y" to values?.y, "Z" to values?.z)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    axes.forEachIndexed { i, (label, value) ->
        val animated by animateFloatAsState((value ?: 0f) / range, spring(stiffness = Spring.StiffnessMedium), label = label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = AxisColors[i], modifier = Modifier.width(20.dp))
            Canvas(Modifier.weight(1f).height(10.dp)) {
                val mid = size.width / 2
                val fraction = animated.coerceIn(-1f, 1f)
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val start = if (fraction >= 0) mid else mid + fraction * mid
                drawRoundRect(AxisColors[i], Offset(start, 0f), Size(abs(fraction) * mid, size.height), CornerRadius(size.height / 2))
                drawLine(Color.White.copy(alpha = 0.4f), Offset(mid, 0f), Offset(mid, size.height))
            }
            Text(
                value?.let { format.format(it) } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(56.dp).padding(start = 8.dp),
            )
        }
    }
}
