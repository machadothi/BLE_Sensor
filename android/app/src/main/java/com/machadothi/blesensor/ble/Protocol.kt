package com.machadothi.blesensor.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE Sensor wire protocol. Mirrors firmware/src/ble/ble_protocol.h and
 * host/ble_sensor/protocol.py; change all three together.
 * Every packet is packed little-endian.
 */
object Protocol {
    const val VERSION = 1

    private fun uuid(short: Int) = UUID.fromString("a7e400%02x-5c2b-4f1a-9d3e-6b8c0f2e1d47".format(short))

    val SERVICE: UUID = uuid(0x00)
    val ENV: UUID = uuid(0x01)
    val MOTION: UUID = uuid(0x02)
    val BUTTON: UUID = uuid(0x03)
    val LED: UUID = uuid(0x04)
    val CONFIG: UUID = uuid(0x05)
    val INFO: UUID = uuid(0x06)
    val COMMAND: UUID = uuid(0x07)
    val NAME: UUID = uuid(0x08)
    val DISPLAY: UUID = uuid(0x09)   // optional: firmware without it has no OLED support

    /** Advertised manufacturer data: company id, then board id and protocol version. */
    const val ADV_COMPANY_ID = 0x02FF

    const val NAME_MAX_BYTES = 20

    fun boardName(id: Int): String = when (id) {
        0x0A -> "BRD4184A"
        0x0B -> "BRD4184B"
        else -> "Unknown"
    }
}

/** Sensors, in the bit order of Config.sensor_mask and Info.available_mask. */
enum class Sensor(val bit: Int, val label: String) {
    RHT(1 shl 0, "Temperature & humidity"),
    LIGHT(1 shl 1, "Light"),
    HALL(1 shl 2, "Magnetic field"),
    IMU(1 shl 3, "Motion (IMU)"),
    SOUND(1 shl 4, "Sound"),
    SUPPLY(1 shl 5, "Supply voltage");

    companion object {
        fun fromMask(mask: Int): Set<Sensor> = entries.filter { mask and it.bit != 0 }.toSet()
        fun toMask(sensors: Set<Sensor>): Int = sensors.fold(0) { acc, s -> acc or s.bit }
    }
}

/** Readings the optional OLED can show, in the bit order of Display.page_mask. */
enum class DisplayPage(val label: String) {
    TEMPERATURE("Temperature"),
    HUMIDITY("Humidity"),
    LIGHT("Light"),
    UV("UV index"),
    MAGNETIC("Magnetic field"),
    SOUND("Sound"),
    SUPPLY("Supply voltage"),
    CHIP_TEMPERATURE("Chip temperature"),
    ORIENTATION("Orientation (X/Y/Z)"),
    BUTTON("Button presses");

    val bit: Int get() = 1 shl ordinal

    /** Whether this board can show the page at all (sensor present). */
    fun isAvailableOn(info: BoardInfo): Boolean = when (this) {
        TEMPERATURE, HUMIDITY -> Sensor.RHT in info.available
        LIGHT -> Sensor.LIGHT in info.available
        UV -> Sensor.LIGHT in info.available && info.board == "BRD4184A"
        MAGNETIC -> Sensor.HALL in info.available
        SOUND -> Sensor.SOUND in info.available
        SUPPLY -> Sensor.SUPPLY in info.available
        ORIENTATION -> Sensor.IMU in info.available
        CHIP_TEMPERATURE, BUTTON -> true
    }
}

/** The optional OLED display: whether one is connected, what it shows, for how long each. */
data class DisplayState(val present: Boolean, val pages: Set<DisplayPage>, val pageMs: Int) {
    fun encode(): ByteArray = le(ByteArray(5)).apply {
        put(if (present) 1 else 0)
        putShort(pages.fold(0) { mask, page -> mask or page.bit }.toShort())
        putShort(pageMs.toShort())
    }.array()

    companion object {
        fun decode(bytes: ByteArray): DisplayState {
            val b = le(bytes)
            val present = b.u8() != 0
            val mask = b.u16()
            val pageMs = b.u16()
            return DisplayState(present, DisplayPage.entries.filter { mask and it.bit != 0 }.toSet(), pageMs)
        }
    }
}

