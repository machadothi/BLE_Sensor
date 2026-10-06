# Python client (`host/ble_sensor`)

A small Python package that works both as a **command** (`ble-sensor`) and as
a **library** (`BleSensor`). It needs Python ≥ 3.10; installing it pulls in
`bleak` and `pyserial` (see [setup.md](setup.md#4-python-environment)):

```sh
cd ~/git/BLE_Sensor
.venv/bin/pip install -e host
export PATH="$PWD/.venv/bin:$PATH"     # makes `ble-sensor` available
```

`-e` (editable) means changes to the files under `host/ble_sensor/` take effect
without reinstalling. Without installing, `python -m ble_sensor ...` works from
inside `host/`.

- [Package layout](#package-layout)
- [Command line](#command-line)
- [Library](#library)
- [BlueZ quirks](#bluez-quirks)

---

## Package layout

| File | What it holds |
|---|---|
| `protocol.py` | UUIDs, packet formats, the `Env`/`Motion`/`Button`/`Led`/`Config`/`Info` classes, sensor names, limits. Mirrors `firmware/src/ble/ble_protocol.h`. |
| `discovery.py` | finding boards: scan sessions, BlueZ cache fallback, and why they're needed |
| `client.py` | `BleSensor`: connect, read, write, subscribe |
| `cli.py` | the `ble-sensor` command (one small function per command) |
| `__main__.py` | lets `python -m ble_sensor` run the command |

## Command line

Every command except `scan` connects to a board, does its job and disconnects.
With several boards in range, pick one with `-a`/`--address` or `-n`/`--name`
(both go **before** the command):

```sh
ble-sensor -n Player-One read
ble-sensor -a 58:8E:81:66:B0:DF led on
```

| Command | Example | What it does |
|---|---|---|
| `scan` | `ble-sensor scan -t 10` | List boards in range: address, RSSI, revision, name (default 6 s) |
| `info` | `ble-sensor info` | Revision, protocol version, available sensors, config, LED state |
| `read` | `ble-sensor read` | Read every sensor once |
| `monitor` | `ble-sensor monitor` | Stream env + motion + button notifications until Ctrl+C |
| | `ble-sensor monitor motion -d 10` | Only motion, stop after 10 s (streams: `env`, `motion`, `button`) |
| `led` | `ble-sensor led blink --on-ms 100 --off-ms 900` | `off`, `on`, `blink` |
| `config` | `ble-sensor config` | Show the stored configuration |
| | `ble-sensor config --motion-period 10 --env-period 500` | Change only the options given |
| | `ble-sensor config --sensors imu,hall` / `--sensors none` | Choose which sensors run (`rht`, `light`, `hall`, `imu`, `sound`, `supply`) |
| | `ble-sensor config --tx-power 6 --adv-interval 50` | Radio settings (applied after disconnect) |
| | `ble-sensor config --hall-threshold 5` | Hall alert threshold in mT |
| `name` | `ble-sensor name Player-One` | Rename (stored; advertised after disconnect) |
| `display` | `ble-sensor display` | OLED connected? Which readings it shows |
| | `ble-sensor display --pages temperature,humidity,orientation` | Choose the readings (`all` / `none` also work); see [display.md](display.md) |
| | `ble-sensor display --page-time 5` | Seconds each reading stays on screen (1–60) |
| `calibrate` | `ble-sensor calibrate` | Gyro bias calibration; keep the board still |
| `reset-orientation` | `ble-sensor reset-orientation` | Zero roll/pitch/yaw |
| `identify` | `ble-sensor identify` | Fast LED blink for 3 s |
| `factory-reset` | `ble-sensor factory-reset` | Default config and name |
| `reboot` | `ble-sensor reboot` | Restart the board |

**ESP32 Air boards** (see [capabilities.md](capabilities.md#other-boards-esp32-air))
work with `scan`, `info`, `read` (adds AQI, eCO2, TVOC, ENS160 details and the board's status), `monitor env`,
`name`, `display` (`--pages all` means that board's eight pages; `--rotate on|off` turns its display 180°), `identify`,
`reboot` and `factory-reset`. They have no LED, config, motion or button.

`ble-sensor config --help` shows each option's allowed range. An out-of-range
value fails with `BleakGATTProtocolError ... Value Not Allowed`, and the board
keeps its previous setting.

## Library

```python
import asyncio
from ble_sensor import BleSensor

async def main():
    async with await BleSensor.connect() as board:     # or connect(name="Player-One")
        print(await board.read_info())                 # Info(protocol_version=1, board='BRD4184A', ...)
        await board.set_config(motion_period_ms=10)    # 100 Hz motion
        await board.set_led("blink", on_ms=50, off_ms=50)

        def on_motion(m):                              # called for every notification
            roll, pitch, yaw = m.orientation_deg
            print(f"tilt {roll:+.0f} {pitch:+.0f}")

        await board.on_motion(on_motion)
        await board.on_button(lambda b: b.pressed and print("fire!"))
        await asyncio.sleep(30)                        # your game loop here
        await board.set_led("off")

asyncio.run(main())
```

API summary (all methods are `async`):

| Method | Returns / does |
|---|---|
| `BleSensor.scan(timeout=6.0)` | `[(BLEDevice, rssi, board)]`, strongest first |
| `BleSensor.connect(address=None, name=None, timeout=10, scan_timeout=10)` | connected `BleSensor`: by address, by name, or the first board found |
| `read_info()` | `Info(protocol_version, board, available)` |
| `read_env()` | `Env(uptime_ms, temperature_c, humidity_pct, lux, uv_index, hall_mt, hall_alert, hall_tamper, sound_db, supply_v, die_temperature_c)`; a missing value is `None` |
| `read_motion()` | `Motion(uptime_ms, accel_g, gyro_dps, orientation_deg)` (tuples of 3) |
| `read_button()` | `Button(pressed, press_count)` |
| `read_led()` / `set_led(mode, on_ms, off_ms)` | LED state / change it |
| `read_config()` / `set_config(sensors=None, **fields)` | `Config(sensor_mask, env_period_ms, motion_period_ms, tx_power_dbm, adv_interval_ms, hall_threshold_mt)`; `set_config` changes only the fields passed |
| `read_name()` / `set_name(name)` | device name |
| `read_air()` | `Air(uptime_ms, state, aqi, eco2_ppm, tvoc_ppb, status, firmware, r1_ohms, r4_ohms, compensation_c, compensation_pct)`: ESP32 Air board only |
| `read_system()` | `System(uptime_s, free_ram, chip_temperature_c, wifi_rssi, wifi, mqtt, bluetooth, ip, cpu_mhz, reset_cause, micropython, sensor_errors, integrity_errors, humid_s)`: ESP32 Air board only |
| `read_display()` / `set_display(pages=None, page_time_s=None)` | `Display(present, pages, page_time_s)`: the OLED, which readings it shows (names from `protocol.DISPLAY_PAGES`) and seconds per reading; `set_display` changes only what you pass |
| `command(name)` | `"calibrate"`, `"factory-reset"`, `"reboot"`, `"identify"`, `"reset-orientation"` |
| `on_env(cb)` / `on_motion(cb)` / `on_button(cb)` | subscribe; `cb` gets the decoded object, and may be a plain or `async` function |
| `disconnect()` | also called automatically by `async with` |

`board.client` is the underlying `bleak.BleakClient` if you need raw access.
Constants such as UUIDs, sensor names and limits are in `ble_sensor.protocol`.

## BlueZ quirks

Linux's Bluetooth stack (BlueZ) needed workarounds. They are handled in
`discovery.py` and `client.py`, but it helps to know about them:

1. **Generic Access service is hidden.** BlueZ handles `1800` itself and never
   shows it to clients, so the standard Device Name can't be read or written
   from Linux. The firmware therefore also exposes the name as the custom
   characteristic `a7e40008`.
2. **Repeated advertisements are suppressed.** bleak asks BlueZ to drop
   duplicates, so a board BlueZ already knows can go unreported for a whole
   scan. The firmware advertises manufacturer data, and the client sets
   `DuplicateData=True` (`SCAN_ARGS`) so BlueZ reports every packet.
3. **The controller's duplicate filter can blind a scan.** On this machine's
   adapter a scan either found the board within about a second or not at all.
   The client scans in **3-second sessions** (`SCAN_SESSION_S`) and restarts
   until found.
4. **Cache fallback and retry.** If scanning finds nothing, `connect()` tries
   the boards in BlueZ's device cache (D-Bus `GetManagedObjects`) directly. If
   that fails as well, the whole scan-then-cache sequence runs once more
   (`CONNECT_ATTEMPTS`). Back-to-back connections now succeed 30 of 30 times.
5. **Reading a subscribed characteristic looks like a notification.** BlueZ
   reports a read as a value change, so `read_button()` while subscribed with
   `on_button()` also fires the callback once. Ignore duplicates, or don't
   read what you're subscribed to.
6. **Service cache after firmware changes.** If you change the GATT database
   and the client still sees old characteristics, remove the cached device:
   `bluetoothctl remove 58:8E:81:66:B0:DF`.

On macOS and Windows, bleak uses the native stacks. Quirks 1–4 are
Linux-specific, and `bluez` options are ignored there.
