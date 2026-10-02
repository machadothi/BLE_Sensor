/***************************************************************************//**
 * @file
 * @brief Binary layout of the TB Game GATT characteristics.
 *
 * Every struct here is sent over the air as-is (packed, little-endian).
 * host/tb_game.py decodes the same layouts; keep both in sync.
 ******************************************************************************/

#ifndef TB_PROTOCOL_H
#define TB_PROTOCOL_H

#include <stdint.h>

#define TB_PROTOCOL_VERSION     1

// Board revision reported in tb_info_t.board
#define TB_BOARD_BRD4184A       0x0A
#define TB_BOARD_BRD4184B       0x0B

// Sensor bits, used by tb_config_t.sensor_mask and tb_info_t.available_mask
#define TB_SENSOR_RHT           (1u << 0) // Si7021 temperature + humidity
#define TB_SENSOR_LIGHT         (1u << 1) // Si1133 (A) lux + UV / VEML6035 (B) lux
#define TB_SENSOR_HALL          (1u << 2) // Si7210 magnetic field
#define TB_SENSOR_IMU           (1u << 3) // ICM-20648 accel + gyro
#define TB_SENSOR_SOUND         (1u << 4) // PDM microphone (B only)
#define TB_SENSOR_BATTERY       (1u << 5) // supply voltage
#define TB_SENSOR_ALL           0x3Fu

// Valid bits in tb_env_t.valid: which fields hold a fresh reading
#define TB_ENV_TEMPERATURE      (1u << 0)
#define TB_ENV_HUMIDITY         (1u << 1)
#define TB_ENV_LUX              (1u << 2)
#define TB_ENV_UV               (1u << 3)
#define TB_ENV_HALL             (1u << 4)
#define TB_ENV_SOUND            (1u << 5)
#define TB_ENV_BATTERY          (1u << 6)
#define TB_ENV_DIE_TEMPERATURE  (1u << 7)

// tb_env_t.hall_flags
#define TB_HALL_ALERT           (1u << 0) // |field| above the configured threshold
#define TB_HALL_TAMPER          (1u << 1) // |field| above the sensor's tamper level

typedef enum {
  TB_LED_OFF   = 0,
  TB_LED_ON    = 1,
  TB_LED_BLINK = 2,
} tb_led_mode_t;

typedef enum {
  TB_CMD_CALIBRATE_GYRO = 0x01, // keep the board still while this runs
  TB_CMD_FACTORY_RESET  = 0x02, // restore default config and device name
  TB_CMD_REBOOT         = 0x03,
  TB_CMD_IDENTIFY       = 0x04, // blink the LED fast for 3 s
  TB_CMD_RESET_ORIENT   = 0x05, // zero the fused orientation angles
} tb_command_t;

// Config limits; writes outside these ranges are rejected with ATT error 0x13
#define TB_ENV_PERIOD_MIN_MS        100
#define TB_ENV_PERIOD_MAX_MS        60000
#define TB_MOTION_PERIOD_MIN_MS     10
#define TB_MOTION_PERIOD_MAX_MS     1000
#define TB_TX_POWER_MIN_DBM_X10     (-300)
#define TB_TX_POWER_MAX_DBM_X10     60
#define TB_ADV_INTERVAL_MIN_MS      20
#define TB_ADV_INTERVAL_MAX_MS      10240
#define TB_HALL_THRESHOLD_MIN_UT    100
#define TB_HALL_THRESHOLD_MAX_UT    20000

#pragma pack(push, 1)

// Environment characteristic (read / notify), sent every env_period_ms
typedef struct {
  uint32_t uptime_ms;
  uint16_t valid;              // TB_ENV_* bits
  int16_t  temperature_cx100;  // 0.01 degC
  uint16_t humidity_x100;      // 0.01 %RH
  uint32_t lux_x100;           // 0.01 lux
  uint16_t uv_index_x100;      // 0.01 UV index (BRD4184A only)
  int32_t  hall_ut;            // magnetic field, microtesla (signed)
  uint8_t  hall_flags;         // TB_HALL_* bits
  int16_t  sound_db_x100;      // 0.01 dB SPL (BRD4184B only)
  uint16_t battery_mv;         // supply voltage, millivolts
  int16_t  die_temp_cx100;     // EFR32 internal temperature, 0.01 degC
} tb_env_t;

// Motion characteristic (read / notify), sent every motion_period_ms
typedef struct {
  uint32_t uptime_ms;
  int16_t  accel_mg[3];        // milli-g
  int16_t  gyro_cdps[3];       // 0.01 deg/s
  int16_t  orientation_cdeg[3];// 0.01 deg, fused (roll, pitch, yaw)
} tb_motion_t;

// Button characteristic (read / notify), sent on every edge
typedef struct {
  uint8_t  pressed;            // 1 while held down
  uint32_t press_count;        // presses since boot
} tb_button_t;

// LED characteristic (read / write)
typedef struct {
  uint8_t  mode;               // tb_led_mode_t
  uint16_t on_ms;              // blink on time  (>= 10)
  uint16_t off_ms;             // blink off time (>= 10)
} tb_led_t;

// Config characteristic (read / write), persisted in NVM3
typedef struct {
  uint8_t  version;            // TB_PROTOCOL_VERSION; ignored on write
  uint8_t  sensor_mask;        // TB_SENSOR_* bits to sample
  uint16_t env_period_ms;
  uint16_t motion_period_ms;   // IMU output data rate follows this
  int16_t  tx_power_dbm_x10;   // radio TX power, 0.1 dBm (BG22 max +6 dBm)
  uint16_t adv_interval_ms;
  uint16_t hall_threshold_ut;  // TB_HALL_ALERT trip point
} tb_config_t;

// Info characteristic (read only)
typedef struct {
  uint8_t  protocol_version;   // TB_PROTOCOL_VERSION
  uint8_t  board;              // TB_BOARD_*
  uint8_t  available_mask;     // TB_SENSOR_* bits present on this board
  uint8_t  reserved;
} tb_info_t;

#pragma pack(pop)

_Static_assert(sizeof(tb_env_t) == 27, "tb_env_t must match the GATT length");
_Static_assert(sizeof(tb_motion_t) == 22, "tb_motion_t must match the GATT length");
_Static_assert(sizeof(tb_button_t) == 5, "tb_button_t must match the GATT length");
_Static_assert(sizeof(tb_led_t) == 5, "tb_led_t must match the GATT length");
_Static_assert(sizeof(tb_config_t) == 12, "tb_config_t must match the GATT length");
_Static_assert(sizeof(tb_info_t) == 4, "tb_info_t must match the GATT length");

#endif // TB_PROTOCOL_H
