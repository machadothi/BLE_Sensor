# Setup: from a clean Linux machine to a working board

This takes you from nothing to a flashed Thunderboard that the Python client
can talk to. It was written and tested on **Ubuntu 22.04 (x86-64)** with a
**Thunderboard EFR32BG22, BRD4184A Rev. A02**.

Everything except one udev rule is installed **inside the repo folder**:
`tools/` (toolchain, git-ignored) and `.venv/` (Python, git-ignored). Nothing
else on the system changes, and deleting the folder removes everything.

- [Downloads at a glance](#downloads-at-a-glance)
- [1. What you need and why](#1-what-you-need-and-why)
- [2. Download the toolchain into tools/](#2-download-the-toolchain-into-tools)
- [3. Give your user access to the J-Link (sudo, once)](#3-give-your-user-access-to-the-j-link)
- [4. Python environment](#4-python-environment)
- [5. Identify the board and back up its flash](#5-identify-the-board-and-back-up-its-flash)
- [6. Build, flash, check](#6-build-flash-check)
- [7. Phone app: Android SDK](#7-phone-app-android-sdk)
- [8. Home Assistant](#8-home-assistant)
- [9. If a download no longer exists](#9-if-a-download-no-longer-exists)

---

## Downloads at a glance

Everything this project downloads, in one place. Sections below explain each
one and give the exact commands.

| What | For | From | Where it goes | Step |
|---|---|---|---|---|
| Simplicity SDK v2025.6.3 | firmware | <https://github.com/SiliconLabs/simplicity_sdk/releases/download/v2025.6.3/simplicity-sdk.zip> | `tools/simplicity_sdk` | [2](#2-download-the-toolchain-into-tools) |
| slc-cli | firmware | <https://www.silabs.com/documents/public/software/slc_cli_linux.zip> (use `wget`) | `tools/slc_cli` | [2](#2-download-the-toolchain-into-tools) |
| Simplicity Commander | flashing | <https://www.silabs.com/documents/public/software/SimplicityCommander-Linux.zip> (use `wget`) | `tools/commander-cli` | [2](#2-download-the-toolchain-into-tools) |
| Arm GNU Toolchain 12.2.Rel1 | firmware | <https://developer.arm.com/-/media/Files/downloads/gnu/12.2.rel1/binrel/arm-gnu-toolchain-12.2.rel1-x86_64-arm-none-eabi.tar.xz> | `tools/gcc` | [2](#2-download-the-toolchain-into-tools) |
| Temurin JDK 21 | slc + Android build | <https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse> | `tools/jdk` | [2](#2-download-the-toolchain-into-tools) |
| bleak, pyserial (PyPI) | Python client, `make log` | `pip install -e host` | `.venv` | [4](#4-python-environment) |
| Android SDK command-line tools 23.0 | phone app | <https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip> (newest: <https://developer.android.com/studio#command-line-tools-only>) | `~/Android/Sdk/cmdline-tools/latest` | [7](#7-phone-app-android-sdk) |
| Android platform 37.2, build-tools 37.0.0, platform-tools (adb) | phone app | `sdkmanager` (Google) | `~/Android/Sdk` | [7](#7-phone-app-android-sdk) |
| Gradle 9.8 | phone app | automatic: `./gradlew` fetches it from services.gradle.org | `~/.gradle/wrapper` | [7](#7-phone-app-android-sdk) |
| App libraries (Compose, Hilt, Nordic BLE, …) | phone app | automatic: Gradle fetches them from Google Maven and Maven Central | `~/.gradle/caches` | [7](#7-phone-app-android-sdk) |
| — | Home Assistant | nothing: BTHome is built into Home Assistant | — | [8](#8-home-assistant) |
| Pillow + DejaVu fonts (optional) | only to regenerate the OLED fonts/icons | `sudo apt install python3-pil fonts-dejavu-core` | system | [display.md](display.md#changing-the-look) |

## 1. What you need and why

| Tool | Version used | What it does here | Size unpacked |
|---|---|---|---|
| **Simplicity SDK** | v2025.6.3 | Silicon Labs' firmware SDK: the Bluetooth stack (a precompiled library), chip headers, drivers for every sensor on the board, board pin configs, and the source templates (including `main.c`). Our firmware is a thin app on top of it. | 1.8 GB |
| **slc-cli** | 5.11.1.0 | "Silicon Labs Configurator", the project generator. Reads our `.slcp` project file, pulls the needed SDK components, generates glue code (`main.c`, init calls, GATT database, linker script) and a Makefile. | 0.5 GB |
| **Temurin JDK** | 21 (≥ 17 works) | slc is a Java program. Used only to run slc; nothing on the board uses Java. | 0.35 GB |
| **Arm GNU Toolchain** (`arm-none-eabi-gcc`) | 12.2.Rel1 | Compiler and linker for the Cortex-M33 in the EFR32BG22. The SDK README states it supports exactly **12.2.Rel1**; Ubuntu 22.04's packaged `gcc-arm-none-eabi` is 10.3, older than that. | 1.1 GB |
| **Simplicity Commander** (CLI) | 1v25p0b1995 | Flashes the `.hex`, reads/erases flash, identifies the kit. It talks to the board's on-board **J-Link** debugger and bundles SEGGER's J-Link library, so no separate SEGGER install is needed. | 90 MB |
| **bleak** (Python) | 3.0.x | Cross-platform Bluetooth LE library used by the `host/ble_sensor` package. On Linux it talks to BlueZ over D-Bus. | small |
| **pyserial** (Python) | ≥ 3.5 | Only for `make log` (reading the board's UART log). | small |

Only for the phone app (see [7](#7-phone-app-android-sdk)):

| Tool | Version used | What it does here | Size |
|---|---|---|---|
| **Android SDK command-line tools** | 12.0 (23.0 current) | `sdkmanager`, which installs the rest of the Android SDK | 150 MB |
| **Android SDK Platform** | android-37.2 | The Android API the app compiles against (compileSdk 37) | 170 MB |
| **Android SDK Build-Tools** | 37.0.0 | `aapt2`, `d8`, `apksigner`: turn the code into a signed APK | 150 MB |
| **Android SDK Platform-Tools** | 37.0.1 | `adb`: installs the app on the phone over USB | 20 MB |
| **Gradle** + app libraries | 9.8 | Build tool and the app's dependencies; downloaded automatically on the first build | `~/.gradle`: several GB, shared by all Android projects (5.6 GB here) |

System prerequisites (Ubuntu packages, normally already present): `wget`,
`unzip`, `tar`, `bzip2`, `xz-utils`, `make`, `python3`, `python3-venv`,
`bluez` (the Linux Bluetooth daemon), and a Bluetooth adapter. Check the
adapter with `bluetoothctl list`.

## 2. Download the toolchain into tools/

> **Use `wget` for silabs.com.** silabs.com sits behind Akamai, which answers
> `curl` and Python's `urllib` with **403 Access Denied**; `wget` with a browser
> user agent is accepted. GitHub, developer.arm.com and adoptium.net work with
> anything.

```sh
cd ~/git/BLE_Sensor          # wherever the repo is
mkdir -p tools && cd tools
UA='Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0'

# Simplicity SDK (GitHub release asset, ~590 MB zip)
wget -O simplicity-sdk.zip \
  https://github.com/SiliconLabs/simplicity_sdk/releases/download/v2025.6.3/simplicity-sdk.zip
unzip -q simplicity-sdk.zip -d simplicity_sdk

# slc-cli (silabs.com; always serves the latest version)
wget -U "$UA" -O slc_cli.zip https://www.silabs.com/documents/public/software/slc_cli_linux.zip
unzip -q slc_cli.zip            # creates slc_cli/

# Simplicity Commander (silabs.com; latest version). The zip holds one tarball
# per platform; we only need the command-line x86-64 one.
wget -U "$UA" -O commander.zip https://www.silabs.com/documents/public/software/SimplicityCommander-Linux.zip
unzip -q commander.zip -d commander_pkg
tar xjf commander_pkg/SimplicityCommander-Linux/Commander-cli_linux_x86_64_*.tar.bz   # creates commander-cli/

# Arm GNU Toolchain 12.2.Rel1
wget -O gcc.tar.xz \
  https://developer.arm.com/-/media/Files/downloads/gnu/12.2.rel1/binrel/arm-gnu-toolchain-12.2.rel1-x86_64-arm-none-eabi.tar.xz
mkdir -p gcc && tar xJf gcc.tar.xz -C gcc --strip-components=1

# Temurin JDK 21 (latest 21.x)
wget -O jdk.tar.gz 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse'
mkdir -p jdk && tar xzf jdk.tar.gz -C jdk --strip-components=1

# Keep the archives somewhere safe (see section 7), then delete them here
rm -rf commander_pkg *.zip *.tar.*
```

Expected result:

```
tools/
├── commander-cli/commander-cli   (+ 99-jlink.rules, used in step 3)
├── gcc/bin/arm-none-eabi-gcc
├── jdk/bin/java
├── simplicity_sdk/               (simplicity_sdk.slcs, platform/, protocol/, hardware/ ...)
└── slc_cli/slc
```

Quick check:

```sh
tools/gcc/bin/arm-none-eabi-gcc --version | head -1     # 12.2.1 20221205
tools/jdk/bin/java -version 2>&1 | head -1              # openjdk version "21..."
tools/commander-cli/commander-cli --version | head -3   # Simplicity Commander 1v25...
```

You do **not** need to run `slc configuration`. `firmware/Makefile` passes the
SDK path (`--sdk ../tools/simplicity_sdk`) and the compiler path on every call,
and runs the one-time `slc signature trust` itself. Each checkout therefore
builds with its own `tools/` folder. (slc also keeps a per-user default in
`~/.uc/cli/cli.config`; the Makefile overrides it.)

The silabs.com links always serve the **latest** slc and Commander. Newer
versions have been backward compatible so far; the versions above are the
ones this project was tested with.

## 3. Give your user access to the J-Link

The board's USB connector is an on-board SEGGER **J-Link OB** debugger. It
exposes two things:

- **J-Link debug interface** (USB `1366:0105`). Commander flashes through it.
  By default only root may open it.
- **Virtual COM port** `/dev/ttyACM0` (the `dialout` group can open it). The
  firmware prints its log there at 115200 8N1.

Install SEGGER's udev rule (shipped inside Commander):

```sh
cd ~/git/BLE_Sensor
sudo sh setup_udev.sh
```

Then **unplug the board and plug it back in**. The rule only triggers on a USB
*add* event; `udevadm trigger` alone is not enough.

Also make sure your user is in `dialout` (`id -nG`). If not, run
`sudo usermod -aG dialout $USER` and log in again.

## 4. Python environment

```sh
cd ~/git/BLE_Sensor
python3 -m venv .venv
.venv/bin/pip install -e host     # installs bleak, pyserial and the ble-sensor command
export PATH="$PWD/.venv/bin:$PATH"   # or put this line in ~/.zshrc
```

## 5. Identify the board and back up its flash

```sh
cd ~/git/BLE_Sensor
tools/commander-cli/commander-cli adapter probe
```

Look for `Part Number : BRD4184A` (or `BRD4184B`) under *Board List*. That is
the `BOARD=` value for the Makefile. The two revisions carry different light
sensors and only B has a microphone (see [capabilities.md](capabilities.md)).

New boards ship with a factory bootloader and the Thunderboard demo. Our
firmware is linked at address 0 and overwrites the bootloader. Save the whole
512 KB flash first so you can always go back:

```sh
mkdir -p backup
tools/commander-cli/commander-cli readmem --device EFR32BG22C224F512IM40 \
  --range 0x0:0x80000 --outfile backup/brd4184a_factory_flash.hex
```

Restore later with:

```sh
tools/commander-cli/commander-cli flash backup/brd4184a_factory_flash.hex --device EFR32BG22C224F512IM40
```

## 6. Build, flash, check

```sh
cd ~/git/BLE_Sensor/firmware
make flash                  # BOARD=brd4184a by default; first build < 1 min
make log                    # firmware log; quit with Ctrl+]
```

While `make log` runs, reset the board from a second terminal with
`tools/commander-cli/commander-cli device reset --device EFR32BG22C224F512IM40`.
You should see:

```
[I] BLE Sensor firmware 1.0.0, protocol v1
[I] Sensors available: 0x2F
[I] Advertising as "BLE-Sensor-B0DF"
```

`0x2F` = RHT + light + hall + IMU + supply (bit meanings in
[capabilities.md](capabilities.md#sensor-bits)). Then, from another terminal:

```sh
ble-sensor scan    # 58:8E:81:66:B0:DF  -70 dBm  BRD4184A  BLE-Sensor-B0DF
ble-sensor read
```

All Makefile targets:

| Target | What it does |
|---|---|
| `make` / `make all` | generate (if needed) + compile → `build/<board>/build/debug/ble_sensor_<board>.hex` |
| `make generate` | only run slc |
| `make flash` | build + flash via Commander |
| `make erase` | mass-erase the chip (wipes config **and** any bootloader) |
| `make log` | UART log via pyserial miniterm |
| `make clean` | delete `build/` |
| `BOARD=brd4184b` | build for the other revision |
| `JLINK_SN=440174227` | choose a J-Link when several are plugged in |

## 7. Phone app: Android SDK

Only needed for the phone app in `android/`. The app's own guide is
[android-app.md](android-app.md).

The Android SDK lives outside the repo, in `~/Android/Sdk` (Android Studio
uses the same place). It isn't needed for the firmware or the Python client.

```sh
# 1. Command-line tools (contain sdkmanager). Newest link:
#    https://developer.android.com/studio#command-line-tools-only
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
wget -O tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip

# 2. The SDK parts the app needs (sdkmanager needs a JDK; reuse the project's)
export JAVA_HOME=~/git/BLE_Sensor/tools/jdk
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
    "platforms;android-37.2" "build-tools;37.0.0" "platform-tools"

# 3. Tell Gradle where the SDK is (file is git-ignored)
echo "sdk.dir=$HOME/Android/Sdk" > ~/git/BLE_Sensor/android/local.properties

# 4. Build: the first run downloads Gradle 9.8 and every library (a few minutes)
cd ~/git/BLE_Sensor/android && ./gradlew assembleDebug
```

To install on a phone you also need USB access for `adb`:
`sudo sh android/setup_adb_udev.sh`, then replug the phone. It covers Google
Pixel phones (vendor 18d1). For another brand, change `idVendor` in the
script; `lsusb` shows it. Details: [android-app.md](android-app.md#build-and-install).

## 8. Home Assistant

Nothing to download or install. The firmware broadcasts in the BTHome format,
which Home Assistant reads with its built-in Bluetooth and BTHome integrations.
See [home-assistant.md](home-assistant.md).

## 9. If a download no longer exists

Vendors move, rename or gate downloads. Protect yourself once:

1. **Archive the toolchain outside git.** `tools/` is about 3.7 GB and
   ~59,000 files, far too big for a git repo (GitHub rejects files over
   100 MB and the history would keep it forever). Pack it once and store it
   where it will last (NAS, cloud drive, or a private GitHub Release asset,
   2 GB max per file, so split if needed):

   ```sh
   tar cJf thunderboard-tools-2025.6.3.tar.xz -C ~/git/BLE_Sensor tools
   sha256sum thunderboard-tools-2025.6.3.tar.xz > thunderboard-tools-2025.6.3.tar.xz.sha256
   ```

   To restore: `tar xJf thunderboard-tools-2025.6.3.tar.xz -C ~/git/BLE_Sensor`.
2. **Keep the built `.hex` files** (≈0.6 MB each, e.g. as release assets). With
   them you can flash a known-good firmware even with no compiler at all.
3. **Fallback sources**, if you have neither the archive nor the URLs:
   - SDK: the GitHub repo `SiliconLabs/simplicity_sdk` (tags, needs `git lfs`).
     Silicon Labs also mirrors SDKs inside Simplicity Studio.
   - GCC: any Arm GNU Toolchain 12.2.Rel1 build, e.g. from xPack
     (`xpack-dev-tools/arm-none-eabi-gcc-xpack` on GitHub).
   - JDK: any 64-bit Java ≥ 17 (Ubuntu `openjdk-17-jre-headless` works).
   - Flashing without Commander: SEGGER's J-Link Software (`JLinkExe -device
     EFR32BG22C224F512IM40 -if SWD -speed 4000`, then `loadfile
     ble_sensor_brd4184a.hex`, `r`, `g`).
   - slc and Commander exist only on silabs.com and inside Simplicity Studio,
     which is why archiving them matters most.
   - Android: Google keeps old SDK packages online for years. To be safe,
     archive `~/Android/Sdk` and `~/.gradle` together with `tools/`; with them a
     fresh machine can build the app offline (`./gradlew --offline assembleDebug`).
