#!/usr/bin/env python3
"""Bluetooth client for the Thunderboard BG22 "TB Game" firmware.

Usable as a CLI (see `tb_game.py --help`) or as a library:

    async with await TBGame.connect() as tb:
        print(await tb.read_env())
        await tb.set_led("blink", on_ms=100, off_ms=900)
        await tb.set_config(motion_period_ms=20)

Binary layouts mirror firmware/src/tb_protocol.h (packed, little-endian).
"""

from __future__ import annotations

import argparse
import asyncio
import struct
import sys
from dataclasses import dataclass, fields, replace
from typing import Awaitable, Callable, Optional, Union

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

SERVICE_UUID = "a7e40000-5c2b-4f1a-9d3e-6b8c0f2e1d47"
ENV_UUID = "a7e40001-5c2b-4f1a-9d3e-6b8c0f2e1d47"
MOTION_UUID = "a7e40002-5c2b-4f1a-9d3e-6b8c0f2e1d47"
BUTTON_UUID = "a7e40003-5c2b-4f1a-9d3e-6b8c0f2e1d47"
LED_UUID = "a7e40004-5c2b-4f1a-9d3e-6b8c0f2e1d47"
CONFIG_UUID = "a7e40005-5c2b-4f1a-9d3e-6b8c0f2e1d47"
INFO_UUID = "a7e40006-5c2b-4f1a-9d3e-6b8c0f2e1d47"
COMMAND_UUID = "a7e40007-5c2b-4f1a-9d3e-6b8c0f2e1d47"
NAME_UUID = "a7e40008-5c2b-4f1a-9d3e-6b8c0f2e1d47"

SENSORS = {"rht": 0x01, "light": 0x02, "hall": 0x04, "imu": 0x08, "sound": 0x10, "battery": 0x20}
BOARDS = {0x0A: "BRD4184A", 0x0B: "BRD4184B"}
ADV_COMPANY_ID = 0x02FF  # Silicon Labs; advertised payload: board id, protocol version

# bleak asks BlueZ to drop repeated advertisements (DuplicateData=False), so a
# board BlueZ already knows can go unreported for a whole scan. The firmware
# advertises manufacturer data, which BlueZ re-reports on every packet when
# DuplicateData is True. Ignored on non-Linux platforms.
SCAN_ARGS = {"bluez": {"filters": {"DuplicateData": True}}}
SCAN_SESSION_S = 3.0
LED_MODES = {"off": 0, "on": 1, "blink": 2}
STREAMS = ["env", "motion", "button"]
COMMANDS = {
    "calibrate": 0x01,
    "factory-reset": 0x02,
    "reboot": 0x03,
    "identify": 0x04,
    "reset-orientation": 0x05,
}

_ENV = struct.Struct("<IHhHIHiBhHh")
_MOTION = struct.Struct("<I3h3h3h")
_BUTTON = struct.Struct("<BI")
_LED = struct.Struct("<BHH")
_CONFIG = struct.Struct("<BBHHhHH")
_INFO = struct.Struct("<BBBB")


def _mask_to_names(mask: int) -> list[str]:
    return [name for name, bit in SENSORS.items() if mask & bit]


@dataclass
class Env:
    uptime_ms: int
    temperature_c: Optional[float]
    humidity_pct: Optional[float]
    lux: Optional[float]
    uv_index: Optional[float]
    hall_mt: Optional[float]
    hall_alert: bool
    hall_tamper: bool
    sound_db: Optional[float]
    battery_v: Optional[float]
    die_temperature_c: Optional[float]

    @classmethod
    def decode(cls, data: bytes) -> "Env":
        (uptime, valid, temp, hum, lux, uvi, hall, hall_flags, sound, batt, die) = _ENV.unpack(data[: _ENV.size])

        def pick(bit: int, value: float) -> Optional[float]:
            return value if valid & bit else None

        return cls(
            uptime_ms=uptime,
            temperature_c=pick(0x01, temp / 100),
            humidity_pct=pick(0x02, hum / 100),
            lux=pick(0x04, lux / 100),
            uv_index=pick(0x08, uvi / 100),
            hall_mt=pick(0x10, hall / 1000),
            hall_alert=bool(valid & 0x10 and hall_flags & 0x01),
            hall_tamper=bool(valid & 0x10 and hall_flags & 0x02),
            sound_db=pick(0x20, sound / 100),
            battery_v=pick(0x40, batt / 1000),
            die_temperature_c=pick(0x80, die / 100),
        )


