/***************************************************************************//**
 * @file
 * @brief BLE Sensor wire protocol: UUIDs, packet layouts, value limits.
 *
 * This is the contract between the firmware and any client. The Python
 * client mirrors it in host/ble_sensor/protocol.py; change both together and
 * bump BLE_SENSOR_PROTOCOL_VERSION. Every struct is sent as-is over the air:
 * packed, little-endian. Characteristic UUIDs are listed in
 * config/btconf/gatt_configuration.btconf.
 ******************************************************************************/

#ifndef BLE_PROTOCOL_H
#define BLE_PROTOCOL_H

#include <stdint.h>

#define BLE_SENSOR_PROTOCOL_VERSION   1

// Service a7e40000-5c2b-4f1a-9d3e-6b8c0f2e1d47, byte-reversed as it goes on air.
#define BLE_SENSOR_SERVICE_UUID_BYTES               \
  { 0x47, 0x1d, 0x2e, 0x0f, 0x8c, 0x6b, 0x3e, 0x9d, \
    0x1a, 0x4f, 0x2b, 0x5c, 0x00, 0x00, 0xe4, 0xa7 }

// Board revision, reported in the Info characteristic and in advertising.
#define BOARD_ID_BRD4184A             0x0A
#define BOARD_ID_BRD4184B             0x0B
// Not this firmware: the ESP32 + ENS160/AHT21 air monitor (~/git/air_quality_sensor,
// firmware/ble.py) speaks the same protocol with this board id. It has Env, Info,
// Command, Name, Display and Air, and no Motion, Button, LED or Config.
#define BOARD_ID_ESP32_AIR            0x0C

// Sensor bits: Config.sensor_mask (which sensors to sample) and
// Info.available_mask (which sensors this board has).
#define SENSOR_BIT_RHT                (1u << 0) // Si7021 temperature + humidity
#define SENSOR_BIT_LIGHT              (1u << 1) // Si1133 (A) lux + UV / VEML6035 (B) lux
#define SENSOR_BIT_HALL               (1u << 2) // Si7210 magnetic field
#define SENSOR_BIT_IMU                (1u << 3) // ICM-20648 accelerometer + gyroscope
#define SENSOR_BIT_SOUND              (1u << 4) // PDM microphone (B only)
#define SENSOR_BIT_SUPPLY             (1u << 5) // supply voltage
#define SENSOR_BIT_ALL                0x3Fu     // this firmware's sensors
#define SENSOR_BIT_AIR                (1u << 6) // ENS160 air quality (ESP32 Air board only)

// Env.valid bits: which fields of an Env packet hold a fresh reading.
#define ENV_VALID_TEMPERATURE         (1u << 0)
#define ENV_VALID_HUMIDITY            (1u << 1)
#define ENV_VALID_LUX                 (1u << 2)
#define ENV_VALID_UV                  (1u << 3)
#define ENV_VALID_HALL                (1u << 4)
#define ENV_VALID_SOUND               (1u << 5)
#define ENV_VALID_SUPPLY              (1u << 6)
#define ENV_VALID_DIE_TEMPERATURE     (1u << 7)

// Env.hall_flags bits
#define HALL_FLAG_ALERT               (1u << 0) // |field| above the configured threshold
#define HALL_FLAG_TAMPER              (1u << 1) // |field| above the sensor's tamper level

typedef enum {
  LED_MODE_OFF   = 0,
  LED_MODE_ON    = 1,
  LED_MODE_BLINK = 2,
} led_mode_t;

typedef enum {
  COMMAND_CALIBRATE_GYRO     = 0x01, // the board must be still
  COMMAND_FACTORY_RESET      = 0x02, // default config and name
  COMMAND_REBOOT             = 0x03,
  COMMAND_IDENTIFY           = 0x04, // fast LED blink for a few seconds
  COMMAND_RESET_ORIENTATION  = 0x05, // zero roll/pitch/yaw
} command_t;

