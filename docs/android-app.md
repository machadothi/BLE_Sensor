# Android app (`android/`)

A phone app that reads and configures everything the board exposes. It uses
the same stack as `~/git/app_playground` (Jetpack Compose, Material 3, Hilt,
MVVM with ViewModels + a repository, type-safe Navigation), moved to the
latest stable versions. Tested on a Pixel 10 Pro (GrapheneOS) with the
BRD4184A board.

- [What it does](#what-it-does)
- [Build and install](#build-and-install)
- [Code map](#code-map)
- [Adding something to the app](#adding-something-to-the-app)

---

## What it does

| Screen | Shows / does |
|---|---|
| Permissions | Asks for **Nearby devices** (Android 12+) or Location (Android 8–11, required there for BLE scans). The app never uses location and has no internet permission. |
| Scan | Radar with a rotating sweep; each board is a blip (closer to the center = stronger signal) and a row with name, revision (BRD4184A/B, from the advertisement), address and signal bars. Scans for 20 s, because Android throttles nonstop scanning. |
| Live | A card per sensor with animated numbers and a sparkline: temperature, humidity, light, UV, magnetic field (border pulses on alert/tamper), sound (B), supply, chip temperature. A button card counts presses and sends a ripple on every press. |
| Motion | A 3D drawing of the board that tilts with roll/pitch/yaw, live accelerometer and gyro bars, **Calibrate gyro** and **Zero angles**. |
| Charts | Scrolling history for temperature, humidity, light, magnetic field, \|acceleration\| and \|rotation rate\| (last 240 env / 400 motion samples). |
| Control | LED off/on/blink with on/off-time sliders and an orb that blinks in the same rhythm; Identify, Reboot, Factory reset (with confirmation). |
| Settings | Name; per-sensor switches (sensors the board lacks are greyed out); environment period, motion rate, magnet threshold, TX power, advertising interval. Edits stay a draft until **Apply**; a bar slides up showing how many changes are pending. Out-of-range values can't be entered, because the sliders are bounded by the protocol limits. |

The top bar shows the board's name, revision and live signal strength. If
the link drops, a screen offers **Reconnect**. Leaving the board screen
disconnects, and the board starts advertising again.

## Build and install

Requirements, all already on this machine:

| What | Where | Notes |
|---|---|---|
| JDK 17+ | `tools/jdk` (the one slc uses) | `export JAVA_HOME=~/git/BLE_Sensor/tools/jdk` |
| Android SDK | `~/Android/Sdk` | platform **android-37.2**, build-tools 37, platform-tools. Install with `cmdline-tools/latest/bin/sdkmanager "platforms;android-37.2" "build-tools;37.0.0" "platform-tools"`. |
| Gradle 9.8 | downloaded by `./gradlew` on first run | |

`android/local.properties` (git-ignored) holds `sdk.dir=/home/<you>/Android/Sdk`.

```sh
cd ~/git/BLE_Sensor/android
export JAVA_HOME=~/git/BLE_Sensor/tools/jdk
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # protocol tests (decode = Python client, byte for byte)
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First time with a phone on Linux:

1. On the phone, open Settings → About phone and tap **Build number** 7×. Then
   in Developer options, turn on **USB debugging**.
2. `sudo sh android/setup_adb_udev.sh`, then unplug and replug the phone. Like
   the J-Link rule, it only applies on a USB "add" event.
3. Accept **Allow USB debugging?** on the phone, and tick "Always allow".
4. `adb devices` should list the phone as `device`; `unauthorized` means step 3
   is still pending.

Versions (in `android/gradle/libs.versions.toml`): AGP 9.4.1 (compiles Kotlin
itself), Kotlin 2.4.20, Gradle 9.8, KSP 2.3.12, Hilt 2.60.1, Compose BOM
2026.09.00, Nordic Android-BLE-Library 2.11.0. compileSdk/targetSdk 37,
minSdk 26.

## Code map

`android/app/src/main/java/com/machadothi/blesensor/`:

| Path | Role |
|---|---|
| `ble/Protocol.kt` | UUIDs, limits, and decode/encode of every characteristic. Mirrors `firmware/src/ble/ble_protocol.h` and `host/ble_sensor/protocol.py`. |
| `ble/BoardScanner.kt` | Platform BLE scan filtered on the service UUID; reads the board revision from the manufacturer data |
| `ble/BoardConnection.kt` | One GATT connection via Nordic's `BleManager`: it queues GATT operations (Android allows one at a time), requests MTU 247 and a high-priority link, and exposes notifications as flows |
| `repository/BoardRepository*.kt` | The single source of truth the UI observes: connection status, every characteristic as a `StateFlow`, chart history (published ~15×/s so 100 Hz data doesn't redraw charts 100×/s), RSSI polling, write helpers |
| `di/BleModule.kt` | Hilt binding, same pattern as app_playground |
| `ui/navigation/` | Routes (Permissions → Scan → Board) with slide/fade transitions |
| `ui/screen/scan/` | Radar + list, `ScanViewModel` |
| `ui/screen/board/` | `BoardScreen` (top bar, tabs, connecting/lost states) and `BoardViewModel`, shared by all tabs; it turns write errors into snackbar messages (ATT 0x13 → "The board rejected that value") |
| `ui/screen/board/{live,motion,charts,control,settings}/` | The five tabs |
| `ui/components/` | `GlowCard`, `AnimatedNumber`, `Sparkline`, `SignalBars`, `LedOrb`, `LabeledSlider` (logarithmic option), dialogs |
| `ui/theme/` | Dark instrument palette (always used; a light scheme exists in `Theme.kt`, switch `darkTheme` to follow the phone) |
| `res/drawable/ic_launcher_*.xml` | Adaptive launcher icon: chip with signal arcs on a navy→teal gradient, plus a monochrome layer for themed icons |
| `app/src/test/.../ProtocolTest.kt` | Decoding tests with fixtures generated by the Python client |

## Adding something to the app

**A new characteristic or field.**
1. Add the UUID and data class (with `decode`/`encode`) in `ble/Protocol.kt`.
2. Add it to the list in `BoardConnection.isRequiredServiceSupported()`, plus
   `subscribe(...)` in `initialize()` if it notifies.
3. Add a `StateFlow` and a read or write in `BoardRepositoryImpl`, and expose it
   in `BoardViewModel`.
4. Show it in a tab.
5. Extend `ProtocolTest` with a fixture from `host/ble_sensor/protocol.py`.

**A new tab.** Add an entry to the `Tab` enum in `BoardScreen.kt` and a branch
in `TabContent`.

**Release build.** `./gradlew assembleRelease` needs a signing config; debug
builds are fine for your own phone.
