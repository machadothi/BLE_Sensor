"""BleSensor: a connection to one board running the BLE Sensor firmware."""

from __future__ import annotations

import asyncio
from dataclasses import replace
from typing import Awaitable, Callable, Optional, Union

from bleak import BleakClient
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from . import discovery
from .protocol import (
    AIR_UUID, BUTTON_UUID, COMMAND_UUID, COMMANDS, CONFIG_UUID, DISPLAY_UUID, ENV_UUID, INFO_UUID, LED_UUID,
    MOTION_UUID, NAME_MAX_LEN, NAME_UUID, Air, Button, Config, Display, Env, Info, Led, Motion, sensor_mask,
)

CONNECT_ATTEMPTS = 2

# A notification callback; may be a plain function or a coroutine function.
Callback = Callable[[object], Union[None, Awaitable[None]]]


class BleSensor:
    """Use as `async with await BleSensor.connect() as board: ...`."""

    def __init__(self, client: BleakClient):
        self.client = client  # the underlying bleak client, for raw access

    # --- connection ------------------------------------------------------

    @staticmethod
    async def scan(timeout: float = 6.0) -> list[tuple[BLEDevice, int, str]]:
        """(device, rssi, board revision) of every board in range, strongest first."""
        return await discovery.scan_boards(timeout)

    @classmethod
    async def connect(cls, address: Optional[str] = None, name: Optional[str] = None,
                      timeout: float = 10.0, scan_timeout: float = 10.0) -> "BleSensor":
        """Connects by address, by advertised name, or to the first board found."""

        def wanted(device: BLEDevice, adv: Optional[AdvertisementData] = None) -> bool:
            if address is not None:
                return device.address.upper() == address.upper()
            if name is not None:
                return name in (device.name, adv.local_name if adv else None)
            # Without an advertisement (cache entries) the UUID was checked already.
            return adv is None or discovery.is_board(device, adv)

        # Linux discovery is occasionally blind for a while (see discovery.py),
        # and a cache entry can vanish before we use it, so try the whole
        # scan-then-cache sequence twice before giving up.
        error: Optional[Exception] = None
        for _attempt in range(CONNECT_ATTEMPTS):
            device = await discovery.find_device(wanted, scan_timeout)
            if device is not None:
                candidates = [device]
            else:
                candidates = [d for d in await discovery.cached_boards() if wanted(d)]

            for target in candidates:
                client = BleakClient(target, timeout=timeout)
                try:
                    await client.connect()
                    return cls(client)
                except Exception as e:  # out of range, or a stale cache entry
                    error = e

        if error is None:
            raise RuntimeError("No BLE Sensor board found. Is the firmware flashed and the board powered?")
        raise RuntimeError(f"Could not connect to a BLE Sensor board: {error}")

    async def disconnect(self) -> None:
        await self.client.disconnect()

    async def __aenter__(self) -> "BleSensor":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.disconnect()

    # --- reading ----------------------------------------------------------

    async def _read(self, uuid: str) -> bytes:
        return bytes(await self.client.read_gatt_char(uuid))

    async def read_info(self) -> Info:
        return Info.decode(await self._read(INFO_UUID))

    async def read_env(self) -> Env:
        return Env.decode(await self._read(ENV_UUID))

    async def read_motion(self) -> Motion:
        return Motion.decode(await self._read(MOTION_UUID))

    async def read_button(self) -> Button:
        return Button.decode(await self._read(BUTTON_UUID))

    async def read_led(self) -> Led:
        return Led.decode(await self._read(LED_UUID))

    async def read_config(self) -> Config:
        return Config.decode(await self._read(CONFIG_UUID))

    async def read_name(self) -> str:
        return (await self._read(NAME_UUID)).decode(errors="replace")

    async def read_air(self) -> Air:
        """Air quality; ESP32 Air board only."""
        return Air.decode(await self._read(AIR_UUID))

    async def read_display(self) -> Display:
        """Whether an OLED is connected and which readings it shows."""
        return Display.decode(await self._read(DISPLAY_UUID))

    # --- control and configuration --------------------------------------

    async def _write(self, uuid: str, data: bytes) -> None:
        # With response, so the board's validation errors come back as exceptions.
        await self.client.write_gatt_char(uuid, data, response=True)

    async def set_led(self, mode: str, on_ms: int = 500, off_ms: int = 500) -> None:
        """mode: "off", "on" or "blink" (on/off times only matter for blink)."""
        await self._write(LED_UUID, Led(mode, on_ms, off_ms).encode())

    async def set_config(self, sensors: Optional[list[str]] = None, **changes) -> Config:
        """Changes only the given Config fields; the rest stay as stored.

        sensors: sensor names (see protocol.CONFIG_SENSORS), instead of sensor_mask.
        """
        config = await self.read_config()
        if sensors is not None:
            changes["sensor_mask"] = sensor_mask(sensors)
        config = replace(config, **changes)
        await self._write(CONFIG_UUID, config.encode())
        return config

    async def set_name(self, name: str) -> None:
        """Stored on the board; advertised after the next disconnect."""
        raw = name.encode()
        if not 0 < len(raw) <= NAME_MAX_LEN:
            raise ValueError(f"name must be 1-{NAME_MAX_LEN} bytes")
        await self._write(NAME_UUID, raw)

    async def set_display(self, pages: Optional[list[str]] = None, page_time_s: Optional[float] = None) -> Display:
        """Changes what the OLED shows: the readings (names from
        protocol.DISPLAY_PAGES) and/or seconds per reading. Stored on the board."""
        display = await self.read_display()
        if pages is not None:
            display.pages = pages
        if page_time_s is not None:
            display.page_time_s = page_time_s
        await self._write(DISPLAY_UUID, display.encode())
        return display

    async def command(self, name: str) -> None:
        """One of protocol.COMMANDS: calibrate, factory-reset, reboot,
        identify, reset-orientation."""
        await self._write(COMMAND_UUID, bytes([COMMANDS[name]]))

    # --- notifications -----------------------------------------------------

    async def _subscribe(self, uuid: str, decode, callback: Callback) -> None:
        async def on_notification(_sender, data: bytearray) -> None:
            result = callback(decode(bytes(data)))
            if asyncio.iscoroutine(result):
                await result

        await self.client.start_notify(uuid, on_notification)

    async def on_env(self, callback: Callback) -> None:
        await self._subscribe(ENV_UUID, Env.decode, callback)

    async def on_motion(self, callback: Callback) -> None:
        await self._subscribe(MOTION_UUID, Motion.decode, callback)

    async def on_button(self, callback: Callback) -> None:
        await self._subscribe(BUTTON_UUID, Button.decode, callback)
