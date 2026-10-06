package com.machadothi.blesensor.repository

import com.machadothi.blesensor.ble.AirReading
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.BoardInfo
import com.machadothi.blesensor.ble.BoardTime
import com.machadothi.blesensor.ble.WeatherInfo
import com.machadothi.blesensor.ble.UpdateStatus
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
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data class Connecting(val name: String) : ConnectionStatus
    data class Connected(val name: String, val address: String) : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
    data class Lost(val message: String) : ConnectionStatus
}

/** Recent values of the charted quantities, oldest first. */
data class History(
    val temperatureC: List<Float> = emptyList(),
    val humidityPct: List<Float> = emptyList(),
    val lux: List<Float> = emptyList(),
    val hallMt: List<Float> = emptyList(),
    val accelG: List<Float> = emptyList(),
    val gyroDps: List<Float> = emptyList(),
    val eco2Ppm: List<Float> = emptyList(),
    val tvocPpb: List<Float> = emptyList(),
)

/** Everything the app knows about the connected board, as observable state. */
interface BoardRepository {
    val status: StateFlow<ConnectionStatus>
    val info: StateFlow<BoardInfo?>
    val config: StateFlow<BoardConfig?>
    val led: StateFlow<LedState?>
    val name: StateFlow<String?>
    /** Null if the firmware has no display support. */
    val display: StateFlow<DisplayState?>
    val env: StateFlow<Env?>
    val motion: StateFlow<Motion?>
    val button: StateFlow<ButtonState?>
    /** Null unless the board measures air quality (ESP32 Air). */
    val air: StateFlow<AirReading?>
    /** Null unless the board reports its own status (ESP32 Air). */
    val system: StateFlow<SystemInfo?>
    /** Temperature offset and self-heating measurement; null if the board has no Calibration (Thunderboard). */
    val calibration: StateFlow<CalibrationStatus?>
    /** The board's clock and Wi-Fi; null unless the board has them (ESP32 Air). */
    val boardTime: StateFlow<BoardTime?>
    val wifi: StateFlow<WifiStatus?>
    val weather: StateFlow<WeatherInfo?>
    /** Firmware updates; null unless the board can update itself (ESP32 Air). */
    val update: StateFlow<UpdateStatus?>
    /** False on boards without LED/config/motion (ESP32 Air): the UI hides those parts. */
    val hasLed: StateFlow<Boolean>
    val hasConfig: StateFlow<Boolean>
    val rssi: StateFlow<Int?>
    val history: StateFlow<History>

    suspend fun connect(address: String, name: String)
    suspend fun disconnect()

    /** Disconnects without waiting; for callers that are going away (e.g. a closing screen). */
    fun disconnectInBackground()

    /** Each write throws if the board rejects it (e.g. out-of-range value). */
    suspend fun setLed(led: LedState)
    suspend fun setConfig(config: BoardConfig)
    suspend fun setName(name: String)
    /** What the OLED shows and for how long each reading (present is ignored). */
    suspend fun setDisplay(display: DisplayState)
    suspend fun send(command: Command)
    suspend fun setTemperatureOffset(offsetC: Float)
    /** command: 0 none, 1 start a self-heating measurement, 2 cancel it. */
    suspend fun controlCalibration(auto: Boolean, command: Int)
    suspend fun scanWifi()
    suspend fun connectWifi(ssid: String, password: String)
    suspend fun forgetWifi()
    suspend fun setMqtt(enabled: Boolean)
    /** command: from WeatherInfo.setPlace / locateAutomatically / enabled / refresh. */
    suspend fun controlWeather(command: ByteArray)
    /** Sets the board's clock to a time picked by hand (unixMs), or the phone's time (null). */
    suspend fun setBoardTime(unixMs: Long?)
    /**
     * command: from UpdateStatus.check / install / dismiss / auto. A check or an
     * install restarts the board: the link drops, and the app reconnects by itself.
     */
    suspend fun controlUpdate(command: ByteArray)
}