// Accepted Config values; a write outside them is rejected (ATT error 0x13).
#define LIMIT_ENV_PERIOD_MIN_MS       100
#define LIMIT_ENV_PERIOD_MAX_MS       60000
#define LIMIT_MOTION_PERIOD_MIN_MS    10
#define LIMIT_MOTION_PERIOD_MAX_MS    1000
#define LIMIT_TX_POWER_MIN_DBM_X10    (-300)
#define LIMIT_TX_POWER_MAX_DBM_X10    60      // EFR32BG22 maximum: +6 dBm
#define LIMIT_ADV_INTERVAL_MIN_MS     20
#define LIMIT_ADV_INTERVAL_MAX_MS     10240
#define LIMIT_HALL_THRESHOLD_MIN_UT   100
#define LIMIT_HALL_THRESHOLD_MAX_UT   20000
#define LIMIT_NAME_MAX_LEN            20      // bytes of UTF-8, no terminator

// Display.page_mask bits: which readings the OLED shows, in this order.
#define DISPLAY_PAGE_BIT_TEMPERATURE       (1u << 0)
#define DISPLAY_PAGE_BIT_HUMIDITY          (1u << 1)
#define DISPLAY_PAGE_BIT_LIGHT             (1u << 2)
#define DISPLAY_PAGE_BIT_UV                (1u << 3)
#define DISPLAY_PAGE_BIT_MAGNETIC          (1u << 4)
#define DISPLAY_PAGE_BIT_SOUND             (1u << 5)
#define DISPLAY_PAGE_BIT_SUPPLY            (1u << 6)
#define DISPLAY_PAGE_BIT_CHIP_TEMPERATURE  (1u << 7)
#define DISPLAY_PAGE_BIT_ORIENTATION       (1u << 8)
#define DISPLAY_PAGE_BIT_BUTTON            (1u << 9)
#define DISPLAY_PAGE_ALL                   0x03FFu  // this firmware's pages
// ESP32 Air board pages (with TEMPERATURE and HUMIDITY above)
#define DISPLAY_PAGE_BIT_AIR_QUALITY       (1u << 10)
#define DISPLAY_PAGE_BIT_ECO2              (1u << 11)
#define DISPLAY_PAGE_BIT_TVOC              (1u << 12)
#define DISPLAY_PAGE_BIT_DEW_POINT         (1u << 13)
#define DISPLAY_PAGE_BIT_SENSOR_DETAILS    (1u << 14)
#define DISPLAY_PAGE_BIT_SYSTEM            (1u << 15)

// Display.page_ms: how long each reading stays on screen. Below 1 s the fade
// between pages (~0.3 s) would take most of the time.
#define LIMIT_DISPLAY_PAGE_MIN_MS          1000
#define LIMIT_DISPLAY_PAGE_MAX_MS          60000

#pragma pack(push, 1)

// Environment (read, notify), sent every env period.
typedef struct {
  uint32_t uptime_ms;
  uint16_t valid;                 // ENV_VALID_* bits
  int16_t  temperature_c_x100;    // °C × 100
  uint16_t humidity_pct_x100;     // %RH × 100
  uint32_t lux_x100;              // lux × 100
  uint16_t uv_index_x100;         // UV index × 100 (BRD4184A only)
  int32_t  hall_ut;               // magnetic field, µT, signed
  uint8_t  hall_flags;            // HALL_FLAG_* bits
  int16_t  sound_db_x100;         // dB × 100 (BRD4184B only)
  uint16_t supply_mv;             // supply voltage, mV
  int16_t  die_temp_c_x100;       // EFR32 internal temperature, °C × 100
} ble_sensor_env_t;

// Motion (read, notify), sent for every new IMU sample.
typedef struct {
  uint32_t uptime_ms;
  int16_t  accel_mg[3];           // X, Y, Z in milli-g
  int16_t  gyro_dps_x100[3];      // X, Y, Z in °/s × 100
  int16_t  orientation_deg_x100[3]; // roll, pitch, yaw in ° × 100
} ble_sensor_motion_t;

// Button (read, notify), sent on every debounced press and release.
typedef struct {
  uint8_t  pressed;               // 1 while held down
  uint32_t press_count;           // presses since boot
} ble_sensor_button_t;

// LED (read, write)
typedef struct {
  uint8_t  mode;                  // led_mode_t
  uint16_t on_ms;                 // blink on time
  uint16_t off_ms;                // blink off time
} ble_sensor_led_t;

