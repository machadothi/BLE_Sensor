package com.machadothi.blesensor.repository

import com.machadothi.blesensor.ble.AirReading
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.BoardInfo
import com.machadothi.blesensor.ble.ButtonState
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.DisplayPage
import com.machadothi.blesensor.ble.DisplayState
import com.machadothi.blesensor.ble.Env
import com.machadothi.blesensor.ble.LedState
import com.machadothi.blesensor.ble.Motion
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
}
