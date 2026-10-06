package com.machadothi.blesensor.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fixtures were produced by the Python client (host/ble_sensor/protocol.py),
 * so these tests prove the Kotlin and Python decoders agree byte for byte.
 */
class ProtocolTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertNear(expected: Float?, actual: Float?) {
        if (expected == null) assertEquals(null, actual) else assertEquals(expected, actual!!, 0.01f)
    }

    private fun env(
        bytes: String, uptime: Long, temperature: Float?, humidity: Float?, lux: Float?, uv: Float?,
        hall: Float?, alert: Boolean, tamper: Boolean, sound: Float?, supply: Float?, die: Float?,
    ) {
        val e = Env.decode(hex(bytes))
        assertEquals(uptime, e.uptimeMs)
        assertNear(temperature, e.temperatureC)
        assertNear(humidity, e.humidityPct)
        assertNear(lux, e.lux)
        assertNear(uv, e.uvIndex)
        assertNear(hall, e.hallMt)
        assertEquals(alert, e.hallAlert)
        assertEquals(tamper, e.hallTamper)
        assertNear(sound, e.soundDb)
        assertNear(supply, e.supplyV)
        assertNear(die, e.dieTemperatureC)
    }

    private fun motion(bytes: String, uptime: Long, expected: FloatArray) {
        val m = Motion.decode(hex(bytes))
        assertEquals(uptime, m.uptimeMs)
        val actual = listOf(m.accelG, m.gyroDps, m.orientationDeg).flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()
        assertArrayEquals(expected, actual, 0.001f)
    }

    private fun config(expectedHex: String) {
        val config = BoardConfig(
            sensors = setOf(Sensor.RHT, Sensor.LIGHT, Sensor.HALL, Sensor.IMU, Sensor.SUPPLY),
            envPeriodMs = 500, motionPeriodMs = 20, txPowerDbm = 3f, advIntervalMs = 100, hallThresholdMt = 5f,
        )
        assertArrayEquals(hex(expectedHex), config.encode())
        assertEquals(config, BoardConfig.decode(config.encode()))
    }

    private fun led(expectedHex: String) {
        val led = LedState(LedMode.BLINK, 100, 400)
        assertArrayEquals(hex(expectedHex), led.encode())
        assertEquals(led, LedState.decode(led.encode()))
    }

    @Test
    fun decodesLikeThePythonClient() {
        env("370d9e26180002f54b22e8181800ec021147000000f41b9f0a7efe", 647892279L, null, null, null, 7.48f, 18.193f, false, false, null, null, null)
        env("5aa30016d600d8f4670ff738170068048d1e000000d01d420a610a", 369140570L, null, 39.43f, 15219.11f, null, 7.821f, false, false, null, 2.626f, 26.57f)
        env("f50ae695190086fefb0297818e00100103fcffff035510ed0ba103", 2514881269L, -3.78f, null, null, 2.72f, -1.021f, true, true, null, null, null)
        env("586627925c00f8f63825733a92001c05f8e1ffff02d60ef40b1c00", 2452055640L, null, null, 95832.19f, 13.08f, -7.688f, false, true, null, 3.06f, null)
        env("c3707a90690025100722b0766d00830211290000034a17f60afe0b", 2423943363L, 41.33f, null, null, 6.43f, null, false, false, 59.62f, 2.806f, null)
        env("27745ccb7c009df5c32420dd4c0033049f300000020f238f0b850e", 3411833895L, null, null, 50373.44f, 10.75f, 12.447f, false, true, 89.75f, 2.959f, null)
        motion("ce4abd120e030aeb3aaad34190d7e8a6ea6e2cfdf4eb", 314395342L, floatArrayOf(0.782f, -5.366f, -21.958f, 168.51f, -103.52f, -228.08f, 283.94f, -7.24f, -51.32f))
        motion("d03110abba43de0eb112024a1f607f5151d012d7fe31", 2869965264L, floatArrayOf(17.338f, 3.806f, 4.785f, 189.46f, 246.07f, 208.63f, -122.07f, -104.78f, 127.98f))
        motion("dd1c01cc9a910857f597d6711ac55ef97132052aa390", 3422624989L, floatArrayOf(-28.262f, 22.28f, -26.635f, 291.42f, -150.78f, -16.98f, 129.13f, 107.57f, -285.09f))
        config("012ff40114001e0064008813")
        led("0264009001")
    }

    @Test
    fun displayMatchesThePythonClient() {
        // Display(True, [temperature, humidity, orientation, button], 3.5).encode() in Python
        val bytes = hex("010303ac0d")
        val display = DisplayState.decode(bytes)
        assertEquals(true, display.present)
        assertEquals(
            setOf(DisplayPage.TEMPERATURE, DisplayPage.HUMIDITY, DisplayPage.ORIENTATION, DisplayPage.BUTTON),
            display.pages,
        )
        assertEquals(3500, display.pageMs)
        assertArrayEquals(bytes, display.encode())
    }

    @Test
    fun infoDecodesBoardAndSensors() {
        val info = BoardInfo.decode(byteArrayOf(1, 0x0A, 0x2F, 0))
        assertEquals("BRD4184A", info.board)
        assertEquals(setOf(Sensor.RHT, Sensor.LIGHT, Sensor.HALL, Sensor.IMU, Sensor.SUPPLY), info.available)
    }

    // Bytes as the ESP32 Air board sends them (~/git/air_quality_sensor/firmware/ble.py).
    @Test
    fun esp32AirBoard() {
        val air = AirReading.decode(hex("40e201000004ad03fc02"))
        assertEquals(AirState.NORMAL, air.state)
        assertEquals(4, air.aqi)
        assertEquals(941, air.eco2Ppm)
        assertEquals(764, air.tvocPpb)

        val warming = AirReading.decode(hex("5a8a0000010000000000"))
        assertEquals(AirState.WARM_UP, warming.state)
        assertEquals(null, warming.eco2Ppm)

        val info = BoardInfo.decode(hex("010c4100"))
        assertEquals(Protocol.BOARD_ESP32_AIR, info.board)
        assertEquals(setOf(Sensor.RHT, Sensor.AIR), info.available)
        assertEquals(false, info.isThunderboard)

        val display = DisplayState.decode(hex("01031c8813"))
        assertEquals(
            setOf(DisplayPage.TEMPERATURE, DisplayPage.HUMIDITY, DisplayPage.AIR_QUALITY, DisplayPage.ECO2, DisplayPage.TVOC),
            display.pages,
        )
        assertEquals(display.pages, display.pages.filter { it.isAvailableOn(info) }.toSet())
    }

    // The ESP32's extended Air (22 bytes) and System (32 bytes) values.
    @Test
    fun esp32Details() {
        val air = AirReading.decode(hex("e8800000000321032d0183050406d6a92c7a870a3c12"))
        assertEquals(801, air.eco2Ppm)
        assertEquals("5.4.6", air.firmware)
        assertEquals(0x83, air.statusRegister)
        assertEquals(26.95f, air.compensationC!!, 0.001f)
        assertEquals(46.68f, air.compensationPct!!, 0.001f)
        assertEquals(Math.pow(2.0, 43478 / 2048.0), air.r1Ohms!!, 1.0)

        val system = SystemInfo.decode(hex("2100000020e500003a12c207c0a8012aa0000201180100000000000000000000"))
        assertEquals(46.66f, system.chipTemperatureC!!, 0.001f)
        assertEquals(-62, system.wifiRssi)
        assertEquals("192.168.1.42", system.ip)
        assertEquals(true, system.wifi && system.mqtt && system.bluetooth)
        assertEquals("1.24.1", system.micropython)
        assertEquals("Reset pin / USB", system.resetCause)

        assertEquals(14.6f, Derived.dewPoint(26.69f, 47.57f)!!, 0.1f)
        assertEquals(12.0f, Derived.absoluteHumidity(26.69f, 47.57f)!!, 0.1f)
    }

    // Thunderboard: 5 bytes, no rotation. ESP32 Air: a 6th byte, bit 0 = rotated 180°.
    @Test
    fun displayRotation() {
        val thunderboard = DisplayState.decode(hex("010303ac0d"))
        assertEquals(null, thunderboard.rotated)
        assertEquals(5, thunderboard.encode().size)

        val esp32 = DisplayState.decode(hex("01031c881301"))
        assertEquals(true, esp32.rotated)
        assertArrayEquals(hex("01031c881300"), esp32.copy(rotated = false).encode())
    }

    @Test
    fun calibrationStatus() {
        val cooling = CalibrationStatus.decode(hex("6aff01027d00b004640a150aff7fffffffff0000"))
        assertEquals(-1.5f, cooling.offsetC, 0.001f)
        assertEquals(true, cooling.auto)
        assertEquals(CalibrationStatus.State.COOLING, cooling.state)
        assertEquals(125, cooling.elapsedS)
        assertEquals(26.6f, cooling.warmC!!, 0.001f)
        assertEquals(null, cooling.resultC)
        assertEquals(null, cooling.resultAgeS)

        val failed = CalibrationStatus.decode(hex("000000040000b004640a8c0aff7fffffffff0200"))
        assertEquals(CalibrationStatus.State.FAILED, failed.state)
        assertEquals("the room temperature changed during the measurement", failed.failure)

        assertEquals(false, CalibrationStatus.decode(hex("6aff")).canMeasure)   // 2-byte offset only
    }

    @Test
    fun weather() {
        val w = WeatherInfo.decode(hex("07a00069002600014600a5007d000000000953746f636b686f6c6d0001"))
        assertEquals(16f, w.temperatureC!!, 0.01f)
        assertEquals("Clear sky", w.description)
        assertEquals("Stockholm", w.place)
        assertEquals(true, w.placeIsAutomatic && w.online && w.fresh)
        assertEquals(null, w.error)
    }

    @Test
    fun updateStatus() {
        // ble.py write_update: state 2 (available), progress, current, available, notes, error, auto
        val u = UpdateStatus.decode(hex("020005312e302e3005312e302e310546697865730001"))
        assertEquals(UpdateStatus.State.AVAILABLE, u.state)
        assertEquals("1.0.0", u.currentVersion)
        assertEquals("1.0.1", u.availableVersion)
        assertEquals("Fixes", u.notes)
        assertEquals(null, u.error)
        assertEquals(true, u.auto)
        assertArrayEquals(byteArrayOf(2), UpdateStatus.install())
        assertArrayEquals(byteArrayOf(5), UpdateStatus.auto(false))
    }

    @Test
    fun manualTime() {
        // 7 bytes like the phone's time, then source 3 (set by hand)
        val bytes = BoardTime.encodeManual(1_759_759_200_000, java.util.TimeZone.getTimeZone("Europe/Stockholm"))
        assertArrayEquals(hex("60cbe3683c000103"), bytes)
        assertEquals(true, BoardTime.decode(hex("60cbe3683c000103" + "3c000000")).setByHand)
    }
}
