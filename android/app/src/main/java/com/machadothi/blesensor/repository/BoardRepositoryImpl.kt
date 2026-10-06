package com.machadothi.blesensor.repository

import android.bluetooth.BluetoothManager
import android.content.Context
import com.machadothi.blesensor.ble.AirReading
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.BoardConnection
import com.machadothi.blesensor.ble.BoardInfo
import com.machadothi.blesensor.ble.BoardTime
import com.machadothi.blesensor.ble.WifiStatus
import com.machadothi.blesensor.ble.CalibrationStatus
import com.machadothi.blesensor.ble.ButtonState
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.DisplayPage
import com.machadothi.blesensor.ble.DisplayState
import com.machadothi.blesensor.ble.Env
import com.machadothi.blesensor.ble.LedState
import com.machadothi.blesensor.ble.Motion
import com.machadothi.blesensor.ble.SystemInfo
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
    override val display = MutableStateFlow<DisplayState?>(null)
    override val env = MutableStateFlow<Env?>(null)
    override val motion = MutableStateFlow<Motion?>(null)
    override val button = MutableStateFlow<ButtonState?>(null)
    override val air = MutableStateFlow<AirReading?>(null)
    override val system = MutableStateFlow<SystemInfo?>(null)
    override val calibration = MutableStateFlow<CalibrationStatus?>(null)
    override val boardTime = MutableStateFlow<BoardTime?>(null)
    override val wifi = MutableStateFlow<WifiStatus?>(null)
    override val hasLed = MutableStateFlow(false)
    override val hasConfig = MutableStateFlow(false)
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
        display.value = null
        env.value = null
        motion.value = null
        button.value = null
        air.value = null
        system.value = null
        calibration.value = null
        boardTime.value = null
        wifi.value = null
        hasLed.value = false
        hasConfig.value = false
        rssi.value = null
        historyBuffers.clear()
        historyFlow.value = History()
    }

    private suspend fun refreshAll(conn: BoardConnection) {
        info.value = BoardInfo.decode(conn.read(Protocol.INFO))
        hasConfig.value = conn.has(Protocol.CONFIG)
        hasLed.value = conn.has(Protocol.LED)
        config.value = if (conn.has(Protocol.CONFIG)) BoardConfig.decode(conn.read(Protocol.CONFIG)) else null
        led.value = if (conn.has(Protocol.LED)) LedState.decode(conn.read(Protocol.LED)) else null
        name.value = conn.read(Protocol.NAME).decodeToString()
        button.value = if (conn.has(Protocol.BUTTON)) ButtonState.decode(conn.read(Protocol.BUTTON)) else null
        display.value = if (conn.has(Protocol.DISPLAY)) DisplayState.decode(conn.read(Protocol.DISPLAY)) else null
        air.value = if (conn.has(Protocol.AIR)) runCatching { AirReading.decode(conn.read(Protocol.AIR)) }.getOrNull() else null
        calibration.value = if (conn.has(Protocol.CALIBRATION)) readCalibration(conn) else null
        if (conn.has(Protocol.TIME)) {
            // Give the board the phone's time and zone on every connect: it then knows
            // the time even without Wi-Fi, and summer time follows the phone's rules.
            runCatching { conn.write(Protocol.TIME, BoardTime.encodeFromPhone(System.currentTimeMillis(), java.util.TimeZone.getDefault())) }
            boardTime.value = runCatching { BoardTime.decode(conn.read(Protocol.TIME)) }.getOrNull()
        }
        wifi.value = if (conn.has(Protocol.WIFI)) runCatching { WifiStatus.decode(conn.read(Protocol.WIFI)) }.getOrNull() else null
        system.value = if (conn.has(Protocol.SYSTEM)) runCatching { SystemInfo.decode(conn.read(Protocol.SYSTEM)) }.getOrNull() else null
        // The first Env notification can take a few seconds; read one now.
        runCatching { Env.decode(conn.read(Protocol.ENV)) }.onSuccess { env.value = it }
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
                conn.wifiNotifications.collect { bytes ->
                    runCatching { WifiStatus.decode(bytes) }.onSuccess { wifi.value = it }
                }
            },
            scope.launch {
                while (isActive && conn.has(Protocol.TIME)) {
                    delay(30_000)
                    boardTime.value = runCatching { BoardTime.decode(conn.read(Protocol.TIME)) }.getOrNull() ?: boardTime.value
                }
            },
            scope.launch {
                conn.calibrationNotifications.collect { bytes ->
                    runCatching { CalibrationStatus.decode(bytes) }.onSuccess { calibration.value = it }
                }
            },
            scope.launch {
                conn.systemNotifications.collect { bytes ->
                    runCatching { SystemInfo.decode(bytes) }.onSuccess { system.value = it }
                }
            },
            scope.launch {
                conn.airNotifications.collect { bytes ->
                    runCatching { AirReading.decode(bytes) }.onSuccess { sample ->
                        air.value = sample
                        historyBuffers.addAir(sample)
                    }
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

    override suspend fun setDisplay(display: DisplayState) {
        this.display.value ?: error("This firmware has no display support")
        requireConnection().write(Protocol.DISPLAY, display.encode())
        this.display.value = display
    }

    override suspend fun setName(name: String) {
        val bytes = name.trim().encodeToByteArray()
        require(bytes.size in 1..Protocol.NAME_MAX_BYTES) { "Name must be 1-${Protocol.NAME_MAX_BYTES} bytes" }
        requireConnection().write(Protocol.NAME, bytes)
        this.name.value = name.trim()
    }

    private suspend fun readCalibration(conn: BoardConnection): CalibrationStatus? =
        runCatching { CalibrationStatus.decode(conn.read(Protocol.CALIBRATION)) }.getOrNull()

    override suspend fun setTemperatureOffset(offsetC: Float) {
        require(offsetC in -Protocol.OFFSET_MAX_C..Protocol.OFFSET_MAX_C) { "Offset must be within ±${Protocol.OFFSET_MAX_C.toInt()} °C" }
        val conn = requireConnection()
        val raw = Math.round(offsetC * 100)
        conn.write(Protocol.CALIBRATION, byteArrayOf(raw.toByte(), (raw shr 8).toByte()))
        calibration.value = readCalibration(conn)
    }

    override suspend fun controlCalibration(auto: Boolean, command: Int) {
        val conn = requireConnection()
        val raw = Math.round((calibration.value?.offsetC ?: 0f) * 100)
        conn.write(Protocol.CALIBRATION, byteArrayOf(raw.toByte(), (raw shr 8).toByte(), if (auto) 1 else 0, command.toByte()))
        calibration.value = readCalibration(conn)
    }

    override suspend fun scanWifi() = requireConnection().write(Protocol.WIFI, WifiStatus.scan())

    override suspend fun connectWifi(ssid: String, password: String) =
        requireConnection().write(Protocol.WIFI, WifiStatus.connect(ssid, password))

    override suspend fun forgetWifi() = requireConnection().write(Protocol.WIFI, WifiStatus.forget())

    override suspend fun setMqtt(enabled: Boolean) = requireConnection().write(Protocol.WIFI, WifiStatus.mqtt(enabled))

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
    private val eco2 = ArrayDeque<Float>()
    private val tvoc = ArrayDeque<Float>()

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

    @Synchronized fun addAir(air: AirReading) {
        eco2.push(air.eco2Ppm?.toFloat(), ENV_HISTORY)
        tvoc.push(air.tvocPpb?.toFloat(), ENV_HISTORY)
    }

    @Synchronized fun addMotion(motion: Motion) {
        accel.push(motion.accelG.magnitude, MOTION_HISTORY)
        gyro.push(motion.gyroDps.magnitude, MOTION_HISTORY)
    }

    @Synchronized fun snapshot() = History(
        temperature.toList(), humidity.toList(), lux.toList(), hall.toList(), accel.toList(), gyro.toList(),
        eco2.toList(), tvoc.toList(),
    )

    @Synchronized fun clear() {
        listOf(temperature, humidity, lux, hall, accel, gyro, eco2, tvoc).forEach { it.clear() }
    }
}
