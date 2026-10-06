# Capabilities and configuration reference

Everything the board exposes over Bluetooth, and every setting. The
authoritative definitions are in [`firmware/src/ble/ble_protocol.h`](../firmware/src/ble/ble_protocol.h).

- [Hardware](#hardware)
- [What you can read](#what-you-can-read)
- [What you can control](#what-you-can-control)
- [What you can configure](#what-you-can-configure)
- [BLE protocol (byte level)](#ble-protocol-byte-level)

---

## Hardware

Thunderboard EFR32BG22 (kit SLTB010A). MCU: **EFR32BG22C224F512IM40**
(Arm Cortex-M33, 512 KB flash, 32 KB RAM, Bluetooth 5.2 LE radio up to
+6 dBm).

| Part | BRD4184A (this board) | BRD4184B | Measures | Bus |
|---|---|---|---|---|
| Si7021 | ✓ | ✓ | temperature, relative humidity | I²C |
| Si1133 | ✓ | – | ambient light (lux) + UV index | I²C |
| VEML6035 | – | ✓ | ambient light (lux) | I²C |
| Si7210 | ✓ | ✓ | magnetic field (hall effect); used in its ±20 mT range | I²C |
| ICM-20648 | ✓ | ✓ | 3-axis accelerometer (±2 g) + 3-axis gyroscope (±250 °/s) | SPI |
| PDM microphone | – | ✓ | sound level | PDM |
| LED0 | ✓ | ✓ | output | GPIO |
| BTN0 push button | ✓ | ✓ | input | GPIO |
| EFR32 internal | ✓ | ✓ | supply voltage (ADC), die temperature | – |
| J-Link OB debugger | ✓ | ✓ | flashing + virtual COM port | USB |

The board runs from USB or a CR2032 coin cell.

## What you can read

Environmental readings (temperature, humidity, light, UV, magnetic field,
supply, chip temperature) are sampled **all the time**, every env period. They
go to a connected client and to the [Home Assistant broadcast](home-assistant.md).
The IMU runs all the time too while the Home Assistant orientation broadcast is
on (the default); otherwise only during a connection. Sound runs **only while a
client is connected**.

| Reading | Unit / resolution | Characteristic | Notes |
|---|---|---|---|
| Temperature | 0.01 °C | Environment | Si7021 |
| Relative humidity | 0.01 % | Environment | Si7021 |
| Ambient light | 0.01 lux | Environment | Si1133 (A) / VEML6035 (B) |
| UV index | 0.01 | Environment | BRD4184A only |
| Magnetic field | 1 µT, signed | Environment | + alert flag (above threshold) and tamper flag (above sensor's tamper level) |
| Sound level | 0.01 dB | Environment | BRD4184B only |
| Supply voltage | 1 mV | Environment | ≈3.0 V on USB |
| Chip die temperature | 0.01 °C | Environment | runs warmer than ambient |
| Acceleration X/Y/Z | 1 mg | Motion | ±2 g range |
| Rotation rate X/Y/Z | 0.01 °/s | Motion | ±250 °/s range |
| Orientation roll/pitch/yaw | 0.01° | Motion | fused from accel + gyro; the gyro offset is removed, so yaw holds still on a still board. There is no magnetometer: yaw is relative to where it started, and `reset-orientation` zeroes it |
| Button | pressed + press count | Button | debounced, notified on every press and release |
| Board info | revision, sensors present | Info | |

Each Environment/Motion packet carries the board **uptime in ms** as a
timestamp. Environment packets also carry a **valid** bitmask: a field whose
sensor is absent, disabled or not ready yet is marked invalid (the client
shows `n/a`).

Measured on BRD4184A: motion arrives at **104.5 / 52.5 / 20.7 Hz** with a
10 / 20 / 50 ms period. The IMU's own output rate is
1125 / (1 + divider) Hz, so the real rate is a little above nominal.

## What you can control

| Action | How (CLI) | Effect |
|---|---|---|
| LED off / on | `ble-sensor led off`, `ble-sensor led on` | immediate |
| LED blink | `ble-sensor led blink --on-ms 100 --off-ms 900` | each time ≥ 10 ms |
| Identify | `ble-sensor identify` | fast blink (100/100 ms) for 3 s, then back to the LED mode |
| Gyro calibration | `ble-sensor calibrate` | averages the gyro for 1 s and stores that offset in flash; **keep the board still**. Not required: the offset is also learned automatically whenever the board lies still |
| Zero orientation | `ble-sensor reset-orientation` | roll/pitch/yaw restart from 0 |
| Factory reset | `ble-sensor factory-reset` | default config + default name (stored) |
| Reboot | `ble-sensor reboot` | restarts after 200 ms |

The LED state is not stored; it is off after every reboot.

## What you can configure

Stored in flash (NVM3) and kept across reboots and reflashing (`make erase`
wipes it). Out-of-range values are rejected with ATT error `0x13`, and nothing
changes.

| Setting | CLI option | Range | Default | Takes effect |
|---|---|---|---|---|
| Sensor mask | `--sensors rht,light,hall,imu,sound,supply` or `none` | any combination | all | immediately |
| Environment period | `--env-period` (ms) | 100 – 60000 | 1000 | immediately |
| Motion period | `--motion-period` (ms) | 10 – 1000 | 50 | immediately (IMU restarts) |
| TX power | `--tx-power` (dBm, 0.1 steps) | −30 – +6 | 0 | **after disconnect** (next advertising) |
| Advertising interval | `--adv-interval` (ms) | 20 – 10240 | 100 | **after disconnect** |
| Hall alert threshold | `--hall-threshold` (mT) | 0.1 – 20 | 3.0 | immediately (0.5 mT hysteresis) |
| Device name | `ble-sensor name <name>` | 1 – 20 bytes UTF-8 | `BLE-Sensor-XXXX` (last 2 address bytes) | **advertised after disconnect** |
| Display pages | `ble-sensor display --pages …`, app Settings → Display | any of the 10 pages | all | at the display's next page change ([display.md](display.md)) |
| Display time per reading | `ble-sensor display --page-time` (s), app Settings → Display | 1 – 60 s | 2 s | immediately |

Notes:

- Radio power is rounded to what the hardware supports; the UART log prints
  the value actually set.
- Clearing `imu` or `sound` from the mask powers that part down. On BRD4184A,
  clearing `rht`, `light` or `hall` only stops sampling, because those three
  share one power pin.
- Faster motion needs a short connection interval. The board asks for
  7.5–15 ms, and the computer decides what it grants.

## BLE protocol (byte level)

**Service** `a7e40000-5c2b-4f1a-9d3e-6b8c0f2e1d47`. Characteristics are
`a7e400XX-5c2b-4f1a-9d3e-6b8c0f2e1d47`. All values are **packed,
little-endian**.

| XX | Name | Properties | Size |
|---|---|---|---|
| 01 | Environment | read, notify | 27 |
| 02 | Motion | read, notify | 22 |
| 03 | Button | read, notify | 5 |
| 04 | LED | read, write | 5 |
| 05 | Config | read, write | 12 |
| 06 | Info | read | 4 |
| 07 | Command | write | 1 |
| 08 | Name | read, write | 1–20 |
| 09 | Display | read, write | 5 |

Notifications need an ATT MTU ≥ 30 (Linux negotiates 247 automatically).

### Environment (27 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | uptime_ms |
| 4 | u16 | valid bits (below) |
| 6 | i16 | temperature, 0.01 °C |
| 8 | u16 | humidity, 0.01 % |
| 10 | u32 | lux × 100 |
| 14 | u16 | UV index × 100 |
| 16 | i32 | magnetic field, µT |
| 20 | u8 | hall flags: bit0 alert, bit1 tamper |
| 21 | i16 | sound, 0.01 dB |
| 23 | u16 | supply, mV |
| 25 | i16 | die temperature, 0.01 °C |

Valid bits: 0 temperature, 1 humidity, 2 lux, 3 UV, 4 hall, 5 sound,
6 supply, 7 die temperature.

### Motion (22 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | uptime_ms |
| 4 | i16 ×3 | acceleration X, Y, Z (mg) |
| 10 | i16 ×3 | gyro X, Y, Z (0.01 °/s) |
| 16 | i16 ×3 | roll, pitch, yaw (0.01°) |

### Button (5 bytes)

`u8 pressed` (1 = held), `u32 press_count` (since boot).

### LED (5 bytes)

`u8 mode` (0 off, 1 on, 2 blink), `u16 on_ms`, `u16 off_ms`.

### Config (12 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | protocol version (read-only, ignored on write) |
| 1 | u8 | sensor mask (bits below) |
| 2 | u16 | env period, ms |
| 4 | u16 | motion period, ms |
| 6 | i16 | TX power, 0.1 dBm |
| 8 | u16 | advertising interval, ms |
| 10 | u16 | hall threshold, µT |

<a id="sensor-bits"></a>Sensor bits (used by both Config and Info): 0 RHT,
1 light, 2 hall, 3 IMU, 4 sound, 5 supply voltage (6 air quality: ESP32 Air
board only, in Info). The UART line
`Sensors available: 0x2F` means bits 0, 1, 2, 3, 5.

### Info (4 bytes)

`u8 protocol_version` (1), `u8 board` (`0x0A` = BRD4184A, `0x0B` = BRD4184B,
`0x0C` = ESP32 Air, see [below](#other-boards-esp32-air)), `u8 available sensor
bits` (bit 6 = air quality, ESP32 Air only), `u8 reserved`.

### Display (5 bytes)

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | present: 1 if an OLED was found at boot (ignored on write) |
| 1 | u16 | page_mask: which readings the OLED cycles through (bits below) |
| 3 | u16 | page_ms: how long each reading stays on screen, 1000–60000 |

Page bits:

| Bit | Page | Bit | Page |
|---|---|---|---|
| 0 | temperature | 5 | sound |
| 1 | humidity | 6 | supply |
| 2 | light | 7 | chip temperature |
| 3 | UV index | 8 | orientation (X/Y/Z) |
| 4 | magnetic field | 9 | button presses |

A mask with bits above 9, or a page_ms outside 1000–60000, is rejected (ATT
error 0x13). The characteristic is
new; clients should treat it as optional (older firmware doesn't have it).
See [display.md](display.md).

### Command (1 byte)

| Code | Command |
|---|---|
| 0x01 | calibrate gyro |
| 0x02 | factory reset |
| 0x03 | reboot |
| 0x04 | identify |
| 0x05 | zero orientation |

### ATT errors returned

| Code | Meaning |
|---|---|
| 0x07 | invalid offset |
| 0x0D | wrong length |
| 0x13 | value not allowed (out of range / unknown command) |

### Advertising

- **Advertising packet:** flags, the complete 128-bit service UUID, and
  manufacturer data `FF 02 <board id> <protocol version>` (company `0x02FF`,
  Silicon Labs).
- **Scan response:** complete local name.
- Connectable and undirected, at the configured interval and TX power.

A second, independent broadcast carries the readings for **Home Assistant** in
BTHome v2 format: temperature, humidity, light, UV, button presses and the
X/Y/Z angles, in two alternating packets. It's non-connectable and comes from
its own address
`D8:8E:81:66:B0:DF` (the board's address with the top two bits set). The
packet layout is in [home-assistant.md](home-assistant.md#how-it-works).

Other services: Generic Access (`1800`) with a writable Device Name, and Device
Information (`180A`): manufacturer "Silicon Labs", model "Thunderboard BG22",
firmware "1.0.0".

## Other boards: ESP32 Air

The ESP32 + ENS160/AHT21 air monitor (separate project, `~/git/air_quality_sensor`)
speaks this same protocol, so the phone app and `ble-sensor` work with it too.
Board id `0x0C`, sensors `0x41` (temperature/humidity + air quality).

| Characteristic | ESP32 Air |
|---|---|
| Env (`01`) | yes, every 2 s; only temperature and humidity are valid |
| Motion, Button, LED, Config (`02`–`05`) | **absent**; the app hides those parts |
| Info, Command, Name, Display (`06`–`09`) | yes; commands: factory reset, reboot, identify (flashes its display). Display is **6 bytes** there: a flags byte follows, bit 0 = rotated 180° (the app shows an "Upside down" switch when it's present) |
| **Air** (`0A`, read, notify, 22 bytes) | new, see below |
| **System** (`0B`, read, notify, 32 bytes) | the ESP32's own status: uptime, RAM, chip temperature, Wi-Fi, MQTT, reset cause, MicroPython version, error counters; layout in `ble_sensor_system_t` |

Air:

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | uptime, ms |
| 4 | u8 | ENS160 state: 0 normal, 1 warm-up, 2 start-up, 3 invalid, `0xFF` no sensor |
| 5 | u8 | AQI 1–5 (UBA), 0 unless normal |
| 6 | u16 | eCO2, ppm, 0 unless normal |
| 8 | u16 | TVOC, ppb, 0 unless normal |
| 10 | u8 | raw DEVICE_STATUS |
| 11 | 3 × u8 | ENS160 firmware |
| 14 | u16 | raw resistance R1, ohms = 2^(raw/2048) |
| 16 | u16 | raw resistance R4 |
| 18 | i16 | compensation temperature in use, °C × 100 (`0x7FFF` = none) |
| 20 | u16 | compensation humidity in use, % × 100 |

Display pages on that board: temperature (bit 0), humidity (bit 1), and its own
air quality (bit 10), eCO2 (bit 11), TVOC (bit 12), dew point (bit 13), air
sensor details (bit 14) and system (bit 15). It doesn't answer writes
with ATT errors (MicroPython can't): it ignores an invalid value and writes the
previous one back.

The definitions are in `firmware/src/ble/ble_protocol.h` (`BOARD_ID_ESP32_AIR`,
`SENSOR_BIT_AIR`, `DISPLAY_PAGE_BIT_AIR_QUALITY`…, `ble_sensor_air_t`); the
ESP32 side is documented in that project's `docs/bluetooth.md`.
