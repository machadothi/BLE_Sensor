# Troubleshooting

Problems hit while building this project, and their fixes.

## Flashing / debugger

**`ERROR: Cannot connect to J-Link` / `Could not open J-Link connection`**, but
`Emulator found with SN=...` is printed.
Your user can't open the USB device. Install the udev rule (`sudo sh
setup_udev.sh`), then **unplug and replug** the board. Check with
`ls -l /dev/bus/usb/*/*`: the J-Link node must be `crw-rw-rw-`.

**`commander-cli adapter probe` shows nothing**: wrong cable (charge-only), or
another program (Simplicity Studio, JLinkExe) holds the J-Link. Close it.

**Several boards plugged in**: `make flash JLINK_SN=<serial>`. Serial numbers
come from `commander-cli adapter probe`.

**Board seems dead after `make erase`**: expected. The chip is empty until you
`make flash` again. To get the factory demo back, flash
`backup/brd4184a_factory_flash.hex` (see [setup.md](setup.md#5-identify-the-board-and-back-up-its-flash)).

## Building

**Changed the `.btconf` but `gattdb_<new_id>` is undeclared**: slc does not
overwrite config files that already exist in `build/`. The Makefile handles
this by deleting `build/<board>` whenever the `.slcp` or `.btconf` changes. If
you edited something else under `config/`, run `make clean` first.

**Build uses the wrong SDK**: slc's per-user default (`~/.uc/cli/cli.config`)
can point at another checkout. The Makefile passes `--sdk ../tools/simplicity_sdk`
on every run. To check, `grep SDK_PATH firmware/build/brd4184a/*.project.mak`.

**`POST_BUILD_EXE is not defined`**: you added `post_build:` to a `.slcp`
(copied from an SDK example). It only creates OTA `.gbl` images and needs
Simplicity Studio's adapter packs. Remove it; we don't use OTA.

**`slc: java: not found`**: run slc through the Makefile, which puts
`tools/jdk/bin` on `PATH`, or prefix `PATH=$PWD/tools/jdk/bin:$PATH`.

**Downloads from silabs.com return 403**: use `wget` with a browser user agent,
not `curl` (see [setup.md](setup.md#2-download-the-toolchain-into-tools)).

## Bluetooth

**`No BLE Sensor board found`**
1. Is it advertising? Run `make log` (or a read-only serial terminal) and look
   for `Advertising as ...` after a reset. If the log ends with an error
   message instead, note it, then reset or reflash.
2. Is another computer or phone connected to it? Only one connection at a
   time; the board does not advertise while connected.
3. Is your Bluetooth on? `bluetoothctl show` should report `Powered: yes`.
4. Raw check: `bluetoothctl --timeout 10 scan le | grep BLE-Sensor`.

**Connected, but old characteristics / `Characteristic ... was not found`**:
BlueZ cached the old GATT layout. Run `bluetoothctl remove <address>`.

**Motion rate lower than configured**: the connection interval is chosen by
the computer, and Wi-Fi coexistence on combo chips also costs throughput. Check
the real rate from the `uptime_ms` timestamps in the packets, not with your
Python process's clock: a busy event loop delays callbacks without losing data.

**Name or TX power change not visible**: both apply only to advertising, which
happens after you disconnect.

**`n/a` for temperature/light right after connecting**: values become valid
about 250 ms after the connection. A read before that returns the
empty startup value.

## Serial log

`make log` opens `/dev/serial/by-id/usb-Silicon_Labs_J-Link_OB_*-if00` at
115200 8N1; quit with **Ctrl+]**. If it says permission denied, add yourself to
`dialout` (`sudo usermod -aG dialout $USER`, then log in again). Opening the
port does not reset the board.
