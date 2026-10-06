# Home Assistant

The board broadcasts its readings in the **BTHome v2** format
(<https://bthome.io>). Home Assistant reads BTHome natively, so it discovers the
board by itself: no pairing, no connection, no add-on, nothing to install.
It is **read-only**: Home Assistant can't change settings or the LED, and
nothing it does affects the phone app or `ble-sensor`.

Tested with Home Assistant OS 18.2 / Core 2026.8.1 on a Raspberry Pi 4, using
its built-in Bluetooth.

- [What Home Assistant gets](#what-home-assistant-gets)
- [Setup](#setup)
- [Using the button in automations](#using-the-button-in-automations)
- [How it works](#how-it-works)
- [Changing or switching it off](#changing-or-switching-it-off)
- [Troubleshooting](#troubleshooting)

---

## What Home Assistant gets

| Entity | Source | Unit / resolution |
|---|---|---|
| Temperature | Si7021 | °C, 0.01 |
| Humidity | Si7021 | %, 0.01 |
| Illuminance | Si1133 (A) / VEML6035 (B) | lx, 0.01 |
| UV index | Si1133 (BRD4184A only) | 0.1 |
| Button | BTN0 | event: `press` |
| Rotation, Rotation 2, Rotation 3 | IMU orientation: **X (roll), Y (pitch), Z (yaw)** | °, 0.1 |
| Signal strength | the Pi's receiver | dBm (disabled by default in HA) |
| Packet id | changes with each new reading | (diagnostic) |

BTHome can't name values, so Home Assistant calls all three "Rotation". Their
**entity IDs** tell them apart, numbered in packet order (the first has no
number):

| Entity ID ends with | Axis | Changes when you… |
|---|---|---|
| `_rotation` | **X (roll)** | tilt the board left/right around its long side |
| `_rotation_2` | **Y (pitch)** | tilt it forward/back |
| `_rotation_3` | **Z (yaw)** | turn it flat on the table, like a compass |

To rename them, open each entity → ⚙️, check its **Entity ID**, and set
**Name** to "X (roll)", "Y (pitch)" or "Z (yaw)". Z (yaw) has no absolute reference because the board has
no magnetometer: it's relative to where the board was at power-up, and it
holds still while the board does (see the gyro notes in
[firmware.md](firmware.md#design-decisions-and-why)).

Not sent:
- **Supply voltage.** Left out by choice.
- **Magnetic field.** BTHome has no type for it.
- **Raw acceleration and gyro rates.** Only the angles are sent.
- **Sound** (BRD4184B). It only runs during a connection.

The orientation needs the IMU, so with orientation on, the IMU runs all the
time. That's fine on USB, but drains a coin cell much faster. It can be switched
off ([below](#changing-or-switching-it-off)).

A sensor switched off in the board's settings simply disappears from the
broadcast.

**Update rate:** the angles are refreshed every 2 s (they alternate with the
environment packet). The environment values follow the board's **environment
period** (Settings → Sampling in the app, or `ble-sensor config --env-period`;
default 1 s). Home Assistant
only records a new state when a value changes, so a short period mostly costs
board power. 10–60 s is plenty for room monitoring.

## Setup

**Requirements:**
- Home Assistant with the **Bluetooth** integration. It's set up automatically
  when the machine has Bluetooth: the Raspberry Pi's built-in adapter works,
  and so does an ESPHome Bluetooth proxy near the board.
- The board within Bluetooth range of the Pi or the proxy (~10 m indoors).
- The BLE Sensor firmware with `HOME_ASSISTANT_ENABLED 1` in
  `firmware/src/app_config.h` (the default).

**Steps:**
1. Flash the firmware (`cd firmware && make flash`).
2. In Home Assistant, open **Settings → Devices & services**. Within a minute,
   a discovered **BTHome** device appears, named like *BTHome sensor B0DF*.
   The address is `D8:8E:81:66:B0:DF`; see [How it works](#how-it-works) for
   why it's not the board's own address.
3. Click **Add** → **Submit**. The BTHome integration is installed with it, if
   it wasn't already.
4. Open the new device and rename it (✏️) to something like "Desk Thunderboard".
   Its entities are listed under **Sensors** and **Events**.

To confirm the Pi hears the board, run this in the Home Assistant terminal
(Terminal & SSH add-on):

```sh
timeout 12 bluetoothctl --timeout 10 scan le | grep -i 'D8:8E:81:66:B0:DF'
```

## Using the button in automations

The button is an **event** entity (`event.<device>_button`). Example:
pressing BTN0 toggles a lamp.

```yaml
automation:
  - alias: "Thunderboard button toggles desk lamp"
    triggers:
      - trigger: state
        entity_id: event.desk_thunderboard_button
    conditions:
      - condition: state
        entity_id: event.desk_thunderboard_button
        attribute: event_type
        state: press
    actions:
      - action: light.toggle
        target:
          entity_id: light.desk_lamp
```

In the UI: **Settings → Automations → Create → Trigger: Entity → State** on
the button entity, then add the condition on *event type = press*.

## How it works

Firmware side (everything Home Assistant-specific is in
`firmware/src/home_assistant/`, with its settings in the **Home Assistant**
section of `firmware/src/app_config.h`):

- **A second advertising set.** The board sends two independent broadcasts.
  - **Connectable:** the service UUID, board id and name. This is what the app
    and `ble-sensor` look for. Home Assistant doesn't use it.
  - **BTHome**, non-connectable: the readings, for Home Assistant. It keeps
    running while a client is connected.

  The project files raise `SL_BT_CONFIG_USER_ADVERTISERS` to 2 to allow this.
- **Its own address.** Many Bluetooth controllers (this computer's among
  them) drop repeated reports from one address. With both broadcasts
  on the board's address, scanners only saw one of them. The BTHome broadcast
  therefore uses a fixed "static random" address: the board's address with the
  top two bits set (`58:8E:…` → `D8:8E:…`). It never changes, so Home
  Assistant keeps the same device.
- **Always sampling.** The environmental sensors run all the time, not only
  during a connection, so there is something to broadcast. The IMU also does
  when the orientation is sent. The microphone still only runs during a
  connection (`src/sampling.c`).
- **Two alternating packets.** A classic advertisement holds 31 bytes, too few
  for everything. The broadcast switches between two packets every
  `HOME_ASSISTANT_PACKET_SWITCH_MS` (1 s). Between switches, the current
  packet is repeated every `HOME_ASSISTANT_BROADCAST_INTERVAL_MS` (250 ms), so
  a missed copy doesn't matter.

  ```
  A, environment (24 bytes)              B, orientation (19 bytes)
  02 01 06      flags                    02 01 06      flags
  len 16 D2 FC  service data, BTHome     len 16 D2 FC  service data, BTHome
  40            BTHome v2, unencrypted   40            BTHome v2, unencrypted
  00 <id>       packet id                00 <id>       packet id
  02 <int16>    temperature × 100        3F <int16>    rotation X (roll) × 10
  03 <uint16>   humidity × 100           3F <int16>    rotation Y (pitch) × 10
  05 <uint24>   illuminance × 100        3F <int16>    rotation Z (yaw) × 10
  3A <uint8>    button: 0 none, 1 press
  46 <uint8>    UV index × 10
  ```

  - The flags are required: BlueZ, which Home Assistant uses, ignores the
    packet without them.
  - Objects go in increasing id order, as BTHome requires.
  - The packet id changes with every switch. Home Assistant skips a packet
    whose id equals the previous one, so the repeats count once.
  - A button press is broadcast immediately, in an environment packet with a
    new id. It's repeated until the next switch and then cleared, so it fires
    exactly one event.
- **Not encrypted.** Anyone nearby with a BLE scanner can read the values,
  just like any thermometer of this kind. BTHome supports AES encryption with a
  bind key that you enter in Home Assistant. It isn't implemented here; it would
  go in `home_assistant/bthome.c`.

## Changing or switching it off

| Want | Change (in `firmware/src/app_config.h`, then `make flash`) |
|---|---|
| No Home Assistant broadcast | `HOME_ASSISTANT_ENABLED 0`. The module compiles to empty functions. |
| No orientation angles (IMU off without a connection, saves power) | `HOME_ASSISTANT_SEND_ORIENTATION 0`. Only the environment packet is sent. |
| Angles more or less often | `HOME_ASSISTANT_PACKET_SWITCH_MS` (each packet kind comes every 2 × this) |
| Fewer repeats on air (saves power) | `HOME_ASSISTANT_BROADCAST_INTERVAL_MS`, e.g. 1000 |
| Readings less or more often | No rebuild: the environment period in the app or `ble-sensor config --env-period` |

To add a value, add the BTHome object to one of the two packets in
`home_assistant/bthome.c` (`put_environment` or `put_orientation`). Keep the
ids in increasing order, check the format at <https://bthome.io/format/>, and
stay within 31 bytes per packet.

**After removing a value** (like the voltage): Home Assistant keeps the old
entity, but it no longer updates. Delete it: open the device, click the
entity, ⚙️ → **Delete**.

## Troubleshooting

**Not discovered.**
1. Is the board in range of the Pi? Run the `bluetoothctl` check from [Setup](#setup).
2. Is the Bluetooth integration set up and the adapter shown as working?
   (Settings → Devices & services → Bluetooth)
3. Is the firmware built with `HOME_ASSISTANT_ENABLED 1`? From this computer:
   `ble-sensor scan` finds the board's normal broadcast. A BTHome check with
   Python is in [troubleshooting.md](troubleshooting.md#home-assistant).

**Values update slowly.** They follow the board's environment period. Check
it with `ble-sensor config`.

**Device shows "unavailable".** Home Assistant marks a BTHome device
unavailable when no broadcast has arrived for a while. Check the board's power
and its distance to the Pi.

**Two BTHome devices discovered** ("BTHome sensor B0DF" and
"BLE-Sensor-B0DF B0DF"). The second is the board's own address
`58:8E:81:66:B0:DF`. An early test firmware sent BTHome from it, before the
broadcast got its own address. Add "BTHome sensor B0DF" (`D8:8E:…`) and
**Ignore** the other. If it was added already, delete the device whose address
is `58:8E:…`: it never receives values.

**Ignored the discovery by mistake.** Settings → Devices & services → BTHome →
⋮ → *Show ignored*, then re-add it.
