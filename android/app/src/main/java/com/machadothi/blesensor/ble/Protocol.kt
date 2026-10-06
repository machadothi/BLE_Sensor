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
    val AIR: UUID = uuid(0x0A)       // optional: air-quality boards (ESP32 Air) only
    val SYSTEM: UUID = uuid(0x0B)    // optional: board status (ESP32 Air) only
    val CALIBRATION: UUID = uuid(0x0C) // optional: i16 temperature offset, °C × 100 (ESP32 Air) only
    val TIME: UUID = uuid(0x0D)      // optional: the board's clock (ESP32 Air) only
    val WIFI: UUID = uuid(0x0E)      // optional: Wi-Fi network and scan (ESP32 Air) only
    const val OFFSET_MAX_C = 10f

    /** Advertised manufacturer data: company id, then board id and protocol version. */
    const val ADV_COMPANY_ID = 0x02FF

    const val NAME_MAX_BYTES = 20

    const val BOARD_ESP32_AIR = "ESP32 Air"

    fun boardName(id: Int): String = when (id) {
        0x0A -> "BRD4184A"
        0x0B -> "BRD4184B"
        0x0C -> BOARD_ESP32_AIR    // ESP32 + ENS160/AHT21 air monitor (~/git/air_quality_sensor)
        else -> "Unknown"
    }
}

/**
 * Sensors, in the bit order of Config.sensor_mask and Info.available_mask.
 * [switchable]: one of the Thunderboard's sensors that Config can turn on and off.
 * Air quality is only reported by the ESP32 Air board (Info), which has no Config.
 */
enum class Sensor(val bit: Int, val label: String, val switchable: Boolean = true) {
    RHT(1 shl 0, "Temperature & humidity"),
    LIGHT(1 shl 1, "Light"),
    HALL(1 shl 2, "Magnetic field"),
    IMU(1 shl 3, "Motion (IMU)"),
    SOUND(1 shl 4, "Sound"),
    SUPPLY(1 shl 5, "Supply voltage"),
    AIR(1 shl 6, "Air quality (ENS160)", switchable = false);

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
    BUTTON("Button presses"),
    // Bits 10-12: the ESP32 Air board's pages.
    AIR_QUALITY("Air quality"),
    ECO2("CO2 (eCO2)"),
    TVOC("TVOC"),
    DEW_POINT("Dew point"),
    SENSOR_DETAILS("Air sensor details"),
    SYSTEM("System (Wi-Fi, uptime)"),
    CLOCK("Clock");

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
        CHIP_TEMPERATURE, BUTTON -> info.isThunderboard
        AIR_QUALITY, ECO2, TVOC, DEW_POINT, SENSOR_DETAILS, SYSTEM, CLOCK -> Sensor.AIR in info.available
    }
}

/** The optional OLED display: whether one is connected, what it shows, for how long each. */
/**
 * The optional OLED display: whether one is connected, what it shows, for how long each.
 * [rotated]: turned 180°; null when the board can't rotate it from the app
 * (5-byte value, the Thunderboard). Boards that can send a 6th byte: flags, bit 0 = rotated.
 */
