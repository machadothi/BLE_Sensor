package com.machadothi.blesensor.ui.screen.scan

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.machadothi.blesensor.ble.KnownBoard
import com.machadothi.blesensor.ble.ScannedBoard
import com.machadothi.blesensor.ui.components.SignalBars

@Composable
fun ScanScreen(onBoardSelected: (ScannedBoard) -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val known by viewModel.knownBoards.collectAsStateWithLifecycle()
    val knownAddresses = known.map { it.address }.toSet()
    var hintFor by remember { mutableStateOf<ScannedBoard?>(null) }
    var forgetting by remember { mutableStateOf<KnownBoard?>(null) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.startScan()
    }
    LaunchedEffect(Unit) { viewModel.startScan() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("BLE Sensor", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (state.scanning) "Looking for boards nearby…" else "${state.boards.size} board(s) found",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Radar(
            boards = state.boards,
            scanning = state.scanning,
            modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(20.dp))

        AnimatedContent(targetState = state.bluetoothOff, label = "bt-off", modifier = Modifier.weight(1f)) { off ->
            if (off) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.BluetoothDisabled, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("Turn on Bluetooth")
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (known.isNotEmpty()) {
                        item(key = "known-header") { SectionLabel("Your boards") }
                        items(known, key = { "known-" + it.address }) { k ->
                            val seen = state.boards.firstOrNull { it.address == k.address }
                            val board = seen ?: ScannedBoard(k.address, k.name, 0, k.board, 0)
                            BoardRow(
                                board,
                                status = if (seen != null) null else "Paired · tap to connect",
                                onClick = { viewModel.stopScan(); onBoardSelected(board) },
                                onLongClick = { forgetting = k },
                                showSignal = seen != null,
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item(key = "scan-header") { SectionLabel("Nearby") }
                    }
                    items(state.boards.filter { it.address !in knownAddresses }, key = { it.address }) { board ->
                        // ESP32 Air takes new phones only while its pairing window is open.
                        val waiting = board.pairingOpen == false
                        BoardRow(
                            board,
                            status = when (board.pairingOpen) {
                                true -> "Ready to pair"
                                false -> "Press its reset button twice to pair"
                                null -> null
                            },
                            highlight = board.pairingOpen == true,
                            onClick = { if (waiting) hintFor = board else { viewModel.stopScan(); onBoardSelected(board) } },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
        if (!state.scanning && !state.bluetoothOff) {
            Button(onClick = viewModel::startScan, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Rounded.Radar, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan again")
            }
        }
    }

    hintFor?.let { board ->
        AlertDialog(
            onDismissRequest = { hintFor = null },
            title = { Text("Pair ${board.name}") },
            text = {
                Text(
                    "This board takes new phones only for 2 minutes after its reset button is pressed twice. " +
                        "Press it twice, a second apart, then tap the board again when it shows \"Ready to pair\". " +
                        "Android asks to pair: no code needed."
                )
            },
            confirmButton = { TextButton(onClick = { hintFor = null; viewModel.startScan() }) { Text("Scan again") } },
        )
    }
    forgetting?.let { k ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${k.name}?") },
            text = { Text("This phone forgets the board. To connect again, press the board's reset button twice and pair.") },
            confirmButton = { TextButton(onClick = { viewModel.forget(k.address); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardRow(
    board: ScannedBoard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    highlight: Boolean = false,
    showSignal: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (highlight) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.fillMaxSize()) {}
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(board.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${board.board} · ${board.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showSignal) {
                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(board.rssi)
                    Text("${board.rssi} dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
