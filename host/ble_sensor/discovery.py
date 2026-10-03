"""Finding BLE Sensor boards, robustly on Linux.

BlueZ (the Linux Bluetooth stack) needed three workarounds, all here:

1. bleak asks BlueZ to drop repeated advertisements (DuplicateData=False), so
   a board BlueZ already knows can go unreported for a whole scan. The
   firmware advertises manufacturer data, and with DuplicateData=True BlueZ
   reports every packet that carries it.
2. Some controllers keep their duplicate filter across discovery sessions, so
   one long scan can stay blind to a board that is advertising. Scanning in
   short sessions that restart discovery avoids that.
3. If scanning still misses the board, BlueZ can connect to a device in its
   cache directly; cached_boards() finds those.

On macOS and Windows the bluez options are ignored and only (2) applies,
harmlessly.
"""

from __future__ import annotations

import asyncio
import sys
from typing import Callable, Optional

from bleak import BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from .protocol import ADV_COMPANY_ID, BOARDS, SERVICE_UUID

SCAN_ARGS = {"bluez": {"filters": {"DuplicateData": True}}}
SCAN_SESSION_S = 3.0

Match = Callable[[BLEDevice, Optional[AdvertisementData]], bool]


def is_board(_device: BLEDevice, adv: Optional[AdvertisementData]) -> bool:
    """True if the advertisement carries the BLE Sensor service UUID."""
    return adv is not None and SERVICE_UUID in adv.service_uuids


def board_revision(adv: AdvertisementData) -> str:
    """Board revision from the advertised manufacturer data, or "?"."""
    data = adv.manufacturer_data.get(ADV_COMPANY_ID, b"")
    return BOARDS.get(data[0], "?") if data else "?"


async def _sessions(timeout: float):
    """Yields the length of each scan session until timeout is used up."""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while (remaining := deadline - loop.time()) > 0:
        yield min(SCAN_SESSION_S, remaining)


async def find_device(match: Match, timeout: float) -> Optional[BLEDevice]:
    """First advertising device for which match(device, adv) is true."""
    async for session_s in _sessions(timeout):
        device = await BleakScanner.find_device_by_filter(match, timeout=session_s, **SCAN_ARGS)
        if device is not None:
            return device
    return None


async def scan_boards(timeout: float) -> list[tuple[BLEDevice, int, str]]:
    """(device, rssi, board revision) of every board in range, strongest first."""
    found: dict[str, tuple[BLEDevice, AdvertisementData]] = {}
    async for session_s in _sessions(timeout):
        found.update(await BleakScanner.discover(timeout=session_s, return_adv=True, **SCAN_ARGS))

    boards = [(device, adv.rssi, board_revision(adv))
              for device, adv in found.values() if is_board(device, adv)]
    return sorted(boards, key=lambda board: -board[1])


async def cached_boards() -> list[BLEDevice]:
    """Boards in BlueZ's device cache (Linux only; [] elsewhere).

    BlueZ forgets unpaired devices about 30 s after their last use, so this
    mainly helps right after a previous connection.
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
            # bleak's BlueZ backend connects straight to this D-Bus path.
            boards.append(BLEDevice(props["Address"], props.get("Name"), {"path": path, "props": props}))
    return boards
