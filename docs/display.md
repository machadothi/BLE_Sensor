# OLED display

An optional **0.96" SSD1306 128×64 OLED** (the common 4-pin I2C module) shows
the readings on the board itself, one at a time, changing every 2 seconds by
default. Which readings it cycles through, and how long each one stays on
screen, are chosen in the phone app or with `ble-sensor display`.

- [Wiring](#wiring)
- [What it shows](#what-it-shows)
- [Choosing the readings](#choosing-the-readings)
- [Settings](#settings)
- [How it works](#how-it-works)
- [Changing the look](#changing-the-look)
- [Troubleshooting](#troubleshooting)

---

## Wiring

The display goes on the board's I2C bus, which it shares with the on-board
sensors. Use the 20-pin expansion header:

| Display pin | Header pin | Board signal |
|---|---|---|
| GND | **1** | GND |
| VCC | **20** | 3.3 V |
| SCL | **15** | PD3 (I2C SCL) |
| SDA | **16** | PD2 (I2C SDA) |

- **3.3 V only.** SSD1306 modules run fine on 3.3 V. Don't use pin 18 (5 V).
- **No pull-ups needed.** The bus already has them on the board.
- **Addresses.** The display uses I2C address 0x3C (some modules 0x3D);
  the firmware tries both. It doesn't clash with the sensors (0x30, 0x40, 0x55).
- **Detected at startup only.** After plugging a display in, reset the board
  (`ble-sensor reboot`, or unplug USB).

## What it shows

After a 1.5 s start screen (board name), one reading per page:

| Page | Shows |
|---|---|
| Temperature | °C, 0.1 |
| Humidity | %, 0.1 |
| Light | lux |
| UV index | BRD4184A only |
| Magnetic | mT, 0.01 |
| Sound | dB, BRD4184B only |
| Supply | V, 0.01 |
| Chip temp | the EFR32's own temperature, °C |
| Orientation | X (roll), Y (pitch), Z (yaw) in whole degrees |
| Button | presses since boot |

Every page has the same layout: an icon and title at the top, the value large
in the middle, and dots at the bottom showing which page of how many is on
screen. A small Bluetooth mark appears top-right while the app or
`ble-sensor` is connected.

- **Missing data, missing page.** A page only appears when its reading exists:
  a sensor switched off in Settings, or absent on this board revision, is
  skipped.
- **Nothing selected.** If no selected page has data, the screen says
  "No reading selected".
- **Orientation needs the IMU.** With a display the IMU runs all the time,
  also without a connection (`src/sampling.c`).

## Choosing the readings

**Phone app:** Settings → **Display** card:
- **Each reading for:** a slider from 1 to 60 s (half-second steps up to
  10 s). It's sent to the board when you let go, and the new rhythm starts
  right away.
- **One switch per reading**, plus *All* / *None*. Each switch takes effect
  immediately, at the display's next page change.
- Readings this board can't show are greyed out.
- Without a display connected, the card says so and the switches are disabled.

**Command line:**

```sh
ble-sensor display                                        # connected? which pages? how long?
ble-sensor display --pages temperature,humidity,orientation
ble-sensor display --page-time 5                          # 5 s per reading (1-60)
ble-sensor display --pages all
ble-sensor display --pages none
```

Page names: `temperature`, `humidity`, `light`, `uv`, `magnetic`, `sound`,
`supply`, `chip-temperature`, `orientation`, `button`.

Both choices are stored on the board and survive reboots. A factory reset
selects all pages again and goes back to 2 s. Over Bluetooth it's the **Display** characteristic
(`a7e40009`), described in
[capabilities.md](capabilities.md#display-5-bytes).

## Settings

In the **Display** section of `firmware/src/app_config.h` (then `make flash`):

| Setting | Default | Meaning |
|---|---|---|
| `DISPLAY_ENABLED` | 1 | 0 = compile the display code out |
| `DEFAULT_DISPLAY_PAGE_MS` | 2000 | time per reading until changed in the app (1000–60000) |
| `DISPLAY_REFRESH_MS` | 500 | how often the shown value is redrawn |
| `DISPLAY_SPLASH_MS` | 1500 | start screen |
| `DISPLAY_CONTRAST` | 0xCF | brightness, 0x00–0xFF |
| `DEFAULT_DISPLAY_PAGES` | all | pages shown until chosen in the app |
| `DISPLAY_COLUMN_OFFSET` | 0 | 2 for SH1106 modules (see [Troubleshooting](#troubleshooting)) |
| `DISPLAY_ROTATE_180` | 0 | 1 = upside down |

## How it works

All display code lives in `firmware/src/display/`:

| File | Role |
|---|---|
| `display.c` | page rotation, fade between pages, live refresh; the only file the rest of the firmware calls |
| `pages.c` | draws each screen (no hardware access, so it also compiles on a PC) |
| `canvas.c` | 128×64 one-bit frame buffer: text, icons, lines, dots |
| `ssd1306.c` | the display controller over I2C |
| `display_assets.c/.h` | fonts and icons, **generated**, don't edit by hand |

- **Shared bus, no blocking.** A full screen is 1 KB, about 90 ms on the
  100 kHz I2C bus shared with the sensors. Instead of blocking that long, the
  frame goes out one eighth at a time (about 12 ms each), with a pause between
  pieces so motion sampling and Bluetooth keep running.
- **Page change.** The brightness fades down in 4 steps, the next page is drawn
  and sent, and the brightness fades back up.
- **Live values.** While a page is shown it is redrawn every
  `DISPLAY_REFRESH_MS`. A page change that arrives during such a redraw waits
  for it to finish instead of being dropped. (The page and refresh timers share
  a rhythm, so an early version never moved past the first page.)
- **RAM.** The frame buffer takes 1 KB of RAM, which comes out of the heap the
  Bluetooth stack uses; plenty remains.

## Changing the look

**Layout and text** are in `pages.c`: positions at the top of the file, one
`case` per page in `pages_draw()`.

**Fonts and icons** are generated by `firmware/tools/make_display_assets.py`:
- DejaVu fonts rendered at fixed sizes: the big value is 34 px DejaVu Sans,
  units 15 px, titles 10 px bold.
- 16×16 icons drawn as ASCII art right in the script, `#` = lit pixel.

Change the script, then run it. It needs Pillow and the DejaVu fonts
(Ubuntu: `sudo apt install python3-pil fonts-dejavu-core`):

```sh
python3 firmware/tools/make_display_assets.py   # rewrites src/display/display_assets.c/.h
cd firmware && make flash
```

**Preview on a PC.** To check a layout without the board, compile `pages.c`,
`canvas.c` and `display_assets.c` with a small `main()` that fills a
`display_data_t`, calls `pages_draw()`, and writes `canvas_buffer` to an image.
These three files have no hardware or SDK dependencies (only
`ble/ble_protocol.h`), which is how the screens were designed.

## Troubleshooting

**`No OLED display found` in the log (`make log`), or the app says "No display
found".**
- Check the wiring and that VCC is on pin 20 (3.3 V).
- Then reset the board: the display is looked for only at startup.

**Picture shifted by 2 pixels, or a stripe of noise along one edge.** Many
modules sold as SSD1306 are really SH1106 (132 columns). Set
`DISPLAY_COLUMN_OFFSET 2`.

**Upside down.** Set `DISPLAY_ROTATE_180 1`.

**Stuck on one page.** Check `ble-sensor display`: maybe only one page is
selected, or only one selected page has data. Sensors switched off in
Settings don't appear.

**Too bright / too dim.** `DISPLAY_CONTRAST`. OLEDs age with brightness and
static images, and lower contrast extends their life.
