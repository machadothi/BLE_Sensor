# Firmware: how it is built and how it runs

- [Build pipeline](#build-pipeline)
- [Where is main()?](#where-is-main)
- [Source layout](#source-layout)
- [Project configuration: app_config.h](#project-configuration-app_configh)
- [Runtime behaviour](#runtime-behaviour)
- [Design decisions and why](#design-decisions-and-why)
- [Common changes](#common-changes)

---

## Build pipeline

```
firmware/ble_sensor_brd4184a.slcp ┐      (project: components + our sources + settings)
firmware/config/btconf/*.btconf ┼─► slc generate ──► firmware/build/brd4184a/
tools/simplicity_sdk ───────────┘                      ├── main.c                (from SDK template)
                                                       ├── autogen/              (init calls, gatt_db.c/.h,
                                                       │                          linkerfile.ld, instances)
                                                       ├── config/               (per-component config headers)
                                                       └── ble_sensor_brd4184a.Makefile
                                    make ──► arm-none-eabi-gcc ──► build/debug/ble_sensor_brd4184a.hex
                                    Commander ──► J-Link ──► EFR32BG22 flash
```

**The `.slcp` file is the project.** It lists:

- **components**: SDK building blocks pulled in by name. Examples:
  `bluetooth_stack`, `bluetooth_feature_gap`, `sensor_rht` (Si7021 wrapper),
  `sensor_light` (Si1133), `imu_driver` (ICM-20648 + sensor fusion),
  `simple_led`/`simple_button` (with instance names `led0`/`btn0`), `nvm3_default`
  (flash key-value store), `app_timer`, `app_log`, `brd4184a` (board pins/config).
  slc resolves their dependencies and copies or links their sources.
- **source/include**: our files in `src/`.
- **config_file**: replaces the SDK's default GATT database with ours.
- **configuration**: overrides of config-header defines (stack size, VCOM on,
  sensor enable, log line endings).

There are **two `.slcp` files** because the board revisions differ in hardware:
`brd4184a` uses `sensor_light` (Si1133, lux + UV); `brd4184b` uses `sensor_lux`
(VEML6035) and adds `mic_driver`. The C code adapts with
`#ifdef SL_CATALOG_<COMPONENT>_PRESENT` (in `src/sensors/env_sensors.c`,
`src/sensors/sound.c` and `sensors_board_id()`); slc writes those defines into
`autogen/sl_component_catalog.h`.

`build/` is disposable and git-ignored. The Makefile deletes and regenerates it
whenever the `.slcp` or `.btconf` changes, because slc never overwrites config
files that already exist in the output folder.

## Where is main()?

`src/` has no `main()` on purpose. **slc generates it** into
`firmware/build/<board>/main.c` from an SDK template. Simplified:

```c
int main(void)
{
  sl_main_init();             // SDK: chip, clocks, drivers, services, BLE stack
  app_init();                 // OURS (src/app.c)
  while (1) {
    sl_main_process_action(); // SDK: timers, Bluetooth stack → sl_bt_on_event()
    app_process_action();     // OURS (src/app.c)
    sl_power_manager_sleep(); // SDK: sleep (EM1/EM2) until the next interrupt
  }
}
```

What the SDK does around our hooks:

1. **`sl_main_init()`** (`tools/simplicity_sdk/platform/service/sl_main/src/sl_main_init.c`)
   sets up memory, interrupts, DC-DC, crystals (HFXO 38.4 MHz, LFXO 32.768 kHz),
   clock manager, power manager and sleep timer. It then calls
   `sl_main_second_stage_init()`, which runs five generated functions from
   `build/<board>/autogen/sl_event_handler.c`:
   - `sl_platform_init()`: board pre-init, clock tree
   - `sl_driver_init()`: GPIO, I²C (`sl_i2cspm_sensor`), button and LED instances
   - `sl_service_init()`: VCOM UART, crypto, iostream (`printf`/`app_log`)
   - `sl_stack_init()`: radio PA, **Bluetooth stack**
   - `sl_internal_app_init()`: `app_log_init()`
2. **`app_init()`** (ours) probes and initializes the sensors.
3. **`sl_main_process_action()`** each loop pass runs app timers that are due
   (our timer callbacks run here, *not* in an interrupt) and `sl_bt_step()`.
   That one delivers queued Bluetooth events to **`sl_bt_on_event()`** (ours,
   in `src/app.c`). The SDK provides a weak default in `autogen/sl_bluetooth.c`,
   and our definition replaces it.
4. **`app_process_action()`** (ours) feeds the microphone level filter on B.
5. **`sl_power_manager_sleep()`** puts the CPU to sleep. Interrupts (radio,
   timers, button) wake it.

So the application is purely **event-driven**: everything happens in
`sl_bt_on_event()` and in app-timer callbacks.

## Source layout

`src/app.c` only decides **which module handles what**. Read it first: about
130 lines that map every Bluetooth event to a module.

| Path | Owns |
|---|---|
| `src/app.c` / `app.h` | `app_init`, `app_process_action`, `sl_bt_on_event` dispatch, `app_apply_config()` (pushes a new config to sensors and timers) |
| `src/app_config.h` | **every tunable value**, see the next section |
| `src/sampling.c` | the env timer (always running) and the motion timer (only while connected), period changes; feeds the GATT service and the Home Assistant broadcast |
| `src/uptime.h` | milliseconds since boot (packet timestamps) |
| `src/ble/ble_protocol.h` | **the wire contract**: service UUID, packed structs, sensor/valid bits, limits, command codes, size checks. `host/ble_sensor/protocol.py` mirrors it. |
| `src/ble/advertising.c` | the connectable advertisement and scan response (for the app / `ble-sensor`), TX power, interval |
| `src/display/` | **the optional OLED**: `display.c` (page rotation, the only entry point), `pages.c` (screens), `canvas.c` (drawing), `ssd1306.c` (controller), `display_assets.c` (generated fonts/icons). Switch: `DISPLAY_ENABLED`. Guide: [display.md](display.md) |
| `src/home_assistant/bthome.c` | **everything for Home Assistant**: a second, non-connectable broadcast in BTHome format, with its own fixed address. Switch: `HOME_ASSISTANT_ENABLED`. Guide: [home-assistant.md](home-assistant.md) |
| `src/ble/gatt_service.c` | connection handle, subscriptions, notifications (`gatt_service_publish_*`), one read/write handler per characteristic, ATT errors |
| `src/ble/device_name.c` | the name: stored or default, GAP and custom characteristic kept in sync |
| `src/control/led.c` | off/on/blink and the identify overlay |
| `src/control/button.c` | the GPIO interrupt → external signal → 25 ms debounce → publish |
| `src/control/commands.c` | calibrate, factory reset, reboot, identify, zero orientation |
| `src/sensors/sensors.c` | **the only sensor interface the rest uses**: available/enabled masks, board id, building Env packets |
| `src/sensors/env_sensors.c` | Si7021, Si1133/VEML6035, Si7210, supply voltage, die temperature; non-blocking conversions |
| `src/sensors/imu.c` | ICM-20648 power, rate, samples, calibration |
| `src/sensors/sound.c` | microphone level (BRD4184B; stubs elsewhere) |
| `src/storage/settings.c` | the live config + NVM3 load/save/validate/factory reset |
| `config/btconf/gatt_configuration.btconf` | GATT database (XML). `id="env"` becomes the handle `gattdb_env` in the generated `gatt_db.h`. |
| `ble_sensor_brd4184{a,b}.slcp` | project definitions (see above); list every `.c` file under `source:` |
| `Makefile` | wraps slc, make and Commander with this repo's `tools/` |

Headers are included relative to `src/` (e.g. `#include "ble/gatt_service.h"`),
so the path says where a file lives.

Who calls whom:

| Module | Calls into |
|---|---|
| `app.c` | everything below except `control/led`, `control/commands` |
| `sampling` | `sensors`, `gatt_service` (publish), `bthome` (publish), `display` (publish), `settings` |
| `ble/gatt_service` | `led`, `commands`, `device_name`, `sensors`, `settings`, `app_apply_config()` |
| `ble/advertising` | `device_name`, `sensors` (board id), `settings`, `bthome` (paused around TX power changes) |
| `home_assistant/bthome` | only the Bluetooth stack |
| `control/button` | `gatt_service` (publish), `bthome` (press event), `display` (press count) |
| `display/display` | `ssd1306` (I2C), `pages`, `settings` (stored page choice) |
| `control/commands` | `led`, `device_name`, `sensors`, `settings`, `app_apply_config()` |
| `ble/device_name` | `settings` |
| `sensors/sensors` | only `env_sensors`, `imu`, `sound` |
| `storage/settings` | only NVM3 |

The sensor and storage modules know nothing about Bluetooth, so they can be
reused or tested on their own.

SDK code worth knowing (all under `tools/simplicity_sdk/`):

| Path | What |
|---|---|
| `app/bluetooth/example/bt_soc_thunderboard/` | Silicon Labs' own Thunderboard demo. Our starting reference. |
| `app/bluetooth/common/sensor_rht`, `sensor_light`, `sensor_lux` | Sensor wrapper components |
| `hardware/driver/{si70xx,si1133,si7210,veml6035,icm20648,imu,mic}` | Chip drivers |
| `hardware/board/config/brd4184a/` | Pin assignments for this board |
| `protocol/bluetooth/inc/sl_bt_api.h` | Full Bluetooth API reference (`sl_bt_*`) |

## Project configuration: app_config.h

[`src/app_config.h`](../firmware/src/app_config.h) holds every value you might
want to tune. Each name carries its unit, and each value has a short note on
why it is what it is. Change it and run `make flash`.

| Section | Examples |
|---|---|
| Firmware identity | `FIRMWARE_VERSION` |
| Factory defaults | `DEFAULT_ENV_PERIOD_MS`, `DEFAULT_MOTION_PERIOD_MS`, `DEFAULT_TX_POWER_DBM_X10`, `DEFAULT_NAME_PREFIX`, LED blink times |
| Timing | `ENV_FIRST_SAMPLE_DELAY_MS`, `BUTTON_DEBOUNCE_MS`, `IDENTIFY_DURATION_MS`, `REBOOT_DELAY_MS`, `LED_BLINK_MIN_MS` |
| Bluetooth link | `CONN_INTERVAL_MIN/MAX`, `CONN_SUPERVISION_TIMEOUT`, `ADV_COMPANY_ID` |
| Sensors | hall range/hysteresis, Si7021/Si1133 conversion times, gyro offset calibration and still-detection, yaw deadband, power-up delays, microphone rate/buffer/smoothing, supply averaging |
| Display | `DISPLAY_ENABLED`, `DEFAULT_DISPLAY_PAGE_MS`, `DISPLAY_REFRESH_MS`, `DISPLAY_CONTRAST`, `DEFAULT_DISPLAY_PAGES`, `DISPLAY_COLUMN_OFFSET`, `DISPLAY_ROTATE_180` |
| Home Assistant | `HOME_ASSISTANT_ENABLED`, `HOME_ASSISTANT_SEND_ORIENTATION`, `HOME_ASSISTANT_PACKET_SWITCH_MS`, `HOME_ASSISTANT_BROADCAST_INTERVAL_MS` |
| Storage | NVM3 keys (config, name, gyro calibration, display pages, display page time) |

What is deliberately **not** in it:

- **Wire-protocol values** (UUIDs, layouts, the allowed ranges `LIMIT_*`,
  command codes) are in `src/ble/ble_protocol.h`, because the Python client
  must match them.
- **Fixed standards** (ATT error codes, advertising AD types) sit as named
  constants in the one file that uses them.
- **SDK and board settings** (pins, stack size, VCOM) go in the `.slcp` files
  under `configuration:`.

Changing a factory default affects new boards, boards after a factory reset,
and boards whose stored config is invalid. A board with a saved config keeps
it: run `ble-sensor factory-reset` to pick up the new defaults.

## Runtime behaviour

```
boot ─► load config + name from NVM3
            ├─► start BTHome broadcast (Home Assistant), own address D8:…
            ├─► advertise (name, service UUID, mfg data) for the app / ble-sensor
            └─► start env timer (env sensors run from now on), and the motion
                timer if Home Assistant gets the angles (mic stays off)

   every env_period ─► read RHT/light/hall/supply/die temp ─► store in GATT DB,
                       notify a subscribed client, hand to the BTHome broadcast
   every 1 s ─► BTHome broadcast switches between its environment and
                orientation packets (new packet id each time)
   button edge ─► ISR ─► sl_bt_external_signal ─► 25 ms debounce
                       ─► store + notify, BTHome "press" event

   client connects ─► request 7.5–15 ms connection interval
            │         power up IMU (and mic on B), start motion timer
   every motion_period/2 ─► if IMU has a new sample ─► remove gyro offset, fuse,
            │                store + notify
   client disconnects ─► stop motion timer, power down IMU/mic ─► apply TX power,
                         advertising interval, name ─► advertise again
                         (the BTHome broadcast never stops)
```

The UART log (`make log`, 115200 8N1) prints boot, sensor probe, connections,
MTU, config changes and errors.

## Design decisions and why

- **Environmental sensors always run; the microphone only while connected;
  the IMU depends.** The always-on part feeds Home Assistant. The IMU runs
  continuously while Home Assistant gets the angles
  (`HOME_ASSISTANT_SEND_ORIENTATION`), otherwise only during a connection,
  because it draws the most current.
- **Shared sensor power on BRD4184A.** Si7021, Si1133 and Si7210 share one
  enable pin (`PA4`), so they are powered once at boot. Clearing them from
  `sensor_mask` only stops sampling. The IMU and microphone have their own
  enable pins and are really switched off.
- **Pipelined slow sensors.** The SDK's blocking calls wait about 210 ms for
  the Si1133 and about 17 ms for the Si7021 (measured). That stalled the main
  loop, and IMU samples were lost. `sensors/env_sensors.c` starts a conversion
  on one env tick and collects it on the next, so light and RHT values are up
  to one env period old.
- **Motion polled at half the period.** The ICM-20648 produces one sample per
  period; polling at the same rate missed samples because of timer jitter.
  Only new samples are sent.
- **Button via external signal.** `sl_button_on_change()` runs in interrupt
  context, where Bluetooth API calls are not allowed. It only calls
  `sl_bt_external_signal()`. The event arrives in `sl_bt_on_event()`, which
  calls `button_on_signal()` to start a 25 ms debounce timer; the contacts
  bounce, and one press used to count twice.
- **TX power and advertising interval apply after disconnect.** The stack
  rejects TX power changes while connected or advertising, so both are applied
  in `advertising_start()` (`src/ble/advertising.c`).
- **Manufacturer data in advertising** (`0x02FF` = Silicon Labs, board id,
  protocol version). It lets scanners identify the board revision, and it makes
  BlueZ report every advertisement (see [host-client.md](host-client.md#bluez-quirks)).
- **Custom Name characteristic.** BlueZ hides the standard GAP Device Name from
  clients, so the name is also exposed as `a7e40008`. Both stay in sync.
- **Two advertising sets.** The connectable one (app, `ble-sensor`) and the
  BTHome one (Home Assistant) are independent; `SL_BT_CONFIG_USER_ADVERTISERS`
  is 2 in both `.slcp` files. The BTHome set uses its own static random
  address, because many scanners drop repeated reports from one address and
  then missed one of the two broadcasts.
- **Our own IMU fusion step.** The SDK's fusion never removes the gyro offset
  (its `sl_imu_calibrate_gyro()` only restarts the chip), and nothing corrects
  yaw because the ICM-20648 has no magnetometer, so yaw drifted on a still
  board. `sensors/imu.c` runs the SDK's fusion functions on its own state,
  with the offset subtracted. The offset is learned by the Calibrate command
  (stored in flash) and refined automatically whenever the board lies still.
- **No bootloader / no OTA.** The app is linked at `0x0` and runs standalone.
  Adding the `in_place_ota_dfu` component would move it to `0x12000` and
  require a Gecko bootloader + apploader in flash.
- **NVM3 sits at the end of flash.** A normal `make flash` only erases the
  pages the image covers, so config and name survive reflashing.
  `make erase` wipes them.

## Common changes

**Tune a value.** Edit `src/app_config.h` and run `make flash`.

**Add a characteristic.**
1. Add it to `config/btconf/gatt_configuration.btconf` with an `id`. Use
   `type="user"` if the app should answer reads and writes itself.
2. Add its struct to `src/ble/ble_protocol.h`, with a `_Static_assert` on its size.
3. Handle `gattdb_<id>` in `gatt_service_on_read_request()` or
   `gatt_service_on_write_request()` in `src/ble/gatt_service.c`. For writes,
   add a small `write_<name>()` handler like the existing ones.
4. Mirror the layout in `host/ble_sensor/protocol.py` and add a method to
   `host/ble_sensor/client.py`.
5. Do the same in the phone app: `android/.../ble/Protocol.kt`
   (see [android-app.md](android-app.md#adding-something-to-the-app)).

The Makefile regenerates the GATT database automatically.

**Add a sensor.** Add its SDK component to **both** `.slcp` files, read it in
`src/sensors/env_sensors.c` (or a new file under `src/sensors/`, also listed
under `source:` in both `.slcp` files), give it a `SENSOR_BIT_*` and an Env
field in `ble_protocol.h`, and mirror both in `protocol.py`.

**Add an SDK component.** Add `- id: <component>` to **both** `.slcp` files.
Component IDs are listed in the `*.slcc` files under
`tools/simplicity_sdk/` (`find tools/simplicity_sdk -name '*.slcc'`).

**Change a pin or a driver setting.** Generated config headers live in
`build/<board>/config/`, but they are recreated on every regeneration.
Override values in the `configuration:` list of the `.slcp` instead.

**Change the protocol.** Bump `BLE_SENSOR_PROTOCOL_VERSION` in
`src/ble/ble_protocol.h` and `PROTOCOL_VERSION` in `host/ble_sensor/protocol.py`.
The `_Static_assert`s catch any struct whose size no longer matches the GATT
length.
