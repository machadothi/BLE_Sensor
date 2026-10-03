# BLE_Sensor: Thunderboard BG22 over Bluetooth

Custom firmware and a Python client for the Silicon Labs **Thunderboard
EFR32BG22** (BRD4184A; also builds for BRD4184B). Over Bluetooth LE you can:

- **read / stream every sensor**: temperature, humidity, light, UV (A), sound
  (B), magnetic field, accelerometer, gyroscope, orientation, button, supply
  voltage, chip temperature (motion up to ~100 Hz)
- **control** the LED and run commands (gyro calibration, zero orientation,
  identify, factory reset, reboot)
- **configure** sample rates, active sensors, TX power, advertising interval,
  hall threshold and device name, all stored on the board

## Quick start

Assumes the toolchain is already in `tools/`; otherwise see [docs/setup.md](docs/setup.md).

```sh
cd firmware && make flash          # build + flash (BOARD=brd4184b for the other revision)
cd .. && python3 -m venv .venv && .venv/bin/pip install -r host/requirements.txt
.venv/bin/python host/tb_game.py read
.venv/bin/python host/tb_game.py monitor
```

## Documentation

| Document | Read it when you want to… |
|---|---|
| [docs/setup.md](docs/setup.md) | set everything up from scratch: what each tool is for, versions, download links, udev, backup, first flash, what to do if a download disappears |
| [docs/capabilities.md](docs/capabilities.md) | know what the board measures, what you can control and configure (ranges, defaults), and the exact BLE byte layouts |
| [docs/host-client.md](docs/host-client.md) | use the `tb_game.py` CLI or call it from your own Python code / game |
| [docs/firmware.md](docs/firmware.md) | understand or change the firmware: build pipeline, **where `main()` is**, source files, design decisions |
| [docs/troubleshooting.md](docs/troubleshooting.md) | fix something that doesn't work |

## Layout

```
BLE_Sensor/
├── firmware/
│   ├── src/                     app.c, sensors.c, settings.c, tb_protocol.h (BLE layouts)
│   ├── config/btconf/           GATT database
│   ├── tb_game_brd4184{a,b}.slcp  project definitions for slc
│   ├── Makefile                 make / make flash / make log
│   └── build/                   generated (git-ignored)
├── host/tb_game.py              Python BLE client (CLI + library)
├── docs/                        documentation
├── setup_udev.sh                J-Link USB permission rule (sudo, once)
├── tools/                       toolchain, ~3.7 GB (git-ignored; see docs/setup.md)
└── backup/                      factory flash image (git-ignored)
```
