"""BLE Sensor wire protocol: UUIDs, packet layouts, value limits.

Mirrors firmware/src/ble/ble_protocol.h. Change both together. Every packet
is packed little-endian; the struct format strings below spell out the fields.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Optional

PROTOCOL_VERSION = 1


def _uuid(short: int) -> str:
    """Full 128-bit UUID of a BLE Sensor attribute (they differ in one byte)."""
    return f"a7e400{short:02x}-5c2b-4f1a-9d3e-6b8c0f2e1d47"


SERVICE_UUID = _uuid(0x00)
ENV_UUID = _uuid(0x01)
MOTION_UUID = _uuid(0x02)
BUTTON_UUID = _uuid(0x03)
LED_UUID = _uuid(0x04)
CONFIG_UUID = _uuid(0x05)
INFO_UUID = _uuid(0x06)
COMMAND_UUID = _uuid(0x07)
NAME_UUID = _uuid(0x08)
DISPLAY_UUID = _uuid(0x09)
AIR_UUID = _uuid(0x0A)       # ESP32 Air board only
SYSTEM_UUID = _uuid(0x0B)    # ESP32 Air board only

# Advertised manufacturer data: company id -> (board id, protocol version)
ADV_COMPANY_ID = 0x02FF  # Silicon Laboratories

BOARDS = {0x0A: "BRD4184A", 0x0B: "BRD4184B", 0x0C: "ESP32 Air"}
ESP32_AIR = "ESP32 Air"   # ESP32 + ENS160/AHT21 air monitor: no motion, button, LED or config

# Sensor bits used by Config.sensor_mask and Info.available
SENSOR_BITS = {
    "rht": 1 << 0,     # temperature + humidity
    "light": 1 << 1,   # lux (+ UV on BRD4184A)
    "hall": 1 << 2,    # magnetic field
    "imu": 1 << 3,     # accelerometer + gyroscope
    "sound": 1 << 4,   # microphone (BRD4184B)
    "supply": 1 << 5,  # supply voltage
    "air": 1 << 6,     # ENS160 air quality (ESP32 Air board): reported in Info only
}
# Sensors Config can switch on and off (the Thunderboard's); not "air".
CONFIG_SENSORS = [name for name in SENSOR_BITS if name != "air"]

LED_MODES = {"off": 0, "on": 1, "blink": 2}

# Readings the optional OLED display can show, in bit order of Display.page_mask
DISPLAY_PAGES = [
    "temperature", "humidity", "light", "uv", "magnetic",
    "sound", "supply", "chip-temperature", "orientation", "button",
    "air", "eco2", "tvoc", "dewpoint", "sensor", "system",   # bits 10-15: ESP32 Air board
]
THUNDERBOARD_PAGES = DISPLAY_PAGES[:10]
ESP32_AIR_PAGES = ["temperature", "humidity", "air", "eco2", "tvoc", "dewpoint", "sensor", "system"]
DISPLAY_PAGES_ALL = (1 << len(THUNDERBOARD_PAGES)) - 1   # Thunderboard
DISPLAY_PAGE_TIME_S = (1.0, 60.0)   # allowed seconds per reading

COMMANDS = {
    "calibrate": 0x01,
    "factory-reset": 0x02,
    "reboot": 0x03,
    "identify": 0x04,
    "reset-orientation": 0x05,
}

# Accepted Config values (the board rejects others with ATT error 0x13)
LIMITS = {
    "env_period_ms": (100, 60000),
    "motion_period_ms": (10, 1000),
    "tx_power_dbm": (-30.0, 6.0),
    "adv_interval_ms": (20, 10240),
    "hall_threshold_mt": (0.1, 20.0),
}
NAME_MAX_LEN = 20  # bytes of UTF-8

# Env.valid bits
_VALID_TEMPERATURE = 1 << 0
_VALID_HUMIDITY = 1 << 1
_VALID_LUX = 1 << 2
_VALID_UV = 1 << 3
_VALID_HALL = 1 << 4
_VALID_SOUND = 1 << 5
_VALID_SUPPLY = 1 << 6
_VALID_DIE_TEMPERATURE = 1 << 7

_HALL_FLAG_ALERT = 1 << 0
_HALL_FLAG_TAMPER = 1 << 1


def sensor_names(mask: int) -> list[str]:
    """Sensor names whose bit is set in mask."""
    return [name for name, bit in SENSOR_BITS.items() if mask & bit]


def sensor_mask(names: list[str]) -> int:
    """Sensor mask from names; raises ValueError for unknown names."""
    unknown = set(names) - set(CONFIG_SENSORS)
    if unknown:
        raise ValueError(f"unknown sensors: {', '.join(sorted(unknown))}")
    return sum(SENSOR_BITS[name] for name in names)


@dataclass
class Env:
    """Environmental readings. A value is None if its sensor is off or absent."""

    uptime_ms: int
    temperature_c: Optional[float]
    humidity_pct: Optional[float]
    lux: Optional[float]
    uv_index: Optional[float]
    hall_mt: Optional[float]
    hall_alert: bool
    hall_tamper: bool
    sound_db: Optional[float]
    supply_v: Optional[float]
    die_temperature_c: Optional[float]

    # uptime, valid, temperature×100, humidity×100, lux×100, UV×100, hall µT,
    # hall flags, sound×100, supply mV, die temperature×100
    FORMAT = struct.Struct("<I H h H I H i B h H h")

    @classmethod
    def decode(cls, data: bytes) -> "Env":
        (uptime, valid, temperature, humidity, lux, uv, hall_ut, hall_flags,
         sound, supply_mv, die_temperature) = cls.FORMAT.unpack(data[: cls.FORMAT.size])

        def if_valid(bit: int, value: float) -> Optional[float]:
            return value if valid & bit else None

        hall_valid = bool(valid & _VALID_HALL)
        return cls(
            uptime_ms=uptime,
            temperature_c=if_valid(_VALID_TEMPERATURE, temperature / 100),
            humidity_pct=if_valid(_VALID_HUMIDITY, humidity / 100),
            lux=if_valid(_VALID_LUX, lux / 100),
            uv_index=if_valid(_VALID_UV, uv / 100),
            hall_mt=if_valid(_VALID_HALL, hall_ut / 1000),
            hall_alert=hall_valid and bool(hall_flags & _HALL_FLAG_ALERT),
            hall_tamper=hall_valid and bool(hall_flags & _HALL_FLAG_TAMPER),
            sound_db=if_valid(_VALID_SOUND, sound / 100),
            supply_v=if_valid(_VALID_SUPPLY, supply_mv / 1000),
            die_temperature_c=if_valid(_VALID_DIE_TEMPERATURE, die_temperature / 100),
        )


@dataclass
class Motion:
    """One IMU sample."""

    uptime_ms: int
    accel_g: tuple[float, float, float]
    gyro_dps: tuple[float, float, float]
    orientation_deg: tuple[float, float, float]  # roll, pitch, yaw

    # uptime, accel mg ×3, gyro °/s×100 ×3, orientation °×100 ×3
    FORMAT = struct.Struct("<I 3h 3h 3h")

    @classmethod
    def decode(cls, data: bytes) -> "Motion":
        values = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        return cls(
            uptime_ms=values[0],
            accel_g=tuple(v / 1000 for v in values[1:4]),
            gyro_dps=tuple(v / 100 for v in values[4:7]),
            orientation_deg=tuple(v / 100 for v in values[7:10]),
        )


@dataclass
class Button:
    pressed: bool
    press_count: int  # since boot

    FORMAT = struct.Struct("<B I")

    @classmethod
    def decode(cls, data: bytes) -> "Button":
        pressed, press_count = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        return cls(bool(pressed), press_count)


@dataclass
class Led:
    mode: str  # "off", "on" or "blink"
    on_ms: int
    off_ms: int

    FORMAT = struct.Struct("<B H H")

    @classmethod
    def decode(cls, data: bytes) -> "Led":
        mode, on_ms, off_ms = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        name = next((k for k, v in LED_MODES.items() if v == mode), str(mode))
        return cls(name, on_ms, off_ms)

    def encode(self) -> bytes:
        return self.FORMAT.pack(LED_MODES[self.mode], self.on_ms, self.off_ms)


@dataclass
class Config:
    """Settings stored on the board. tx_power_dbm and adv_interval_ms take
    effect after the client disconnects."""

    sensor_mask: int
    env_period_ms: int
    motion_period_ms: int
    tx_power_dbm: float
    adv_interval_ms: int
    hall_threshold_mt: float

    # version, sensor mask, env period, motion period, TX power ×10,
    # advertising interval, hall threshold µT
    FORMAT = struct.Struct("<B B H H h H H")

    @classmethod
    def decode(cls, data: bytes) -> "Config":
        _, mask, env_period, motion_period, tx_x10, adv_interval, hall_ut = \
            cls.FORMAT.unpack(data[: cls.FORMAT.size])
        return cls(mask, env_period, motion_period, tx_x10 / 10, adv_interval, hall_ut / 1000)

    def encode(self) -> bytes:
        return self.FORMAT.pack(
            PROTOCOL_VERSION,
            self.sensor_mask,
            self.env_period_ms,
            self.motion_period_ms,
            round(self.tx_power_dbm * 10),
            self.adv_interval_ms,
            round(self.hall_threshold_mt * 1000),
        )

    @property
    def sensors(self) -> list[str]:
        return sensor_names(self.sensor_mask)


@dataclass
class Display:
    """The optional OLED: whether one is connected, which readings it shows,
    and for how long each."""

    present: bool
    pages: list[str]      # names from DISPLAY_PAGES
    page_time_s: float    # seconds per reading, DISPLAY_PAGE_TIME_S

    # present (ignored on write), page mask, milliseconds per reading
    FORMAT = struct.Struct("<B H H")

    @classmethod
    def decode(cls, data: bytes) -> "Display":
        present, mask, page_ms = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        pages = [name for i, name in enumerate(DISPLAY_PAGES) if mask & (1 << i)]
        return cls(bool(present), pages, page_ms / 1000)

    def encode(self) -> bytes:
        return self.FORMAT.pack(int(self.present), display_page_mask(self.pages), round(self.page_time_s * 1000))


def display_page_mask(pages: list[str]) -> int:
    """Display page mask from page names; raises ValueError for unknown names."""
    unknown = set(pages) - set(DISPLAY_PAGES)
    if unknown:
        raise ValueError(f"unknown display pages: {', '.join(sorted(unknown))}")
    return sum(1 << DISPLAY_PAGES.index(name) for name in pages)


@dataclass
class Info:
    protocol_version: int
    board: str            # "BRD4184A" / "BRD4184B"
    available: list[str]  # sensor names present on this board

    FORMAT = struct.Struct("<B B B x")

    @classmethod
    def decode(cls, data: bytes) -> "Info":
        version, board_id, mask = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        return cls(version, BOARDS.get(board_id, hex(board_id)), sensor_names(mask))


def board_pages(board: str) -> list[str]:
    """The display pages a board has ("--pages all")."""
    return ESP32_AIR_PAGES if board == ESP32_AIR else THUNDERBOARD_PAGES


AIR_STATES = {0: "normal", 1: "warm-up", 2: "start-up", 3: "invalid", 0xFF: "no sensor"}


@dataclass
class Air:
    """ENS160 air quality (ESP32 Air board). Values are None unless state is "normal".
    The details after the first 10 bytes are None with older firmware."""
    uptime_ms: int
    state: str
    aqi: Optional[int]          # 1 excellent ... 5 unhealthy (UBA)
    eco2_ppm: Optional[int]
    tvoc_ppb: Optional[int]
    status: Optional[int] = None             # raw DEVICE_STATUS register
    firmware: Optional[str] = None           # ENS160 firmware
    r1_ohms: Optional[float] = None          # raw resistance, sensor element 1
    r4_ohms: Optional[float] = None          # raw resistance, sensor element 4
    compensation_c: Optional[float] = None   # what the ENS160 uses for compensation
    compensation_pct: Optional[float] = None

    FORMAT = struct.Struct("<I B B H H")
    DETAILS = struct.Struct("<B B B B H H h H")

    @classmethod
    def decode(cls, data: bytes) -> "Air":
        uptime, state, aqi, eco2, tvoc = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        ok = state == 0
        air = cls(uptime, AIR_STATES.get(state, "no sensor"), aqi if ok and 1 <= aqi <= 5 else None,
                  eco2 if ok else None, tvoc if ok else None)
        if len(data) >= cls.FORMAT.size + cls.DETAILS.size:
            status, major, minor, release, r1, r4, comp_t, comp_rh = cls.DETAILS.unpack_from(data, cls.FORMAT.size)
            air.status = status
            air.firmware = f"{major}.{minor}.{release}"
            air.r1_ohms = 2 ** (r1 / 2048) if r1 else None
            air.r4_ohms = 2 ** (r4 / 2048) if r4 else None
            if comp_t != 0x7FFF:
                air.compensation_c, air.compensation_pct = comp_t / 100, comp_rh / 100
        return air


RESET_CAUSES = {1: "power on", 2: "reset pin / USB", 3: "watchdog", 4: "deep sleep", 5: "software"}


@dataclass
class System:
    """The ESP32 Air board itself."""
    uptime_s: int
    free_ram: int
    chip_temperature_c: Optional[float]   # die temperature, uncalibrated
    wifi_rssi: Optional[int]
    wifi: bool
    mqtt: bool
    bluetooth: bool
    ip: Optional[str]
    cpu_mhz: int
    reset_cause: str
    micropython: str
    sensor_errors: int
    integrity_errors: int     # ENS160 checksum mismatches
    humid_s: int              # seconds above 80 %RH (AHT21 drift risk)

    FORMAT = struct.Struct("<I I h b B 4s H B B B B H H I H")

    @classmethod
    def decode(cls, data: bytes) -> "System":
        (uptime, ram, chip, rssi, flags, ip, mhz, reset, mp1, mp2, mp3,
         errors, integrity, humid, _) = cls.FORMAT.unpack(data[: cls.FORMAT.size])
        return cls(uptime, ram, None if chip == 0x7FFF else chip / 100, rssi or None,
                   bool(flags & 1), bool(flags & 2), bool(flags & 4),
                   ".".join(map(str, ip)) if any(ip) else None, mhz, RESET_CAUSES.get(reset, str(reset)),
                   f"{mp1}.{mp2}.{mp3}", errors, integrity, humid)
