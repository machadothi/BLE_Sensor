package com.machadothi.blesensor.ui.screen.board

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.Sensor
import com.machadothi.blesensor.repository.ConnectionStatus
import com.machadothi.blesensor.ui.components.SignalBars
import com.machadothi.blesensor.ui.screen.board.charts.ChartsTab
import com.machadothi.blesensor.ui.screen.board.control.ControlTab
import com.machadothi.blesensor.ui.screen.board.live.LiveTab
import com.machadothi.blesensor.ui.screen.board.motion.MotionTab
import com.machadothi.blesensor.ui.screen.board.settings.SettingsTab

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.Dashboard),
    MOTION("Motion", Icons.Rounded._3dRotation),
    CHARTS("Charts", Icons.AutoMirrored.Rounded.ShowChart),
    CONTROL("Control", Icons.Rounded.Lightbulb),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(onBack: () -> Unit, viewModel: BoardViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val rssi by viewModel.rssi.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    // The Motion tab only for boards with an IMU (not the ESP32 Air board).
    val tabs = Tab.entries.filter { it != Tab.MOTION || info?.available?.contains(Sensor.IMU) != false }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(name ?: viewModel.boardName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            info?.board ?: statusLabel(status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (status is ConnectionStatus.Connected) {
                        SignalBars(rssi)
                        Spacer(Modifier.width(4.dp))
                        Text("${rssi ?: "–"}", style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.LinkOff, "Disconnect") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (status is ConnectionStatus.Connected) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(targetState = status::class, label = "status") { kind ->
                when (kind) {
                    ConnectionStatus.Connected::class -> TabContent(tab, viewModel)
                    ConnectionStatus.Failed::class, ConnectionStatus.Lost::class ->
                        ProblemView(statusLabel(status), onRetry = viewModel::connect, onBack = onBack)
                    else -> ConnectingView(viewModel.boardName)
                }
            }
        }
    }
}

@Composable
private fun TabContent(tab: Tab, viewModel: BoardViewModel) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(tween(300)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(300)))
                .togetherWith(slideOutHorizontally(tween(300)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
        },
        label = "tab",
    ) { t ->
        when (t) {
            Tab.LIVE -> LiveTab(viewModel)
            Tab.MOTION -> MotionTab(viewModel)
            Tab.CHARTS -> ChartsTab(viewModel)
            Tab.CONTROL -> ControlTab(viewModel)
            Tab.SETTINGS -> SettingsTab(viewModel)
        }
    }
}

private fun statusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Idle -> "Disconnected"
    is ConnectionStatus.Connecting -> "Connecting…"
    is ConnectionStatus.Connected -> "Connected"
    is ConnectionStatus.Failed -> "Couldn't connect: ${status.message}"
    is ConnectionStatus.Lost -> status.message
}

@Composable
private fun ConnectingView(name: String) {
    val pulse by rememberInfiniteTransition(label = "connecting")
        .animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp).scale(pulse))
        Spacer(Modifier.height(20.dp))
        Text("Connecting to $name", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProblemView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = onRetry) { Text("Reconnect") }
        }
    }
}
