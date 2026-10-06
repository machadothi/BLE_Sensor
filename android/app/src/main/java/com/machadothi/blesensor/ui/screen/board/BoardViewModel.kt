package com.machadothi.blesensor.ui.screen.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.machadothi.blesensor.ble.BoardConfig
import com.machadothi.blesensor.ble.Command
import com.machadothi.blesensor.ble.DisplayPage
import com.machadothi.blesensor.ble.DisplayState
import com.machadothi.blesensor.ble.LedState
import com.machadothi.blesensor.repository.BoardRepository
import com.machadothi.blesensor.ui.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.exception.RequestFailedException
import javax.inject.Inject

/** Shared by all tabs of the board screen. Connects on open, disconnects on close. */
@HiltViewModel
class BoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BoardRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<NavRoutes.Board>()
    val boardName: String = route.name

    val status = repository.status
    val info = repository.info
    val config = repository.config
    val led = repository.led
    val name = repository.name
    val display = repository.display
    val env = repository.env
    val motion = repository.motion
    val button = repository.button
    val air = repository.air
    val system = repository.system
    val hasLed = repository.hasLed
    val hasConfig = repository.hasConfig
    val rssi = repository.rssi
    val history = repository.history

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short confirmations and errors for the snackbar. */
    val messages: SharedFlow<String> = _messages

    init {
        connect()
    }

    fun connect() {
        viewModelScope.launch { repository.connect(route.address, route.name) }
    }

    fun setLed(led: LedState) = run("LED updated", quiet = true) { repository.setLed(led) }

    fun setConfig(config: BoardConfig) = run("Settings saved on the board") { repository.setConfig(config) }

    fun setName(name: String) = run("Name saved; it's advertised after you disconnect") { repository.setName(name) }

    fun setDisplayPages(pages: Set<DisplayPage>) = updateDisplay { it.copy(pages = pages) }

    fun setDisplayPageMs(pageMs: Int) = updateDisplay { it.copy(pageMs = pageMs) }

    private fun updateDisplay(change: (DisplayState) -> DisplayState) {
        val current = display.value ?: return
        run("Display updated", quiet = true) { repository.setDisplay(change(current)) }
    }

    fun send(command: Command) = run(
        when (command) {
            Command.CALIBRATE_GYRO -> "Gyro calibrated"
            Command.FACTORY_RESET -> "Factory defaults restored"
            Command.REBOOT -> "Board is rebooting"
            Command.IDENTIFY -> if (info.value?.isThunderboard == false) "Look at the board: the display is flashing" else "Look at the board: the LED is blinking"
            Command.RESET_ORIENTATION -> "Orientation zeroed"
        },
    ) { repository.send(command) }

    private fun run(success: String, quiet: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                if (!quiet) _messages.emit(success)
            } catch (e: RequestFailedException) {
                // 0x13 = the board's "value not allowed"
                _messages.emit(if (e.status == 0x13) "The board rejected that value" else "Write failed (status ${e.status})")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Something went wrong")
            }
        }
    }

    override fun onCleared() {
        repository.disconnectInBackground()
    }
}
