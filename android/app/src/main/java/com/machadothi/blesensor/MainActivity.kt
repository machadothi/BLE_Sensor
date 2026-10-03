package com.machadothi.blesensor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.machadothi.blesensor.ui.navigation.AppNavHost
import com.machadothi.blesensor.ui.screen.permissions.hasBluetoothPermissions
import com.machadothi.blesensor.ui.theme.BleSensorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark UI: light status/navigation bar icons on a transparent bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val needsPermissions = !hasBluetoothPermissions(this)
        setContent {
            BleSensorTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppNavHost(startWithPermissions = needsPermissions)
                }
            }
        }
    }
}
