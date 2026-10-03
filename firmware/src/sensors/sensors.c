/***************************************************************************//**
 * @file
 * @brief All on-board sensors behind one interface.
 ******************************************************************************/

#include <string.h>
#include "sl_component_catalog.h"
#include "app_log.h"
#include "uptime.h"
#include "sensors/env_sensors.h"
#include "sensors/imu.h"
#include "sensors/sound.h"
#include "sensors/sensors.h"

static uint8_t available_mask;
static uint8_t enabled_mask;

static bool turned_on(uint8_t bit, uint8_t before, uint8_t after)
{
  return (after & bit) && !(before & bit);
}

static bool turned_off(uint8_t bit, uint8_t before, uint8_t after)
{
  return !(after & bit) && (before & bit);
}

void sensors_init(void)
{
  available_mask = env_sensors_init();
  if (imu_probe()) {
    available_mask |= SENSOR_BIT_IMU;
  }
  if (sound_is_present()) {
    available_mask |= SENSOR_BIT_SOUND;
  }
  enabled_mask = 0;
  app_log_info("Sensors available: 0x%02X" APP_LOG_NL, available_mask);
}

uint8_t sensors_available(void)
{
  return available_mask;
}

uint8_t sensors_board_id(void)
{
#if defined(SL_CATALOG_SENSOR_LUX_PRESENT)
  return BOARD_ID_BRD4184B;   // the VEML6035 light sensor is only on B
#else // SL_CATALOG_SENSOR_LUX_PRESENT
  return BOARD_ID_BRD4184A;
#endif // SL_CATALOG_SENSOR_LUX_PRESENT
}

void sensors_set_enabled(uint8_t mask)
{
  uint8_t before = enabled_mask;
  enabled_mask = mask & available_mask;

  env_sensors_start(enabled_mask);

  if (turned_on(SENSOR_BIT_IMU, before, enabled_mask)) {
    imu_power_on();
  } else if (turned_off(SENSOR_BIT_IMU, before, enabled_mask)) {
    imu_power_off();
  }

  if (turned_on(SENSOR_BIT_SOUND, before, enabled_mask)) {
    sound_start();
  } else if (turned_off(SENSOR_BIT_SOUND, before, enabled_mask)) {
    sound_stop();
  }
}

void sensors_set_motion_period(uint16_t period_ms)
{
  imu_set_period(period_ms);
}

void sensors_set_hall_threshold(uint16_t threshold_ut)
{
  env_sensors_set_hall_threshold(threshold_ut);
}

void sensors_read_env(ble_sensor_env_t *env)
{
  memset(env, 0, sizeof(*env));
  env->uptime_ms = uptime_ms();

  env_sensors_read(env, enabled_mask);

  float level_db;
  if ((enabled_mask & SENSOR_BIT_SOUND) && sound_level_db(&level_db)) {
    env->sound_db_x100 = (int16_t)(level_db * 100.0f);
    env->valid |= ENV_VALID_SOUND;
  }
}

bool sensors_read_motion(ble_sensor_motion_t *motion)
{
  if (!(enabled_mask & SENSOR_BIT_IMU)) {
    return false;
  }
  return imu_read(motion);
}

sl_status_t sensors_calibrate_gyro(void)
{
  return imu_calibrate_gyro();
}

void sensors_reset_orientation(void)
{
  imu_reset_orientation();
}

void sensors_process(void)
{
  sound_process();
}
