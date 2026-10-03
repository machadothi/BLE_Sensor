package com.machadothi.blesensor.ui.screen.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** The runtime permissions BLE needs on this Android version. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)  // Android < 12 ties BLE scans to location
    }

fun hasBluetoothPermissions(context: Context): Boolean = bluetoothPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun PermissionsScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onGranted() else denied = true
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingBluetooth()
        Spacer(Modifier.height(32.dp))
        Text("Find your board", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "BLE Sensor needs the Nearby devices permission to find and talk to your Thunderboard. It never uses your location."
            else
                "Android needs the Location permission for Bluetooth scanning on this version. BLE Sensor never uses your location.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { launcher.launch(bluetoothPermissions()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Allow")
        }
        if (denied) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
        }
    }
}

@Composable
private fun PulsingBluetooth() {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "pulse")
    val wave by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "wave")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..2) {
                val t = (wave + i / 3f) % 1f
                drawCircle(primary.copy(alpha = (1 - t) * 0.5f), radius = size.minDimension / 2 * t, style = Stroke(2.dp.toPx()))
            }
        }
        Icon(Icons.Rounded.Bluetooth, null, tint = primary, modifier = Modifier.size(56.dp))
    }
}
