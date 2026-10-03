package com.machadothi.blesensor.repository

import android.bluetooth.BluetoothManager
import android.content.Context
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.BoardConnection
import com.machadothi.blesensor.ble.BoardInfo
import com.machadothi.blesensor.ble.ButtonState
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.Env
import com.machadothi.blesensor.ble.LedState
import com.machadothi.blesensor.ble.Motion
import com.machadothi.blesensor.ble.Protocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.observer.ConnectionObserver
import android.bluetooth.BluetoothDevice
import javax.inject.Inject
import javax.inject.Singleton

private const val ENV_HISTORY = 240        // samples kept per environmental chart
private const val MOTION_HISTORY = 400     // samples kept per motion chart
private const val HISTORY_PUBLISH_MS = 66L // charts redraw at most ~15 times a second
private const val RSSI_POLL_MS = 2_000L

@Singleton
class BoardRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BoardRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connection: BoardConnection? = null
    private var sessionJobs: List<Job> = emptyList()

    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val info = MutableStateFlow<BoardInfo?>(null)
    override val config = MutableStateFlow<BoardConfig?>(null)
    override val led = MutableStateFlow<LedState?>(null)
    override val name = MutableStateFlow<String?>(null)
    override val env = MutableStateFlow<Env?>(null)
    override val motion = MutableStateFlow<Motion?>(null)
    override val button = MutableStateFlow<ButtonState?>(null)
    override val rssi = MutableStateFlow<Int?>(null)
    override val history: StateFlow<History> get() = historyFlow
    private val historyFlow = MutableStateFlow(History())

    // Every sample lands here; a timer publishes snapshots so the UI never
    // redraws charts 100 times a second.
    private val historyBuffers = HistoryBuffers()

    override suspend fun connect(address: String, name: String) {
        disconnect()
        status.value = ConnectionStatus.Connecting(name)
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val newConnection = BoardConnection(context)
        newConnection.setConnectionObserver(LostObserver())
        connection = newConnection
        try {
            newConnection.connectTo(device)
            startSession(newConnection)
            refreshAll(newConnection)
            status.value = ConnectionStatus.Connected(this.name.value ?: name, address)
        } catch (e: Exception) {
            newConnection.close()
            connection = null
            status.value = ConnectionStatus.Failed(e.message ?: "Could not connect")
        }
    }

    override suspend fun disconnect() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        connection?.let { conn ->
            connection = null
            runCatching { conn.disconnectNow() }
            conn.close()
        }
        clearState()
        status.value = ConnectionStatus.Idle
    }

    override fun disconnectInBackground() {
        scope.launch { disconnect() }
    }

    private fun clearState() {
        info.value = null
        config.value = null
        led.value = null
        name.value = null
        env.value = null
        motion.value = null
        button.value = null
        rssi.value = null
        historyBuffers.clear()
        historyFlow.value = History()
    }

    private suspend fun refreshAll(conn: BoardConnection) {
        info.value = BoardInfo.decode(conn.read(Protocol.INFO))
        config.value = BoardConfig.decode(conn.read(Protocol.CONFIG))
        led.value = LedState.decode(conn.read(Protocol.LED))
        name.value = conn.read(Protocol.NAME).decodeToString()
        button.value = ButtonState.decode(conn.read(Protocol.BUTTON))
    }

    private fun startSession(conn: BoardConnection) {
        sessionJobs = listOf(
            scope.launch {
                conn.envNotifications.collect { bytes ->
                    runCatching { Env.decode(bytes) }.onSuccess { sample ->
                        env.value = sample
                        historyBuffers.addEnv(sample)
                    }
                }
            },
            scope.launch {
                conn.motionNotifications.collect { bytes ->
                    runCatching { Motion.decode(bytes) }.onSuccess { sample ->
                        motion.value = sample
                        historyBuffers.addMotion(sample)
                    }
                }
            },
            scope.launch {
                conn.buttonNotifications.collect { bytes ->
                    runCatching { ButtonState.decode(bytes) }.onSuccess { button.value = it }
                }
            },
            scope.launch {
                while (isActive) {
                    historyFlow.value = historyBuffers.snapshot()
                    delay(HISTORY_PUBLISH_MS)
                }
            },
            scope.launch {
                while (isActive) {
                    rssi.value = runCatching { conn.readSignalStrength() }.getOrNull()
                    delay(RSSI_POLL_MS)
                }
            },
        )
    }

    private fun requireConnection() = connection ?: error("Not connected")

    override suspend fun setLed(led: LedState) {
        requireConnection().write(Protocol.LED, led.encode())
        this.led.value = led
    }

    override suspend fun setConfig(config: BoardConfig) {
        val conn = requireConnection()
        conn.write(Protocol.CONFIG, config.encode())
        this.config.value = BoardConfig.decode(conn.read(Protocol.CONFIG))
    }

    override suspend fun setName(name: String) {
        val bytes = name.trim().encodeToByteArray()
        require(bytes.size in 1..Protocol.NAME_MAX_BYTES) { "Name must be 1-${Protocol.NAME_MAX_BYTES} bytes" }
        requireConnection().write(Protocol.NAME, bytes)
        this.name.value = name.trim()
    }

    override suspend fun send(command: Command) {
        requireConnection().write(Protocol.COMMAND, byteArrayOf(command.code.toByte()))
        if (command == Command.FACTORY_RESET) {
            delay(300)
            connection?.let { refreshAll(it) }
        }
    }

    /** Reports a link that drops by itself (out of range, board reset, reboot command). */
    private inner class LostObserver : ConnectionObserver {
        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            if (connection == null) return  // we disconnected on purpose
            sessionJobs.forEach { it.cancel() }
            sessionJobs = emptyList()
            connection?.close()
            connection = null
            clearState()
            status.value = ConnectionStatus.Lost(
                when (reason) {
                    ConnectionObserver.REASON_LINK_LOSS -> "The board went out of range"
                    ConnectionObserver.REASON_TERMINATE_PEER_USER -> "The board closed the connection"
                    else -> "Connection lost"
                }
            )
        }

        override fun onDeviceConnecting(device: BluetoothDevice) = Unit
        override fun onDeviceConnected(device: BluetoothDevice) = Unit
        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
        override fun onDeviceReady(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
    }
}

/** Fixed-size rolling buffers for the charts. */
private class HistoryBuffers {
    private val temperature = ArrayDeque<Float>()
    private val humidity = ArrayDeque<Float>()
    private val lux = ArrayDeque<Float>()
    private val hall = ArrayDeque<Float>()
    private val accel = ArrayDeque<Float>()
    private val gyro = ArrayDeque<Float>()

    private fun ArrayDeque<Float>.push(value: Float?, max: Int) {
        if (value == null) return
        addLast(value)
        while (size > max) removeFirst()
    }

    @Synchronized fun addEnv(env: Env) {
        temperature.push(env.temperatureC, ENV_HISTORY)
        humidity.push(env.humidityPct, ENV_HISTORY)
        lux.push(env.lux, ENV_HISTORY)
        hall.push(env.hallMt, ENV_HISTORY)
    }

    @Synchronized fun addMotion(motion: Motion) {
        accel.push(motion.accelG.magnitude, MOTION_HISTORY)
        gyro.push(motion.gyroDps.magnitude, MOTION_HISTORY)
    }

    @Synchronized fun snapshot() = History(
        temperature.toList(), humidity.toList(), lux.toList(), hall.toList(), accel.toList(), gyro.toList(),
    )

    @Synchronized fun clear() {
        listOf(temperature, humidity, lux, hall, accel, gyro).forEach { it.clear() }
    }
}
