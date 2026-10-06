package com.machadothi.blesensor.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

/**
 * One GATT connection to a board. Nordic's BleManager queues every GATT
 * operation (Android allows only one at a time) and turns callbacks into
 * suspend functions. Notifications come out as flows.
 */
class BoardConnection(context: Context) : BleManager(context) {

    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    private fun notifications() = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val envFlow = notifications()
    private val motionFlow = notifications()
    private val buttonFlow = notifications()
    private val airFlow = notifications()
    private val systemFlow = notifications()
    private val calibrationFlow = notifications()
    private val wifiFlow = notifications()
    private val updateFlow = notifications()

    val envNotifications: SharedFlow<ByteArray> = envFlow
    val motionNotifications: SharedFlow<ByteArray> = motionFlow
    val buttonNotifications: SharedFlow<ByteArray> = buttonFlow
    val airNotifications: SharedFlow<ByteArray> = airFlow
    val systemNotifications: SharedFlow<ByteArray> = systemFlow
    val calibrationNotifications: SharedFlow<ByteArray> = calibrationFlow
    val wifiNotifications: SharedFlow<ByteArray> = wifiFlow
    val updateNotifications: SharedFlow<ByteArray> = updateFlow

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(Protocol.SERVICE) ?: return false
        // Every board has these.
        listOf(Protocol.ENV, Protocol.INFO, Protocol.COMMAND, Protocol.NAME).forEach { uuid ->
            characteristics[uuid] = service.getCharacteristic(uuid) ?: return false
        }
        // Thunderboard only (motion, button, LED, config), OLED support, air quality (ESP32 Air).
        listOf(Protocol.MOTION, Protocol.BUTTON, Protocol.LED, Protocol.CONFIG, Protocol.DISPLAY, Protocol.AIR, Protocol.SYSTEM, Protocol.CALIBRATION, Protocol.TIME, Protocol.WIFI, Protocol.WEATHER, Protocol.UPDATE).forEach { uuid ->
            service.getCharacteristic(uuid)?.let { characteristics[uuid] = it }
        }
        return true
    }

    // Runs once after service discovery, before the connection counts as ready.
    override fun initialize() {
        requestMtu(247).enqueue()                       // packets are up to 27 bytes
        requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
        subscribe(Protocol.ENV, envFlow)
        subscribe(Protocol.MOTION, motionFlow)
        subscribe(Protocol.BUTTON, buttonFlow)
        subscribe(Protocol.AIR, airFlow)
        subscribe(Protocol.SYSTEM, systemFlow)
        subscribe(Protocol.CALIBRATION, calibrationFlow)
        subscribe(Protocol.WIFI, wifiFlow)
        subscribe(Protocol.UPDATE, updateFlow)
    }

    private fun subscribe(uuid: UUID, flow: MutableSharedFlow<ByteArray>) {
        val characteristic = characteristics[uuid] ?: return   // this board doesn't have it
        setNotificationCallback(characteristic).with { _, data -> data.value?.let { flow.tryEmit(it) } }
        enableNotifications(characteristic).enqueue()
    }

    override fun onServicesInvalidated() {
        characteristics.clear()
    }

    suspend fun connectTo(device: BluetoothDevice) {
        connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .timeout(15_000)
            .suspend()
    }

    suspend fun disconnectNow() {
        disconnect().suspend()
    }

    fun has(uuid: UUID): Boolean = uuid in characteristics

    suspend fun read(uuid: UUID): ByteArray =
        readCharacteristic(characteristics[uuid]).suspend().value ?: ByteArray(0)

    /** Write with response: the board's validation errors come back as exceptions. */
    suspend fun write(uuid: UUID, value: ByteArray) {
        writeCharacteristic(characteristics[uuid], value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
    }

    suspend fun readSignalStrength(): Int = readRssi().suspend()

    override fun log(priority: Int, message: String) {
        if (priority >= Log.WARN) Log.println(priority, "BoardConnection", message)
    }
}
