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
    fun infoDecodesBoardAndSensors() {
        val info = BoardInfo.decode(byteArrayOf(1, 0x0A, 0x2F, 0))
        assertEquals("BRD4184A", info.board)
        assertEquals(setOf(Sensor.RHT, Sensor.LIGHT, Sensor.HALL, Sensor.IMU, Sensor.SUPPLY), info.available)
    }
}
