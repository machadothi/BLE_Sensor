/***************************************************************************//**
 * @file
 * @brief Project configuration: every tunable value of the firmware.
 *
 * Change behaviour here, not in the .c files. Each value carries its unit in
 * the name and a note on why it has its current value.
 *
 * What is NOT here, on purpose:
 *  - Wire-protocol facts (UUIDs, packet layouts, value limits, command codes)
 *    live in ble/ble_protocol.h, because the Python client must mirror them.
 *  - Fixed standards (ATT error codes, advertising AD types) live as named
 *    constants next to the only code that uses them.
 *  - SDK/board settings (pins, stack size, VCOM) live in the .slcp files
 *    under "configuration:".
 ******************************************************************************/

#ifndef APP_CONFIG_H
#define APP_CONFIG_H

#include "ble/ble_protocol.h"

// -----------------------------------------------------------------------------
// Firmware identity

// Printed on boot. The Device Information service has its own copy in
// config/btconf/gatt_configuration.btconf ("Firmware Revision String").
#define FIRMWARE_VERSION                "1.0.0"

// -----------------------------------------------------------------------------
// Factory defaults
// Used on first boot, when the stored config is invalid, and after a factory
// reset. Every default must lie within the limits in ble_protocol.h.

#define DEFAULT_SENSOR_MASK             SENSOR_BIT_ALL
#define DEFAULT_ENV_PERIOD_MS           1000    // 1 Hz environment updates
#define DEFAULT_MOTION_PERIOD_MS        50      // 20 Hz motion updates
#define DEFAULT_TX_POWER_DBM_X10        0       // 0 dBm: fine for a desk
#define DEFAULT_ADV_INTERVAL_MS         100     // quick discovery, moderate power
#define DEFAULT_HALL_THRESHOLD_UT       3000    // 3 mT: a small magnet a few cm away

// Default name is this prefix + the last two Bluetooth address bytes in hex,
// e.g. "BLE-Sensor-B0DF", so several boards can be told apart.
#define DEFAULT_NAME_PREFIX             "BLE-Sensor-"

// LED blink times used until a client writes the LED characteristic.
#define DEFAULT_LED_ON_MS               500
#define DEFAULT_LED_OFF_MS              500

// -----------------------------------------------------------------------------
// Timing

// First environment sample after a connection opens. Must exceed the slowest
// sensor conversion (LIGHT_CONVERSION_MS) so the first sample is complete.
#define ENV_FIRST_SAMPLE_DELAY_MS       250

// The IMU produces one sample per motion period. It is polled at half the
// period so timer jitter never skips a sample, but never faster than this.
#define MOTION_POLL_MIN_MS              5

// The button contacts bounce; an edge only counts after this much quiet.
#define BUTTON_DEBOUNCE_MS              25

// "Identify" command: fast blink for this long, then back to the LED mode.
#define IDENTIFY_DURATION_MS            3000
#define IDENTIFY_BLINK_MS               100

// Delay before rebooting, so the write response reaches the client first.
#define REBOOT_DELAY_MS                 200

// Shortest blink on/off time a client may request.
#define LED_BLINK_MIN_MS                10

// -----------------------------------------------------------------------------
// Bluetooth link

// Connection interval requested from the client, in units of 1.25 ms
// (6-12 = 7.5-15 ms). Short enough for 100 Hz motion notifications; the
// client has the final say.
#define CONN_INTERVAL_MIN               6
#define CONN_INTERVAL_MAX               12
#define CONN_PERIPHERAL_LATENCY         0
// Supervision timeout in units of 10 ms (2 s): how long a silent link
// survives before it is dropped.
#define CONN_SUPERVISION_TIMEOUT        200

// Bluetooth SIG company identifier in the advertised manufacturer data
// (0x02FF = Silicon Laboratories).
#define ADV_COMPANY_ID                  0x02FF

// -----------------------------------------------------------------------------
// Home Assistant
// The board broadcasts its readings in the BTHome format, which Home
// Assistant's BTHome integration picks up by itself (read-only, no pairing).
// Code: home_assistant/bthome.c. Guide: docs/home-assistant.md.

// 1 = broadcast for Home Assistant, 0 = compile the feature out.
#define HOME_ASSISTANT_ENABLED          1

// 1 = also send the X/Y/Z orientation angles (roll, pitch, yaw). This keeps
// the IMU powered all the time (fine on USB, drains a coin cell much faster).
#define HOME_ASSISTANT_SEND_ORIENTATION 1

