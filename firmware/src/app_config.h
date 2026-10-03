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

#endif // APP_CONFIG_H