data class DisplayState(
    val present: Boolean,
    val pages: Set<DisplayPage>,
    val pageMs: Int,
    val rotated: Boolean? = null,
    /** Bytes the board uses: 5 (Thunderboard), 6 (+ flags), 7 (+ page bits 16-23). */
    val size: Int = if (rotated == null) 5 else 6,
) {
    fun encode(): ByteArray = le(ByteArray(size)).apply {
        val mask = pages.fold(0) { m, page -> m or page.bit }
        put(if (present) 1 else 0)
        putShort((mask and 0xFFFF).toShort())
        putShort(pageMs.toShort())
        if (size >= 6) put(if (rotated == true) 1 else 0)
        if (size >= 7) put((mask shr 16).toByte())
    }.array()

    companion object {
        fun decode(bytes: ByteArray): DisplayState {
            val b = le(bytes)
            val present = b.u8() != 0
            var mask = b.u16()
            val pageMs = b.u16()
            val rotated = if (bytes.size >= 6) b.u8() and 1 != 0 else null
            if (bytes.size >= 7) mask = mask or (b.u8() shl 16)
            return DisplayState(present, DisplayPage.entries.filter { mask and it.bit != 0 }.toSet(), pageMs, rotated, minOf(bytes.size, 7))
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
    /** Thunderboard EFR32BG22 (BRD4184A/B), as opposed to the ESP32 Air board. */
    val isThunderboard: Boolean get() = board.startsWith("BRD")

    companion object {
        fun decode(bytes: ByteArray): BoardInfo {
            val b = le(bytes)
            return BoardInfo(b.u8(), Protocol.boardName(b.u8()), Sensor.fromMask(b.u8()))
        }
    }
}

/** ENS160 state, from the Air characteristic. Readings count only when NORMAL. */
enum class AirState(val label: String) {
    NORMAL("Normal"), WARM_UP("Warming up"), START_UP("First start-up"), INVALID("Invalid"), NO_SENSOR("No sensor");

    companion object {
        fun fromCode(code: Int) = when (code) {
            0 -> NORMAL
            1 -> WARM_UP
            2 -> START_UP
            3 -> INVALID
            else -> NO_SENSOR
        }
    }
}

/**
 * Air quality from the ENS160 (ESP32 Air board). Values are null unless the state is NORMAL.
 * The details after the first 10 bytes came later; older firmware leaves them null.
 */
data class AirReading(
    val uptimeMs: Long,
    val state: AirState,
    val aqi: Int?,
    val eco2Ppm: Int?,
    val tvocPpb: Int?,
    val statusRegister: Int? = null,
    val firmware: String? = null,
    /** Raw resistances of sensor elements 1 and 4, in ohms (datasheet: 2^(raw/2048)). */
    val r1Ohms: Double? = null,
    val r4Ohms: Double? = null,
    /** The temperature and humidity the ENS160 uses for its compensation. */
    val compensationC: Float? = null,
    val compensationPct: Float? = null,
) {
    companion object {
        const val SIZE = 10
        const val FULL_SIZE = 22

        fun decode(bytes: ByteArray): AirReading {
            require(bytes.size >= SIZE) { "Air needs $SIZE bytes, got ${bytes.size}" }
            val b = le(bytes)
            val uptime = b.u32()
            val state = AirState.fromCode(b.u8())
            val aqi = b.u8()
            val eco2 = b.u16()
            val tvoc = b.u16()
            val ok = state == AirState.NORMAL
            val base = AirReading(uptime, state, aqi.takeIf { ok && it in 1..5 }, eco2.takeIf { ok }, tvoc.takeIf { ok })
            if (bytes.size < FULL_SIZE) return base
            val status = b.u8()
            val fw = "${b.u8()}.${b.u8()}.${b.u8()}"
            val r1 = b.u16()
            val r4 = b.u16()
            val compC = b.i16()
            val compPct = b.u16()
            fun ohms(raw: Int) = if (raw == 0) null else Math.pow(2.0, raw / 2048.0)
            return base.copy(
                statusRegister = status,
                firmware = fw.takeIf { it != "0.0.0" },
                r1Ohms = ohms(r1),
                r4Ohms = ohms(r4),
                compensationC = if (compC == NONE_I16) null else compC / 100f,
                compensationPct = if (compC == NONE_I16) null else compPct / 100f,
            )
        }
    }
}

/** "No value" in the boards' signed 16-bit fields. */
private const val NONE_I16 = 0x7FFF

/** The board itself (ESP32 Air): System characteristic, 32 bytes. */
data class SystemInfo(
    val uptimeS: Long,
    val freeRam: Long,
    /** ESP32 die temperature, uncalibrated: well above room temperature. */
    val chipTemperatureC: Float?,
    val wifiRssi: Int?,
    val wifi: Boolean,
    val mqtt: Boolean,
    val bluetooth: Boolean,
    val ip: String?,
    val cpuMhz: Int,
    val resetCause: String,
    val micropython: String,
    val sensorErrors: Int,
    val integrityErrors: Int,
    /** Seconds the AHT21 spent above 80 %RH since start (long stays cause drift). */
    val humidSeconds: Long,
) {
    companion object {
        const val SIZE = 32

        fun decode(bytes: ByteArray): SystemInfo {
            require(bytes.size >= SIZE) { "System needs $SIZE bytes, got ${bytes.size}" }
            val b = le(bytes)
            val uptime = b.u32()
            val ram = b.u32()
            val chip = b.i16()
            val rssi = b.get().toInt()
            val flags = b.u8()
            val ip = (0 until 4).map { b.u8() }
            val mhz = b.u16()
            val reset = b.u8()
            val mp = "${b.u8()}.${b.u8()}.${b.u8()}"
            return SystemInfo(
                uptimeS = uptime,
                freeRam = ram,
                chipTemperatureC = if (chip == NONE_I16) null else chip / 100f,
                wifiRssi = rssi.takeIf { it != 0 },
                wifi = flags and 1 != 0,
                mqtt = flags and 2 != 0,
                bluetooth = flags and 4 != 0,
                ip = ip.takeIf { it.any { part -> part != 0 } }?.joinToString("."),
                cpuMhz = mhz,
                resetCause = when (reset) {
                    1 -> "Power on"
                    2 -> "Reset pin / USB"
                    3 -> "Watchdog"
                    4 -> "Deep sleep"
                    5 -> "Software"
                    else -> "Unknown ($reset)"
                },
                micropython = mp,
                sensorErrors = b.u16(),
                integrityErrors = b.u16(),
                humidSeconds = b.u32(),
            )
        }
    }
}

/** Values derived from the readings, using the sensors' datasheets. */
object Derived {
    private fun magnus(t: Double) = 17.62 * t / (243.12 + t)

    /** Dew point, °C (Magnus formula). */
    fun dewPoint(temperatureC: Float?, humidityPct: Float?): Float? {
        if (temperatureC == null || humidityPct == null || humidityPct <= 0f) return null
        val gamma = Math.log(humidityPct / 100.0) + magnus(temperatureC.toDouble())
        return (243.12 * gamma / (17.62 - gamma)).toFloat()
    }

    /** Water vapour, g/m³. */
    fun absoluteHumidity(temperatureC: Float?, humidityPct: Float?): Float? {
        if (temperatureC == null || humidityPct == null) return null
        return (216.7 * humidityPct / 100 * 6.112 * Math.exp(magnus(temperatureC.toDouble())) / (273.15 + temperatureC)).toFloat()
    }

    /** TVOC in µg/m³: the ENS160's TVOC is ethanol-calibrated (datasheet: DATA_ETOH = DATA_TVOC). */
    fun tvocUgm3(tvocPpb: Int?, temperatureC: Float?): Float? {
        if (tvocPpb == null) return null
        val molarVolume = 22.414 * (273.15 + (temperatureC ?: 25f)) / 273.15
        return (tvocPpb * 46.07 / molarVolume).toFloat()
    }

    /** ENS160 datasheet, table 5. */
    fun eco2Rating(ppm: Int?): String? = when {
        ppm == null -> null
        ppm < 600 -> "Excellent"
        ppm < 800 -> "Good"
        ppm < 1000 -> "Fair: ventilation optional"
        ppm < 1500 -> "Poor: ventilate"
        else -> "Bad: ventilation required"
    }

    /** How the air feels, from the dew point. */
    fun comfort(dewPointC: Float?): String? = when {
        dewPointC == null -> null
        dewPointC < 5 -> "Dry"
        dewPointC < 13 -> "Comfortable"
        dewPointC < 16 -> "Slightly humid"
        dewPointC < 19 -> "Humid"
        else -> "Muggy"
    }
}

/**
 * Calibration (ESP32 Air): the temperature offset and the self-heating
 * measurement (the board puts its air sensor to sleep and measures how much the
 * temperature drops). 20 bytes; older firmware sends only the 2-byte offset.
 */
data class CalibrationStatus(
    val offsetC: Float,
    /** Null when the board can't measure the offset itself (2-byte value). */
    val auto: Boolean? = null,
    val state: State = State.IDLE,
    val elapsedS: Int = 0,
    val cooldownS: Int = 0,
    val warmC: Float? = null,
    val nowC: Float? = null,
    val resultC: Float? = null,
    val resultAgeS: Long? = null,
    val failure: String? = null,
) {
    enum class State { IDLE, COOLING, DONE, FAILED }

    val canMeasure: Boolean get() = auto != null

    companion object {
        fun decode(bytes: ByteArray): CalibrationStatus {
            val b = le(bytes)
            val offset = b.i16() / 100f
            if (bytes.size < 20) return CalibrationStatus(offset)
            fun temp(raw: Int) = if (raw == NONE_I16) null else raw / 100f
            val auto = b.u8() != 0
            val state = when (b.u8()) {
                2 -> State.COOLING
                3 -> State.DONE
                4 -> State.FAILED
                else -> State.IDLE
            }
            val elapsed = b.u16()
            val cooldown = b.u16()
            val warm = temp(b.i16())
            val now = temp(b.i16())
            val result = temp(b.i16())
            val age = b.u32()
            val reason = when (b.u8()) {
                1 -> "a sensor isn't answering"
                2 -> "the room temperature changed during the measurement"
                3 -> "cancelled"
                4 -> "the result was out of range"
                else -> null
            }
            return CalibrationStatus(offset, auto, state, elapsed, cooldown, warm, now, result, age.takeIf { it != 0xFFFF_FFFFL }, reason)
        }
    }
}

/** The board's clock (ESP32 Air), Time characteristic: 12 bytes read, 7 written. */
data class BoardTime(
    /** Unix time, UTC; null if the board doesn't know the time yet. */
    val unixUtc: Long?,
    val offsetMin: Int,
    val dstRule: Int,
    val source: String,
    /** Offset in effect now (summer time included). */
    val currentOffsetMin: Int,
) {
    companion object {
        const val DST_NONE = 0
        const val DST_EU = 1
        const val DST_US = 2

        fun decode(bytes: ByteArray): BoardTime {
            val b = le(bytes)
            val unix = b.u32()
            val offset = b.i16()
            val dst = b.u8()
            val source = when (b.u8()) {
                1 -> "internet"
                2 -> "phone"
                else -> "unknown"
            }
            return BoardTime(unix.takeIf { it != 0L }, offset, dst, source, b.i16())
        }

        /** The phone's time and zone, as the board wants them. */
        fun encodeFromPhone(nowMs: Long, zone: java.util.TimeZone): ByteArray {
            val rule = when {
                !zone.useDaylightTime() -> DST_NONE
                zone.id.startsWith("Europe/") -> DST_EU
                zone.id.startsWith("America/") || zone.id.startsWith("US/") -> DST_US
                else -> DST_NONE
            }
            // Without a known rule, send the offset in effect now (right until the next change).
            val offsetMin = (if (rule == DST_NONE) zone.getOffset(nowMs) else zone.rawOffset) / 60_000
            return le(ByteArray(7)).apply {
                putInt((nowMs / 1000).toInt())
                putShort(offsetMin.toShort())
                put(rule.toByte())
            }.array()
        }
    }
}

/** The board's Wi-Fi (ESP32 Air): connection and scan results. */
data class WifiStatus(
    val state: State,
    val failure: String?,
    val rssi: Int?,
    val ip: String?,
    val ssid: String,
    val scanning: Boolean,
    val mqttEnabled: Boolean,
    val mqttConnected: Boolean,
    val networks: List<Pair<String, Int>>,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }

    companion object {
        fun decode(bytes: ByteArray): WifiStatus {
            val b = le(bytes)
            val state = State.entries.getOrElse(b.u8()) { State.IDLE }
            val reason = when (b.u8()) {
                1 -> "wrong password"
                2 -> "network not found"
                3 -> "couldn't connect"
                else -> null
            }
            val rssi = b.get().toInt()
            val ip = (0 until 4).map { b.u8() }
            val ssid = ByteArray(b.u8()).also { b.get(it) }.decodeToString()
            val scanning = b.u8() != 0
            val mqtt = b.u8()
            val networks = (0 until b.u8()).map {
                val name = ByteArray(b.u8()).also { b.get(it) }.decodeToString()
                name to b.get().toInt()
            }
            return WifiStatus(
                state, reason.takeIf { state == State.FAILED }, rssi.takeIf { it != 0 },
                ip.takeIf { it.any { p -> p != 0 } }?.joinToString("."), ssid, scanning,
                mqtt and 1 != 0, mqtt and 2 != 0, networks,
            )
        }

        fun scan() = byteArrayOf(1)
        fun forget() = byteArrayOf(3)
        fun mqtt(on: Boolean) = byteArrayOf(if (on) 4 else 5)
        fun connect(ssid: String, password: String): ByteArray {
            val s = ssid.encodeToByteArray()
            val p = password.encodeToByteArray()
            return byteArrayOf(2, s.size.toByte()) + s + byteArrayOf(p.size.toByte()) + p
        }
    }
}