// One packet can't hold everything, so the broadcast alternates an
// environment packet and an orientation packet every PACKET_SWITCH_MS.
// Within that time each packet is repeated every BROADCAST_INTERVAL_MS, so a
// receiver that misses one copy still gets another.
#define HOME_ASSISTANT_PACKET_SWITCH_MS       1000
#define HOME_ASSISTANT_BROADCAST_INTERVAL_MS  250

// -----------------------------------------------------------------------------
// Display
// Optional SSD1306 128x64 OLED on the board's I2C bus (EXP header pin 15 =
// SCL, 16 = SDA, 20 = 3.3 V, 1 = GND), found automatically at 0x3C or 0x3D.
// It shows one reading at a time. Code: display/. Guide: docs/display.md.

// 1 = drive the display (nothing happens if none is connected), 0 = compile out.
#define DISPLAY_ENABLED                 1

#define DEFAULT_DISPLAY_PAGE_MS         2000    // time per reading; the app can change it
#define DISPLAY_REFRESH_MS              500     // redraw rate of the shown value
#define DISPLAY_SPLASH_MS               1500    // startup screen
#define DISPLAY_CONTRAST                0xCF    // brightness, 0x00-0xFF

// Readings shown by default; the app can change this (stored in flash).
#define DEFAULT_DISPLAY_PAGES           DISPLAY_PAGE_ALL

// Hardware variants:
// many "SSD1306" modules are really SH1106 (132 columns): if the picture is
// shifted by 2 pixels or shows a stripe on one side, set the offset to 2.
#define DISPLAY_COLUMN_OFFSET           0
// 1 = turn the picture upside down (pins on the other side).
#define DISPLAY_ROTATE_180              0

// -----------------------------------------------------------------------------
// Sensors

// Si7210 hall sensor: measurement range and alert hysteresis.
#define HALL_RANGE_UT                   20000   // ±20 mT range (the other is ±200 mT)
#define HALL_HYSTERESIS_MT              0.5f

// Conversion times of the slow I2C sensors. A conversion is started on one
// env tick and collected on a later one once this much time has passed, so
// the main loop never waits for them (the SDK's blocking read of the Si1133
// takes ~210 ms and used to starve motion sampling).
#define RHT_CONVERSION_MS               25      // Si7021, measured ~17 ms
#define LIGHT_CONVERSION_MS             220     // Si1133, measured ~211 ms

// Gyro offset (bias) handling. The ICM-20648 has no magnetometer, so nothing
// corrects yaw: any gyro offset integrates into endless "rotation". The
// firmware removes the offset, learned two ways:
//  - the Calibrate command averages the gyro for GYRO_CALIBRATION_MS while
//    the board is still and stores the result in flash;
//  - whenever the board lies still for a GYRO_STILL_WINDOW_MS window (gyro and
//    accelerometer barely fluctuate), the offset is nudged toward that
//    window's average, so it keeps up with temperature changes.
#define GYRO_CALIBRATION_MS             1000
#define GYRO_STILL_WINDOW_MS            1000
#define GYRO_STILL_MAX_STDDEV_DPS       0.5f    // noise of a still ICM-20648 is ~0.1 °/s
#define ACCEL_STILL_MAX_STDDEV_G        0.02f
#define GYRO_BIAS_LEARN_WEIGHT          0.5f    // how far each still window moves the offset
// Below this, yaw rate counts as zero: hides the last bit of drift at rest.
#define YAW_DEADBAND_DPS                0.3f

// Settling time after switching on a sensor's power pin.
#define IMU_POWER_UP_MS                 50
#define MIC_POWER_UP_MS                 50

// PDM microphone (BRD4184B only).
#define MIC_SAMPLE_RATE_HZ              44100
#define MIC_BUFFER_FRAMES               1000    // ~23 ms of audio per level update
#define MIC_LEVEL_SMOOTHING             0.1f    // IIR weight of each new level

// ADC samples averaged per supply voltage reading.
#define SUPPLY_ADC_AVERAGE              4

// -----------------------------------------------------------------------------
// Storage (NVM3 object keys)
// Keys 0x40000-0x4FFFF belong to the Bluetooth stack; stay well below them.
// Changing a key makes the board forget the value stored under the old one.

#define NVM3_KEY_CONFIG                 0x01001
#define NVM3_KEY_NAME                   0x01002
#define NVM3_KEY_GYRO_BIAS              0x01003
#define NVM3_KEY_DISPLAY_PAGES          0x01004
#define NVM3_KEY_DISPLAY_PAGE_MS        0x01005

#endif // APP_CONFIG_H
