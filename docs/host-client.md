# Python client (`host/tb_game.py`)

A single file that works both as a **command-line tool** and as a **library**.
It needs Python ≥ 3.10 and `bleak` (see [setup.md](setup.md#4-python-environment)).

```sh
alias tb="$HOME/git/BLE_Sensor/.venv/bin/python $HOME/git/BLE_Sensor/host/tb_game.py"
```

- [Command line](#command-line)
- [Library](#library)
- [BlueZ quirks](#bluez-quirks)

---

## Command line

Every command except `scan` connects to a board, does its job and disconnects.
With several boards in range, pick one with `-a`/`--address` or `-n`/`--name`
(both go **before** the command):

```sh
tb -n Player-One read
tb -a 58:8E:81:66:B0:DF led on
```

| Command | Example | What it does |
|---|---|---|
| `scan` | `tb scan -t 10` | List boards in range: address, RSSI, revision, name (default 6 s) |
| `info` | `tb info` | Revision, protocol version, available sensors, config, LED state |
| `read` | `tb read` | Read every sensor once |
| `monitor` | `tb monitor` | Stream env + motion + button notifications until Ctrl+C |
| | `tb monitor motion -d 10` | Only motion, stop after 10 s (streams: `env`, `motion`, `button`) |
| `led` | `tb led blink --on-ms 100 --off-ms 900` | `off`, `on`, `blink` |
| `config` | `tb config` | Show the stored configuration |
| | `tb config --motion-period 10 --env-period 500` | Change only the options given |
| | `tb config --sensors imu,hall` / `--sensors none` | Choose which sensors run |
| | `tb config --tx-power 6 --adv-interval 50` | Radio settings (after disconnect) |
| | `tb config --hall-threshold 5` | Hall alert threshold in mT |
| `name` | `tb name Player-One` | Rename (stored; advertised after disconnect) |
| `calibrate` | `tb calibrate` | Gyro bias calibration; keep the board still |
| `reset-orientation` | `tb reset-orientation` | Zero roll/pitch/yaw |
| `identify` | `tb identify` | Fast LED blink for 3 s |
| `factory-reset` | `tb factory-reset` | Default config and name |
| `reboot` | `tb reboot` | Restart the board |

The `config` options and their ranges are in
[capabilities.md](capabilities.md#what-you-can-configure). An out-of-range value
fails with `BleakGATTProtocolError ... Value Not Allowed`.

## Library

```python
import asyncio, sys
sys.path.insert(0, "/home/machado/git/BLE_Sensor/host")
from tb_game import TBGame

async def main():
    async with await TBGame.connect() as tb:            # or connect(name="Player-One")
        print(await tb.read_info())                     # Info(protocol_version=1, board='BRD4184A', ...)
        await tb.set_config(motion_period_ms=10)        # 100 Hz motion
        await tb.set_led("blink", on_ms=50, off_ms=50)

        def on_motion(m):                               # called for every notification
            roll, pitch, yaw = m.orientation_deg
            print(f"tilt {roll:+.0f} {pitch:+.0f}")

        await tb.on_motion(on_motion)
        await tb.on_button(lambda b: b.pressed and print("fire!"))
        await asyncio.sleep(30)                         # your game loop here
        await tb.set_led("off")

asyncio.run(main())
```

API summary (all methods are `async`):

| Method | Returns / does |
|---|---|
| `TBGame.scan(timeout=6.0)` | `[(BLEDevice, rssi, board)]`, strongest first |
| `TBGame.connect(address=None, name=None, timeout=10, scan_timeout=10)` | connected `TBGame`; by address, by name, or the first board found |
| `read_info()` | `Info(protocol_version, board, available)` |
| `read_env()` | `Env(uptime_ms, temperature_c, humidity_pct, lux, uv_index, hall_mt, hall_alert, hall_tamper, sound_db, battery_v, die_temperature_c)`; a missing value is `None` |
| `read_motion()` | `Motion(uptime_ms, accel_g, gyro_dps, orientation_deg)` (tuples of 3) |
| `read_button()` | `Button(pressed, press_count)` |
| `read_led()` / `set_led(mode, on_ms, off_ms)` | LED state / change it |
| `read_config()` / `set_config(sensors=None, **fields)` | `Config(sensor_mask, env_period_ms, motion_period_ms, tx_power_dbm, adv_interval_ms, hall_threshold_mt)`; `set_config` changes only the fields passed |
| `read_name()` / `set_name(name)` | device name |
| `command(name)` | `"calibrate"`, `"factory-reset"`, `"reboot"`, `"identify"`, `"reset-orientation"` |
| `on_env(cb)` / `on_motion(cb)` / `on_button(cb)` | subscribe; `cb` gets the decoded object, and may be a plain or `async` function |
| `disconnect()` | also called automatically by `async with` |

`tb.client` is the underlying `bleak.BleakClient` if you need raw access.

## BlueZ quirks

Linux's Bluetooth stack (BlueZ) needed workarounds. They are all handled in
`tb_game.py`, but it helps to know about them:

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
   until found. That made 20 of 20 connect cycles succeed.
4. **Cache fallback.** If scanning still finds nothing, `connect()` tries the
   boards BlueZ has in its device cache (D-Bus `GetManagedObjects`) and
   connects directly.
5. **Reading a subscribed characteristic looks like a notification.** BlueZ
   reports a read as a value change, so `read_button()` while subscribed with
   `on_button()` also fires the callback once. Ignore duplicates, or don't
   read what you're subscribed to.
6. **Service cache after firmware changes.** If you change the GATT database
   and the client still sees old characteristics, remove the cached device:
   `bluetoothctl remove 58:8E:81:66:B0:DF`.

On macOS and Windows, bleak uses the native stacks. Quirks 1–4 are
Linux-specific, and `bluez` options are ignored there.
