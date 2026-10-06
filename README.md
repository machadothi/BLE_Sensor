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
- do all of it from a **phone app** (`android/`) or the `ble-sensor` command
- see the readings in **Home Assistant** (read-only, discovered automatically via BTHome)
- show them on an optional **OLED display** on the board, one reading at a time, chosen in the app

## Quick start

Assumes the toolchain is already in `tools/`; otherwise see [docs/setup.md](docs/setup.md).

```sh
cd firmware && make flash          # build + flash (BOARD=brd4184b for the other revision)
cd .. && python3 -m venv .venv && .venv/bin/pip install -e host
.venv/bin/ble-sensor read
.venv/bin/ble-sensor monitor
```

## Documentation

| Document | Read it when you want to… |
|---|---|
| [docs/setup.md](docs/setup.md) | set everything up from scratch: **every download with its link** (firmware toolchain, Python, Android SDK), udev, backup, first flash, what to do if a download disappears |
| [docs/capabilities.md](docs/capabilities.md) | know what the board measures, what you can control and configure (ranges, defaults), and the exact BLE byte layouts |
| [docs/host-client.md](docs/host-client.md) | use the `ble-sensor` command, or the `BleSensor` class from your own Python code / game |
| [docs/display.md](docs/display.md) | wire up the SSD1306 OLED, choose what it shows, change its look |
| [docs/home-assistant.md](docs/home-assistant.md) | get the readings into Home Assistant: what's sent, setup, button automations, how it works |
| [docs/android-app.md](docs/android-app.md) | build and install the phone app, or change it: screens, code map |
| [docs/firmware.md](docs/firmware.md) | understand or change the firmware: build pipeline, **where `main()` is**, module map, **`app_config.h`** (all tunables), design decisions |
| [docs/troubleshooting.md](docs/troubleshooting.md) | fix something that doesn't work |

## Layout

```
BLE_Sensor/
├── firmware/
│   ├── src/
│   │   ├── app.c                startup + Bluetooth event dispatch
│   │   ├── app_config.h         ALL tunable values (defaults, timings, sensor settings)
│   │   ├── sampling.c           periodic sensor timers
│   │   ├── ble/                 wire protocol, advertising, GATT service, device name
│   │   ├── control/             LED, button, commands
│   │   ├── display/             optional SSD1306 OLED: one reading at a time (switch: app_config.h)
│   │   ├── home_assistant/      BTHome broadcast that Home Assistant reads (switch: app_config.h)
│   │   ├── sensors/             environmental sensors, IMU, microphone
│   │   └── storage/             settings in flash (NVM3)
│   ├── config/btconf/           GATT database
│   ├── ble_sensor_brd4184{a,b}.slcp  project definitions for slc
│   ├── tools/                   make_display_assets.py: generates the OLED fonts/icons
│   ├── Makefile                 make / make flash / make log
│   └── build/                   generated (git-ignored)
├── android/                     phone app (Compose): scan, live values, 3D motion, charts, control, settings
├── host/
│   ├── pyproject.toml           `pip install -e host` → `ble-sensor` command
│   └── ble_sensor/              Python package: protocol, discovery, client, cli
├── docs/                        documentation
├── setup_udev.sh                J-Link USB permission rule (sudo, once)
├── tools/                       toolchain, ~3.7 GB (git-ignored; see docs/setup.md)
└── backup/                      factory flash image (git-ignored)
```
