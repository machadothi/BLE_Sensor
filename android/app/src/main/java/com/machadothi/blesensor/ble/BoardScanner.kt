package com.machadothi.blesensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A board seen while scanning. */
data class ScannedBoard(
    val address: String,
    val name: String,
    val rssi: Int,
    val board: String,
    val lastSeenMs: Long,
)

class ScanFailedException(val code: Int) : Exception("Bluetooth scan failed (code $code)")

/** Finds advertising BLE Sensor boards (filtered on the service UUID). */
@Singleton
class BoardScanner @Inject constructor(@ApplicationContext private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Emits the boards found so far every time one is seen. Scans until cancelled. */
    @SuppressLint("MissingPermission") // checked by the permissions screen
    fun scan(): Flow<List<ScannedBoard>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw ScanFailedException(-1)
        val boards = linkedMapOf<String, ScannedBoard>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val manufacturer = record?.getManufacturerSpecificData(Protocol.ADV_COMPANY_ID)
                val board = manufacturer?.firstOrNull()?.let { Protocol.boardName(it.toInt() and 0xFF) } ?: "Unknown"
                boards[result.device.address] = ScannedBoard(
                    address = result.device.address,
                    name = record?.deviceName ?: result.device.name ?: "BLE Sensor",
                    rssi = result.rssi,
                    board = board,
                    lastSeenMs = SystemClock.elapsedRealtime(),
                )
                trySend(boards.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
