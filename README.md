# Thunderboard BG22 over Bluetooth

Firmware and a Python client for the Silicon Labs **Thunderboard EFR32BG22**
(BRD4184A, also builds for BRD4184B). Over BLE you can:

- **read / stream every sensor**: temperature, humidity, ambient light, UV index
  (A), sound level (B), magnetic field (hall), accelerometer, gyroscope, fused
  orientation, push button, supply voltage, chip temperature
- **control** the LED (off / on / blink)
- **configure** sample rates, which sensors run, TX power, advertising interval,
  hall alert threshold and the device name (all stored in flash, survive reboot)
- run **commands**: gyro calibration, zero orientation, identify, factory reset, reboot

```
silicon_labs_game/
├── firmware/            EFR32BG22 application (Simplicity SDK 2025.6.3)
│   ├── src/             app.c, sensors.c, settings.c, tb_protocol.h
│   ├── config/btconf/   GATT database
│   ├── tb_game_brd4184{a,b}.slcp
│   └── Makefile         build / flash / log
├── host/tb_game.py      Python BLE client: CLI + library (bleak)
├── tools/               local toolchain (not system-wide, safe to delete/re-download)
├── backup/              flash image read from the board before the first flash
└── setup_udev.sh        one-time J-Link USB permission rule (needs sudo)
```

## Setup (already done on this machine)

Everything lives inside this folder; nothing was installed system-wide except
the J-Link udev rule.

| Tool | Where | Source |
|---|---|---|
| Simplicity SDK v2025.6.3 | `tools/simplicity_sdk` | github.com/SiliconLabs/simplicity_sdk releases |
| slc-cli (project generator) | `tools/slc_cli` | silabs.com `slc_cli_linux.zip` |
| Simplicity Commander (flasher) | `tools/commander-cli` | silabs.com `SimplicityCommander-Linux.zip` |
| Arm GNU Toolchain 12.2.Rel1 | `tools/gcc` | developer.arm.com (system GCC 10.3 is too old) |
| Temurin JDK 21 (for slc) | `tools/jdk` | adoptium.net |
| bleak, pyserial | `.venv` | pip |

silabs.com rejects `curl` (Akamai 403); `wget` works. On a new machine also run
`sudo sh setup_udev.sh`, then **unplug and replug** the board (the rule only
applies on a USB add event).

slc must know the SDK and compiler once per user:

```sh
cd tools && PATH=$PWD/jdk/bin:$PATH ./slc_cli/slc configuration --sdk $PWD/simplicity_sdk --gcc-toolchain $PWD/gcc
PATH=$PWD/jdk/bin:$PATH ./slc_cli/slc signature trust --sdk $PWD/simplicity_sdk
```

## Firmware

```sh
cd firmware
make flash                    # build + flash BRD4184A (the board connected here)
make BOARD=brd4184b flash     # the other Thunderboard BG22 revision
make log                      # UART log on the VCOM port, 115200 8N1 (Ctrl+] to quit)
```

The app is linked at address 0 and needs no bootloader. The first flash
replaced the factory bootloader + demo; `backup/brd4184a_factory_flash.hex`
restores them:

```sh
tools/commander-cli/commander-cli flash backup/brd4184a_factory_flash.hex --device EFR32BG22C224F512IM40
```

## Python client

```sh
alias tb='~/git/silicon_labs_game/.venv/bin/python ~/git/silicon_labs_game/host/tb_game.py'

tb scan                        # boards in range
tb info                        # board revision, sensors, config, LED state
tb read                        # every sensor once
tb monitor                     # live stream (env, motion, button); Ctrl+C to stop
tb monitor motion -d 10        # only IMU, for 10 s

tb led on | off
tb led blink --on-ms 100 --off-ms 900

tb config                                          # show
tb config --motion-period 10 --env-period 500      # 100 Hz IMU, 2 Hz environment
tb config --sensors imu,hall                       # sample only these
tb config --tx-power 6 --adv-interval 50           # applied after disconnect
tb config --hall-threshold 5                       # mT

tb name Player-One             # persistent, advertised after disconnect
tb calibrate                   # gyro bias; keep the board still
tb reset-orientation           # zero roll/pitch/yaw
tb identify                    # fast LED blink for 3 s
tb factory-reset | reboot
```

With several boards in range, pick one with `-a <address>` or `-n <name>`.

As a library (e.g. for a game loop):

```python
import asyncio, sys
sys.path.insert(0, "host")
from tb_game import TBGame

async def main():
    async with await TBGame.connect() as tb:
        await tb.set_config(motion_period_ms=10)        # 100 Hz
        await tb.on_motion(lambda m: print(m.orientation_deg))
        await tb.on_button(lambda b: print("fire!" if b.pressed else ""))
        await tb.set_led("blink", on_ms=50, off_ms=50)
        print(await tb.read_env())
        await asyncio.sleep(30)

asyncio.run(main())
```

## BLE protocol

Service `a7e40000-5c2b-4f1a-9d3e-6b8c0f2e1d47`. Characteristic UUIDs differ only
in the 4th byte (`a7e400XX-…`). Packed little-endian; the authoritative layout
is `firmware/src/tb_protocol.h`.

| XX | Name | Access | Content |
|---|---|---|---|
| 01 | Environment | read, notify | uptime, valid bits, temp (0.01 °C), RH (0.01 %), lux (0.01), UV (0.01), hall (µT), hall flags, sound (0.01 dB), supply (mV), die temp (0.01 °C) — 27 B |
| 02 | Motion | read, notify | uptime, accel (mg ×3), gyro (0.01 °/s ×3), roll/pitch/yaw (0.01° ×3) — 22 B |
| 03 | Button | read, notify | pressed, press count — 5 B |
| 04 | LED | read, write | mode (0 off, 1 on, 2 blink), on ms, off ms — 5 B |
| 05 | Config | read, write | version, sensor mask, env period, motion period, TX power (0.1 dBm), adv interval, hall threshold (µT) — 12 B |
| 06 | Info | read | protocol version, board (0x0A/0x0B), available-sensor mask — 4 B |
| 07 | Command | write | 1 calibrate, 2 factory reset, 3 reboot, 4 identify, 5 reset orientation |
| 08 | Name | read, write | UTF-8, 1–20 bytes |

Out-of-range writes are rejected with ATT error 0x13 (Value Not Allowed).
The board advertises the service UUID plus manufacturer data
(`0x02FF`, board id, protocol version) and its name in the scan response.

Limits: env period 100–60000 ms, motion period 10–1000 ms, TX power −30 to
+6 dBm, advertising interval 20–10240 ms, hall threshold 0.1–20 mT.

## Notes

- Sensors only run while a client is connected; the board just advertises otherwise.
- On BRD4184A the RHT, light and hall sensors share one power pin, so removing
  them from the sensor mask stops sampling but not their supply. The IMU and
  the microphone are really powered down.
- Slow conversions (Si1133 light ≈ 210 ms, Si7021 ≈ 20 ms) are started on one
  env tick and collected on the next, so they never stall IMU streaming.
  Light/RHT values are therefore up to one env period old.
- Linux/BlueZ quirks handled by the client: the Generic Access service is hidden
  (hence the custom Name characteristic), and this adapter's duplicate filter
  can blind a long scan, so discovery runs in short 3 s sessions and falls back
  to BlueZ's device cache.
- Measured on BRD4184A: motion 104.5 / 52.5 / 20.7 Hz at 10 / 20 / 50 ms periods.
- BRD4184B (sound sensor, VEML6035) builds but has not been tested on hardware.