// Config (read, write), stored in flash.
typedef struct {
  uint8_t  version;               // BLE_SENSOR_PROTOCOL_VERSION; ignored on write
  uint8_t  sensor_mask;           // SENSOR_BIT_* to sample
  uint16_t env_period_ms;
  uint16_t motion_period_ms;      // also sets the IMU output data rate
  int16_t  tx_power_dbm_x10;      // 0.1 dBm
  uint16_t adv_interval_ms;
  uint16_t hall_threshold_ut;     // HALL_FLAG_ALERT trip point
} ble_sensor_config_t;

// Info (read only)
typedef struct {
  uint8_t  protocol_version;      // BLE_SENSOR_PROTOCOL_VERSION
  uint8_t  board_id;              // BOARD_ID_*
  uint8_t  available_mask;        // SENSOR_BIT_* present on this board
  uint8_t  reserved;
} ble_sensor_info_t;

// Display (read, write), stored in flash. Optional OLED on the I2C bus.
typedef struct {
  uint8_t  present;               // 1 if a display was found at boot; ignored on write
  uint16_t page_mask;             // DISPLAY_PAGE_BIT_* to show
  uint16_t page_ms;               // time per reading, LIMIT_DISPLAY_PAGE_MIN/MAX_MS
} ble_sensor_display_t;

// Air (read, notify), characteristic a7e4000a: ESP32 Air board only.
typedef struct {
  uint32_t uptime_ms;
  uint8_t  state;                 // ENS160 validity: 0 normal, 1 warm-up, 2 start-up, 3 invalid; 0xFF no sensor
  uint8_t  aqi;                   // 1-5 (UBA), 0 unless state is normal
  uint16_t eco2_ppm;              // 0 unless state is normal
  uint16_t tvoc_ppb;              // 0 unless state is normal
  // Details (later addition; a client may read just the 10 bytes above)
  uint8_t  status;                // raw ENS160 DEVICE_STATUS
  uint8_t  firmware[3];           // ENS160 firmware: major, minor, release
  uint16_t r1_raw;                // raw resistance, sensor element 1: ohms = 2^(raw/2048)
  uint16_t r4_raw;                // raw resistance, sensor element 4
  int16_t  compensation_c_x100;   // temperature the ENS160 uses, 0x7FFF = none yet
  uint16_t compensation_pct_x100; // humidity the ENS160 uses
} ble_sensor_air_t;

// System (read, notify), characteristic a7e4000b: ESP32 Air board only.
typedef struct {
  uint32_t uptime_s;
  uint32_t free_ram;
  int16_t  chip_temp_c_x100;      // ESP32 die temperature (uncalibrated), 0x7FFF = n/a
  int8_t   wifi_rssi;             // dBm, 0 = not connected
  uint8_t  flags;                 // bit 0 Wi-Fi, bit 1 MQTT, bit 2 Bluetooth connected
  uint8_t  ip[4];
  uint16_t cpu_mhz;
  uint8_t  reset_cause;           // 1 power on, 2 reset pin, 3 watchdog, 4 deep sleep, 5 software
  uint8_t  micropython[3];        // major, minor, patch
  uint16_t sensor_errors;
  uint16_t integrity_errors;      // ENS160 checksum mismatches
  uint32_t humid_s;               // seconds above 80 %RH since start
  uint16_t reserved;
} ble_sensor_system_t;

#pragma pack(pop)

// Sizes must match the value lengths in gatt_configuration.btconf.
_Static_assert(sizeof(ble_sensor_env_t) == 27, "Env must be 27 bytes");
_Static_assert(sizeof(ble_sensor_motion_t) == 22, "Motion must be 22 bytes");
_Static_assert(sizeof(ble_sensor_button_t) == 5, "Button must be 5 bytes");
_Static_assert(sizeof(ble_sensor_led_t) == 5, "LED must be 5 bytes");
_Static_assert(sizeof(ble_sensor_config_t) == 12, "Config must be 12 bytes");
_Static_assert(sizeof(ble_sensor_info_t) == 4, "Info must be 4 bytes");
_Static_assert(sizeof(ble_sensor_display_t) == 5, "Display must be 5 bytes");
_Static_assert(sizeof(ble_sensor_air_t) == 22, "Air must be 22 bytes");
_Static_assert(sizeof(ble_sensor_system_t) == 32, "System must be 32 bytes");

#endif // BLE_PROTOCOL_H