@dataclass
class Motion:
    uptime_ms: int
    accel_g: tuple[float, float, float]
    gyro_dps: tuple[float, float, float]
    orientation_deg: tuple[float, float, float]  # roll, pitch, yaw

    @classmethod
    def decode(cls, data: bytes) -> "Motion":
        v = _MOTION.unpack(data[: _MOTION.size])
        return cls(
            uptime_ms=v[0],
            accel_g=tuple(x / 1000 for x in v[1:4]),
            gyro_dps=tuple(x / 100 for x in v[4:7]),
            orientation_deg=tuple(x / 100 for x in v[7:10]),
        )


@dataclass
class Button:
    pressed: bool
    press_count: int

    @classmethod
    def decode(cls, data: bytes) -> "Button":
        pressed, count = _BUTTON.unpack(data[: _BUTTON.size])
        return cls(bool(pressed), count)


@dataclass
class Led:
    mode: str
    on_ms: int
    off_ms: int

    @classmethod
    def decode(cls, data: bytes) -> "Led":
        mode, on_ms, off_ms = _LED.unpack(data[: _LED.size])
        name = next((k for k, v in LED_MODES.items() if v == mode), str(mode))
        return cls(name, on_ms, off_ms)

    def encode(self) -> bytes:
        return _LED.pack(LED_MODES[self.mode], self.on_ms, self.off_ms)


@dataclass
class Config:
    sensor_mask: int
    env_period_ms: int
    motion_period_ms: int
    tx_power_dbm: float
    adv_interval_ms: int
    hall_threshold_mt: float

    @classmethod
    def decode(cls, data: bytes) -> "Config":
        _, mask, env_p, mot_p, tx, adv, hall = _CONFIG.unpack(data[: _CONFIG.size])
        return cls(mask, env_p, mot_p, tx / 10, adv, hall / 1000)

    def encode(self) -> bytes:
        return _CONFIG.pack(
            1,
            self.sensor_mask,
            self.env_period_ms,
            self.motion_period_ms,
            round(self.tx_power_dbm * 10),
            self.adv_interval_ms,
            round(self.hall_threshold_mt * 1000),
        )

    @property
    def sensors(self) -> list[str]:
        return _mask_to_names(self.sensor_mask)


@dataclass
class Info:
    protocol_version: int
    board: str
    available: list[str]

    @classmethod
    def decode(cls, data: bytes) -> "Info":
        version, board, mask, _ = _INFO.unpack(data[: _INFO.size])
        return cls(version, BOARDS.get(board, hex(board)), _mask_to_names(mask))


Callback = Callable[[object], Union[None, Awaitable[None]]]