enum class LedMode(val code: Int) {
    OFF(0), ON(1), BLINK(2);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class Command(val code: Int) {
    CALIBRATE_GYRO(0x01),
    FACTORY_RESET(0x02),
    REBOOT(0x03),
    IDENTIFY(0x04),
    RESET_ORIENTATION(0x05),
}

/** Allowed Config values; the board rejects others (ATT error 0x13). */
object Limits {
    val envPeriodMs = 100..60_000
    val motionPeriodMs = 10..1_000
    val txPowerDbm = -30f..6f
    val advIntervalMs = 20..10_240
    val hallThresholdMt = 0.1f..20f
    val displayPageMs = 1_000..60_000
    const val LED_BLINK_MIN_MS = 10
}

private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

private fun ByteBuffer.u8() = get().toInt() and 0xFF
private fun ByteBuffer.u16() = short.toInt() and 0xFFFF
private fun ByteBuffer.i16() = short.toInt()
private fun ByteBuffer.u32() = int.toLong() and 0xFFFF_FFFFL
private fun ByteBuffer.i32() = int

/** Environmental readings. A value is null when its sensor is off, absent or not ready yet. */
data class Env(
    val uptimeMs: Long,
    val temperatureC: Float?,
    val humidityPct: Float?,
    val lux: Float?,
    val uvIndex: Float?,
    val hallMt: Float?,
    val hallAlert: Boolean,
    val hallTamper: Boolean,
    val soundDb: Float?,
    val supplyV: Float?,
    val dieTemperatureC: Float?,
) {
    companion object {
        const val SIZE = 27

        fun decode(bytes: ByteArray): Env {
            require(bytes.size >= SIZE) { "Env needs $SIZE bytes, got ${bytes.size}" }
            val b = le(bytes)
            val uptime = b.u32()
            val valid = b.u16()
            val temperature = b.i16()
            val humidity = b.u16()
            val lux = b.u32()
            val uv = b.u16()
            val hallUt = b.i32()
            val hallFlags = b.u8()
            val sound = b.i16()
            val supplyMv = b.u16()
            val dieTemperature = b.i16()

            fun <T> ifValid(bit: Int, value: T): T? = if (valid and (1 shl bit) != 0) value else null
            val hallValid = valid and (1 shl 4) != 0
            return Env(
                uptimeMs = uptime,
                temperatureC = ifValid(0, temperature / 100f),
                humidityPct = ifValid(1, humidity / 100f),
                lux = ifValid(2, lux / 100f),
                uvIndex = ifValid(3, uv / 100f),
                hallMt = ifValid(4, hallUt / 1000f),
                hallAlert = hallValid && hallFlags and 0x01 != 0,
                hallTamper = hallValid && hallFlags and 0x02 != 0,
                soundDb = ifValid(5, sound / 100f),
                supplyV = ifValid(6, supplyMv / 1000f),
                dieTemperatureC = ifValid(7, dieTemperature / 100f),
            )
        }
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    val magnitude: Float get() = kotlin.math.sqrt(x * x + y * y + z * z)
}

/** One IMU sample. Orientation is roll (x), pitch (y), yaw (z) in degrees. */
data class Motion(val uptimeMs: Long, val accelG: Vec3, val gyroDps: Vec3, val orientationDeg: Vec3) {
    companion object {
        const val SIZE = 22

        fun decode(bytes: ByteArray): Motion {
            require(bytes.size >= SIZE) { "Motion needs $SIZE bytes, got ${bytes.size}" }
            val b = le(bytes)
            val uptime = b.u32()
            fun vec(scale: Float) = Vec3(b.i16() / scale, b.i16() / scale, b.i16() / scale)
            return Motion(uptime, accelG = vec(1000f), gyroDps = vec(100f), orientationDeg = vec(100f))
        }
    }
}

data class ButtonState(val pressed: Boolean, val pressCount: Long) {
    companion object {
        fun decode(bytes: ByteArray): ButtonState {
            val b = le(bytes)
            return ButtonState(b.u8() != 0, b.u32())
        }
    }
}

data class LedState(val mode: LedMode, val onMs: Int, val offMs: Int) {
    fun encode(): ByteArray = le(ByteArray(5)).apply {
        put(mode.code.toByte()); putShort(onMs.toShort()); putShort(offMs.toShort())
    }.array()

    companion object {
        fun decode(bytes: ByteArray): LedState {
            val b = le(bytes)
            return LedState(LedMode.fromCode(b.u8()), b.u16(), b.u16())
        }
    }
}

/** Settings stored on the board. TX power and advertising interval apply after disconnecting. */
data class BoardConfig(
    val sensors: Set<Sensor>,
    val envPeriodMs: Int,
    val motionPeriodMs: Int,
    val txPowerDbm: Float,
    val advIntervalMs: Int,
    val hallThresholdMt: Float,
) {
    fun encode(): ByteArray = le(ByteArray(12)).apply {
        put(Protocol.VERSION.toByte())
        put(Sensor.toMask(sensors).toByte())
        putShort(envPeriodMs.toShort())
        putShort(motionPeriodMs.toShort())
        putShort(Math.round(txPowerDbm * 10).toShort())
        putShort(advIntervalMs.toShort())
        putShort(Math.round(hallThresholdMt * 1000).toShort())
    }.array()

    val isValid: Boolean
        get() = envPeriodMs in Limits.envPeriodMs && motionPeriodMs in Limits.motionPeriodMs &&
            txPowerDbm in Limits.txPowerDbm && advIntervalMs in Limits.advIntervalMs &&
            hallThresholdMt in Limits.hallThresholdMt

    companion object {
        fun decode(bytes: ByteArray): BoardConfig {
            val b = le(bytes)
            b.u8() // protocol version
            return BoardConfig(
                sensors = Sensor.fromMask(b.u8()),
                envPeriodMs = b.u16(),
                motionPeriodMs = b.u16(),
                txPowerDbm = b.i16() / 10f,
                advIntervalMs = b.u16(),
                hallThresholdMt = b.u16() / 1000f,
            )
        }
    }
}

data class BoardInfo(val protocolVersion: Int, val board: String, val available: Set<Sensor>) {
    companion object {
        fun decode(bytes: ByteArray): BoardInfo {
            val b = le(bytes)
            return BoardInfo(b.u8(), Protocol.boardName(b.u8()), Sensor.fromMask(b.u8()))
        }
    }
}
