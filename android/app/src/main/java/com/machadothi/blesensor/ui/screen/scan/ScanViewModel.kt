package com.machadothi.blesensor.ui.screen.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.machadothi.blesensor.ble.BoardScanner
import com.machadothi.blesensor.ble.ScanFailedException
import com.machadothi.blesensor.ble.ScannedBoard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val boards: List<ScannedBoard> = emptyList(),
    val bluetoothOff: Boolean = false,
    val error: String? = null,
)

private const val SCAN_DURATION_MS = 20_000L  // Android throttles apps that scan nonstop

@HiltViewModel
class ScanViewModel @Inject constructor(private val scanner: BoardScanner) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    fun startScan() {
        if (!scanner.isBluetoothOn) {
            _state.value = ScanUiState(bluetoothOff = true)
            return
        }
        scanJob?.cancel()
        _state.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            val collector = launch {
                scanner.scan()
                    .catch { e ->
                        val message = if (e is ScanFailedException && e.code == 2)
                            "Android is limiting scans. Wait half a minute and try again."
                        else e.message ?: "Scan failed"
                        _state.value = _state.value.copy(scanning = false, error = message)
                    }
                    .collect { boards -> _state.value = _state.value.copy(boards = boards.sortedByDescending { it.rssi }) }
            }
            delay(SCAN_DURATION_MS)
            collector.cancel()
            _state.value = _state.value.copy(scanning = false)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.value = _state.value.copy(scanning = false)
    }
}
