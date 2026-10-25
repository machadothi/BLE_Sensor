package com.machadothi.blesensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A board this phone is bonded with: connectable without scanning. */
data class KnownBoard(val address: String, val name: String, val board: String)

/**
 * The boards this phone has bonded with (ESP32 Air), kept in the app's
 * preferences. Only those Android still has a bond for are listed, so a bond
 * removed in Android's Bluetooth settings drops out by itself.
 */
@Singleton
class KnownBoards @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("known_boards", Context.MODE_PRIVATE)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _boards = MutableStateFlow(load())
    val boards: StateFlow<List<KnownBoard>> = _boards.asStateFlow()

    @SuppressLint("MissingPermission") // the permissions screen comes first
    private fun bonded(address: String): Boolean =
        runCatching { adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    private fun load(): List<KnownBoard> = prefs.all.mapNotNull { (address, value) ->
        val parts = (value as? String)?.split("\n") ?: return@mapNotNull null
        KnownBoard(address, parts.getOrElse(0) { address }, parts.getOrElse(1) { "Unknown" })
    }.filter { bonded(it.address) }.sortedBy { it.name }

    fun refresh() {
        _boards.value = load()
    }

    fun remember(address: String, name: String, board: String) {
        prefs.edit().putString(address, "$name\n$board").apply()
        refresh()
    }

    /** Forgets the board here and removes Android's bond with it. */
    @SuppressLint("MissingPermission")
    fun forget(address: String) {
        prefs.edit().remove(address).apply()
        runCatching {
            val device = adapter?.getRemoteDevice(address) ?: return@runCatching
            // BluetoothDevice.removeBond() is hidden API, but the standard way apps unpair.
            device.javaClass.getMethod("removeBond").invoke(device)
        }
        refresh()
    }
}
