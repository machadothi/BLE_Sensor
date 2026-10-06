"""Python client for the BLE Sensor firmware (Thunderboard EFR32BG22).

    import asyncio
    from ble_sensor import BleSensor

    async def main():
        async with await BleSensor.connect() as board:
            print(await board.read_env())
            await board.set_led("blink", on_ms=100, off_ms=900)

    asyncio.run(main())

Modules: protocol (packet layouts), discovery (finding boards), client
(BleSensor), cli (the `ble-sensor` command).
"""

from .client import BleSensor
from .protocol import Button, Config, Display, Env, Info, Led, Motion

__all__ = ["BleSensor", "Button", "Config", "Display", "Env", "Info", "Led", "Motion"]
