# Firmware: how it is built and how it runs

- [Build pipeline](#build-pipeline)
- [Where is main()?](#where-is-main)
- [Source files](#source-files)
- [Runtime behaviour](#runtime-behaviour)
- [Design decisions and why](#design-decisions-and-why)
- [Common changes](#common-changes)

---

## Build pipeline

```
firmware/tb_game_brd4184a.slcp ─┐      (project: components + our sources + settings)
firmware/config/btconf/*.btconf ┼─► slc generate ──► firmware/build/brd4184a/
tools/simplicity_sdk ───────────┘                      ├── main.c                (from SDK template)
                                                       ├── autogen/              (init calls, gatt_db.c/.h,
                                                       │                          linkerfile.ld, instances)
                                                       ├── config/               (per-component config headers)
                                                       └── tb_game_brd4184a.Makefile
                                    make ──► arm-none-eabi-gcc ──► build/debug/tb_game_brd4184a.hex
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
`#ifdef SL_CATALOG_<COMPONENT>_PRESENT`; slc writes those defines into
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
   That one delivers queued Bluetooth events to **`sl_bt_on_event()`** (ours).
   The SDK provides a weak default in `autogen/sl_bluetooth.c`, and our
   definition in `app.c` replaces it.
4. **`app_process_action()`** (ours) feeds the microphone level filter on B.
5. **`sl_power_manager_sleep()`** puts the CPU to sleep. Interrupts (radio,
   timers, button) wake it.

So the application is purely **event-driven**: everything happens in
`sl_bt_on_event()` and in app-timer callbacks.

## Source files

| File | Role |
|---|---|
| `src/tb_protocol.h` | **Single source of truth** for the BLE byte layouts, sensor bits, limits and command codes. `host/tb_game.py` mirrors it. |
| `src/app.c` | The application: `app_init`, `sl_bt_on_event` (boot, connect/disconnect, MTU, CCCD changes, user reads/writes), advertising, sampling timers, LED modes, button debounce, commands, config apply. |
| `src/sensors.c/.h` | All sensor access: init/probe, enable mask, pipelined Si7021 and Si1133 conversions, Si7210 hall + threshold, IMU start/stop/rate/calibration, microphone (B), supply voltage, die temperature. |
| `src/settings.c/.h` | Config + device name in **NVM3** (keys `0x01001` config, `0x01002` name; the BLE stack uses `0x40000–0x4FFFF`), defaults, validation. |
| `config/btconf/gatt_configuration.btconf` | GATT database (XML). slc turns it into `autogen/gatt_db.c/.h`, which gives `gattdb_tb_env` and the other handles. |
| `tb_game_brd4184{a,b}.slcp` | Project definitions (see above). |
| `Makefile` | Wraps slc, make and Commander with this repo's `tools/`. |

SDK code worth knowing (all under `tools/simplicity_sdk/`):

| Path | What |
|---|---|
| `app/bluetooth/example/bt_soc_thunderboard/` | Silicon Labs' own Thunderboard demo. Our starting reference. |
| `app/bluetooth/common/sensor_rht`, `sensor_light`, `sensor_lux` | Sensor wrapper components |
| `hardware/driver/{si70xx,si1133,si7210,veml6035,icm20648,imu,mic}` | Chip drivers |
| `hardware/board/config/brd4184a/` | Pin assignments for this board |
| `protocol/bluetooth/inc/sl_bt_api.h` | Full Bluetooth API reference (`sl_bt_*`) |

## Runtime behaviour

```
boot ─► load config + name from NVM3 ─► advertise (name, service UUID, mfg data)
            │
   client connects ─► request 7.5–15 ms connection interval
            │         enable sensors per sensor_mask
            │         start env timer (first sample after 250 ms) + motion timer
            │
   every env_period ─► read RHT/light/hall/battery/die temp ─► store in GATT DB,
            │           notify if the client subscribed
   every motion_period/2 ─► if IMU has a new sample ─► store + notify
   button edge ─► ISR ─► sl_bt_external_signal ─► 25 ms debounce ─► store + notify
            │
   client disconnects ─► stop timers, power down IMU/mic ─► apply TX power,
                         advertising interval, name ─► advertise again
```

The UART log (`make log`, 115200 8N1) prints boot, sensor probe, connections,
MTU, config changes and errors.

## Design decisions and why

- **Sensors only run while connected.** This saves power, and nobody can read
  values otherwise.
- **Shared sensor power on BRD4184A.** Si7021, Si1133 and Si7210 share one
  enable pin (`PA4`), so they are powered once at boot. Clearing them from
  `sensor_mask` only stops sampling. The IMU and microphone have their own
  enable pins and are really switched off.
- **Pipelined slow sensors.** The SDK's blocking calls wait about 210 ms for
  the Si1133 and about 17 ms for the Si7021 (measured). That stalled the main
  loop, and IMU samples were lost. `sensors.c` starts a conversion on one env
  tick and collects it on the next, so light and RHT values are up to one env
  period old.
- **Motion polled at half the period.** The ICM-20648 produces one sample per
  period; polling at the same rate missed samples because of timer jitter.
  Only new samples are sent.
- **Button via external signal.** `sl_button_on_change()` runs in interrupt
  context, where Bluetooth API calls are not allowed. It only calls
  `sl_bt_external_signal()`. The event arrives in `sl_bt_on_event()`, which
  starts a 25 ms debounce timer; the contacts bounce, and one press used to
  count twice.
- **TX power and advertising interval apply after disconnect.** The stack
  rejects TX power changes while connected or advertising, so both are applied
  in `advertising_start()`.
- **Manufacturer data in advertising** (`0x02FF` = Silicon Labs, board id,
  protocol version). It lets scanners identify the board revision, and it makes
  BlueZ report every advertisement (see [host-client.md](host-client.md#bluez-quirks)).
- **Custom Name characteristic.** BlueZ hides the standard GAP Device Name from
  clients, so the name is also exposed as `a7e40008`. Both stay in sync.
- **No bootloader / no OTA.** The app is linked at `0x0` and runs standalone.
  Adding the `in_place_ota_dfu` component would move it to `0x12000` and
  require a Gecko bootloader + apploader in flash.
- **NVM3 sits at the end of flash.** A normal `make flash` only erases the
  pages the image covers, so config and name survive reflashing.
  `make erase` wipes them.

## Common changes

**Add a characteristic.** Add it to `config/btconf/gatt_configuration.btconf`
(`type="user"` if the app should answer reads and writes itself), add its
struct to `src/tb_protocol.h`, and handle `gattdb_<id>` in `handle_user_read`
or `handle_user_write` in `app.c`. Mirror the layout in `host/tb_game.py`. The
Makefile regenerates automatically.

**Add an SDK component.** Add `- id: <component>` to **both** `.slcp` files.
Component IDs are listed in the `*.slcc` files under
`tools/simplicity_sdk/` (`find tools/simplicity_sdk -name '*.slcc'`).

**Change a pin or a driver setting.** Generated config headers live in
`build/<board>/config/`, but they are recreated on every regeneration.
Override values in the `configuration:` list of the `.slcp` instead.

**Change the protocol.** Bump `TB_PROTOCOL_VERSION` in `tb_protocol.h`. The
`_Static_assert`s there catch any struct whose size no longer matches the GATT
length.