class TBGame:
    """Connection to one board running the TB Game firmware."""

    def __init__(self, client: BleakClient):
        self.client = client

    # --- discovery / connection -------------------------------------------

    @staticmethod
    def _is_board(_device: BLEDevice, adv: AdvertisementData) -> bool:
        return SERVICE_UUID in adv.service_uuids

    @staticmethod
    async def _find(match, timeout: float) -> Optional[BLEDevice]:
        """Scans until match(device, adv) is true, in short back-to-back sessions.

        Some Linux controllers keep their duplicate filter across discovery
        sessions, so a single long scan can stay blind to a board that is
        advertising; restarting discovery every few seconds avoids that.
        """
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while (remaining := deadline - loop.time()) > 0:
            device = await BleakScanner.find_device_by_filter(
                match, timeout=min(SCAN_SESSION_S, remaining), **SCAN_ARGS)
            if device is not None:
                return device
        return None

    @staticmethod
    async def scan(timeout: float = 6.0) -> list[tuple[BLEDevice, int, str]]:
        """Returns (device, rssi, board revision) for every TB Game board in range."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        found: dict[str, tuple[BLEDevice, AdvertisementData]] = {}
        while (remaining := deadline - loop.time()) > 0:
            found.update(await BleakScanner.discover(
                timeout=min(SCAN_SESSION_S, remaining), return_adv=True, **SCAN_ARGS))
        boards = []
        for device, adv in found.values():
            if TBGame._is_board(device, adv):
                mfg = adv.manufacturer_data.get(ADV_COMPANY_ID, b"")
                board = BOARDS.get(mfg[0], "?") if mfg else "?"
                boards.append((device, adv.rssi, board))
        return sorted(boards, key=lambda b: -b[1])

    @staticmethod
    async def _bluez_known_boards() -> list[BLEDevice]:
        """Boards BlueZ already has in its device cache (Linux only).

        In crowded radio environments a short scan can miss a board that is
        advertising; BlueZ can still connect to a cached device directly.
        """
        if not sys.platform.startswith("linux"):
            return []
        try:
            from dbus_fast import BusType, Message
            from dbus_fast.aio import MessageBus
        except ImportError:
            return []

        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        try:
            reply = await bus.call(Message(destination="org.bluez", path="/",
                                           interface="org.freedesktop.DBus.ObjectManager",
                                           member="GetManagedObjects"))
        finally:
            bus.disconnect()
        boards = []
        for path, interfaces in reply.body[0].items():
            device = interfaces.get("org.bluez.Device1")
            if device is None:
                continue
            props = {key: variant.value for key, variant in device.items()}
            if SERVICE_UUID in props.get("UUIDs", []):
                boards.append(BLEDevice(props["Address"], props.get("Name"), {"path": path, "props": props}))
        return boards

    @classmethod
    async def connect(cls, address: Optional[str] = None, name: Optional[str] = None,
                      timeout: float = 10.0, scan_timeout: float = 10.0) -> "TBGame":
        """Connects to the board with this address or name, else the first one found."""

        def wanted(device: BLEDevice, adv: Optional[AdvertisementData] = None) -> bool:
            if address is not None:
                return device.address.upper() == address.upper()
            if name is not None:
                return name in (device.name, adv.local_name if adv else None)
            return adv is None or cls._is_board(device, adv)

        device = await cls._find(wanted, scan_timeout)
        candidates = [device] if device else [d for d in await cls._bluez_known_boards() if wanted(d)]
        if not candidates:
            raise RuntimeError("No TB Game board found. Is the firmware flashed and the board powered?")

        error: Optional[Exception] = None
        for target in candidates:
            client = BleakClient(target, timeout=timeout)
            try:
                await client.connect()
                return cls(client)
            except Exception as e:  # out of range, or a stale cache entry
                error = e
        raise RuntimeError(f"Could not connect to a TB Game board: {error}")

    async def disconnect(self) -> None:
        await self.client.disconnect()

    async def __aenter__(self) -> "TBGame":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.disconnect()

    # --- reads ----------------------------------------------------------

    async def read_info(self) -> Info:
        return Info.decode(await self.client.read_gatt_char(INFO_UUID))

    async def read_env(self) -> Env:
        return Env.decode(await self.client.read_gatt_char(ENV_UUID))

    async def read_motion(self) -> Motion:
        return Motion.decode(await self.client.read_gatt_char(MOTION_UUID))

    async def read_button(self) -> Button:
        return Button.decode(await self.client.read_gatt_char(BUTTON_UUID))

    async def read_led(self) -> Led:
        return Led.decode(await self.client.read_gatt_char(LED_UUID))

    async def read_config(self) -> Config:
        return Config.decode(await self.client.read_gatt_char(CONFIG_UUID))

    async def read_name(self) -> str:
        return (await self.client.read_gatt_char(NAME_UUID)).decode(errors="replace")

    # --- control / configuration ---------------------------------------

    async def set_led(self, mode: str, on_ms: int = 500, off_ms: int = 500) -> None:
        """mode: 'off', 'on' or 'blink'."""
        await self.client.write_gatt_char(LED_UUID, Led(mode, on_ms, off_ms).encode(), response=True)

    async def set_config(self, sensors: Optional[list[str]] = None, **changes) -> Config:
        """Updates only the given Config fields (persisted on the board).

        sensors: list of names from SENSORS, as an alternative to sensor_mask.
        tx_power_dbm and adv_interval_ms take effect on the next advertising
        cycle, i.e. after disconnecting.
        """
        config = await self.read_config()
        if sensors is not None:
            changes["sensor_mask"] = sum(SENSORS[s] for s in sensors)
        config = replace(config, **changes)
        await self.client.write_gatt_char(CONFIG_UUID, config.encode(), response=True)
        return config

    async def set_name(self, name: str) -> None:
        """Persistent; advertised after the next disconnect. Max 20 bytes."""
        raw = name.encode()
        if not 0 < len(raw) <= 20:
            raise ValueError("name must be 1-20 bytes")
        await self.client.write_gatt_char(NAME_UUID, raw, response=True)

    async def command(self, name: str) -> None:
        """One of COMMANDS: calibrate, factory-reset, reboot, identify, reset-orientation."""
        await self.client.write_gatt_char(COMMAND_UUID, bytes([COMMANDS[name]]), response=True)

    # --- notifications ---------------------------------------------------

    async def _subscribe(self, uuid: str, decode, callback: Callback) -> None:
        async def handler(_sender, data: bytearray) -> None:
            result = callback(decode(bytes(data)))
            if asyncio.iscoroutine(result):
                await result

        await self.client.start_notify(uuid, handler)

    async def on_env(self, callback: Callback) -> None:
        await self._subscribe(ENV_UUID, Env.decode, callback)

    async def on_motion(self, callback: Callback) -> None:
        await self._subscribe(MOTION_UUID, Motion.decode, callback)

    async def on_button(self, callback: Callback) -> None:
        await self._subscribe(BUTTON_UUID, Button.decode, callback)


# --- CLI -----------------------------------------------------------------


def _fmt(value, unit: str = "", digits: int = 2) -> str:
    if value is None:
        return "n/a"
    return f"{value:.{digits}f}{unit}"


def _print_env(env: Env) -> None:
    hall = _fmt(env.hall_mt, " mT", 3)
    if env.hall_alert:
        hall += " [ALERT]"
    if env.hall_tamper:
        hall += " [TAMPER]"
    print(
        f"[{env.uptime_ms / 1000:9.2f}s] "
        f"T={_fmt(env.temperature_c, '°C')} RH={_fmt(env.humidity_pct, '%')} "
        f"light={_fmt(env.lux, ' lx', 1)} UV={_fmt(env.uv_index)} hall={hall} "
        f"sound={_fmt(env.sound_db, ' dB', 1)} supply={_fmt(env.battery_v, ' V', 3)} "
        f"die={_fmt(env.die_temperature_c, '°C', 1)}"
    )


def _print_motion(m: Motion) -> None:
    a, g, o = m.accel_g, m.gyro_dps, m.orientation_deg
    print(
        f"[{m.uptime_ms / 1000:9.2f}s] "
        f"acc=({a[0]:+.3f},{a[1]:+.3f},{a[2]:+.3f}) g  "
        f"gyro=({g[0]:+7.2f},{g[1]:+7.2f},{g[2]:+7.2f}) °/s  "
        f"roll/pitch/yaw=({o[0]:+7.2f},{o[1]:+7.2f},{o[2]:+7.2f})°"
    )


def _print_button(b: Button) -> None:
    print(f"button {'PRESSED' if b.pressed else 'released'} (presses: {b.press_count})")


def _print_config(c: Config) -> None:
    for f in fields(c):
        print(f"  {f.name:18} {getattr(c, f.name)}")
    print(f"  {'sensors':18} {', '.join(c.sensors) or 'none'}")


async def _run(args: argparse.Namespace) -> None:
    if args.cmd == "scan":
        boards = await TBGame.scan(timeout=args.timeout)
        if not boards:
            print("No TB Game boards found.")
        for device, rssi, board in boards:
            print(f"{device.address}  {rssi:4d} dBm  {board}  {device.name}")
        return

    tb = await TBGame.connect(address=args.address, name=args.name)
    async with tb:
        print(f"Connected to {await tb.read_name()} ({tb.client.address})", file=sys.stderr)

        if args.cmd == "info":
            info = await tb.read_info()
            print(f"board:     {info.board}")
            print(f"protocol:  v{info.protocol_version}")
            print(f"sensors:   {', '.join(info.available)}")
            print("config:")
            _print_config(await tb.read_config())
            led = await tb.read_led()
            print(f"led:       {led.mode} (on {led.on_ms} ms / off {led.off_ms} ms)")

        elif args.cmd == "read":
            # Values are refreshed by the board right after connecting.
            _print_env(await tb.read_env())
            try:
                _print_motion(await tb.read_motion())
            except struct.error:
                print("motion: n/a")
            _print_button(await tb.read_button())

        elif args.cmd == "monitor":
            streams = set(args.streams or STREAMS)
            if streams - set(STREAMS):
                raise SystemExit(f"unknown streams: {', '.join(sorted(streams - set(STREAMS)))}")
            if "env" in streams:
                await tb.on_env(_print_env)
            if "motion" in streams:
                await tb.on_motion(_print_motion)
            if "button" in streams:
                await tb.on_button(_print_button)
            print("Monitoring, Ctrl+C to stop.", file=sys.stderr)
            if args.duration:
                await asyncio.sleep(args.duration)
            else:
                await asyncio.Event().wait()

        elif args.cmd == "led":
            await tb.set_led(args.mode, args.on_ms, args.off_ms)

        elif args.cmd == "config":
            changes = {k: v for k, v in vars(args).items()
                       if k in {f.name for f in fields(Config)} and v is not None}
            if args.sensors is not None or changes:
                sensors = None
                if args.sensors is not None:
                    sensors = [] if args.sensors == "none" else args.sensors.split(",")
                    unknown = set(sensors) - set(SENSORS)
                    if unknown:
                        raise SystemExit(f"unknown sensors: {', '.join(sorted(unknown))}")
                await tb.set_config(sensors=sensors, **changes)
            _print_config(await tb.read_config())

        elif args.cmd == "name":
            await tb.set_name(args.new_name)
            print("Name saved; it is advertised after this disconnect.")

        elif args.cmd in COMMANDS:
            if args.cmd == "calibrate":
                print("Keep the board still...", file=sys.stderr)
            await tb.command(args.cmd)


def main() -> None:
    p = argparse.ArgumentParser(description="Thunderboard BG22 TB Game BLE client")
    p.add_argument("-a", "--address", help="board Bluetooth address (default: strongest board found)")
    p.add_argument("-n", "--name", help="connect by advertised name instead")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan", help="list boards in range")
    s.add_argument("-t", "--timeout", type=float, default=6.0)
    sub.add_parser("info", help="board revision, sensors, config, LED")
    sub.add_parser("read", help="read every sensor once")
    m = sub.add_parser("monitor", help="stream live sensor notifications")
    # No choices=: Python 3.10 argparse rejects an empty nargs="*" list with choices.
    m.add_argument("streams", nargs="*", help=f"any of {', '.join(STREAMS)} (default: all)")
    m.add_argument("-d", "--duration", type=float, help="stop after N seconds")

    led = sub.add_parser("led", help="control the LED")
    led.add_argument("mode", choices=list(LED_MODES))
    led.add_argument("--on-ms", type=int, default=500)
    led.add_argument("--off-ms", type=int, default=500)

    c = sub.add_parser("config", help="show or change the persistent configuration")
    c.add_argument("--sensors", help=f"comma list of {','.join(SENSORS)} or 'none'")
    c.add_argument("--env-period", dest="env_period_ms", type=int, help="ms, 100-60000")
    c.add_argument("--motion-period", dest="motion_period_ms", type=int, help="ms, 10-1000")
    c.add_argument("--tx-power", dest="tx_power_dbm", type=float, help="dBm, -30 to 6 (after reconnect)")
    c.add_argument("--adv-interval", dest="adv_interval_ms", type=int, help="ms, 20-10240 (after reconnect)")
    c.add_argument("--hall-threshold", dest="hall_threshold_mt", type=float, help="mT, 0.1-20")

    n = sub.add_parser("name", help="rename the board (persistent)")
    n.add_argument("new_name")

    sub.add_parser("calibrate", help="gyro calibration; keep the board still")
    sub.add_parser("reset-orientation", help="zero roll/pitch/yaw")
    sub.add_parser("identify", help="blink the LED fast for 3 s")
    sub.add_parser("factory-reset", help="restore default config and name")
    sub.add_parser("reboot", help="reboot the board")

    args = p.parse_args()
    try:
        asyncio.run(_run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
