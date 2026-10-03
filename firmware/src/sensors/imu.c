/***************************************************************************//**
 * @file
 * @brief ICM-20648 inertial sensor: accelerometer, gyroscope, fused orientation.
 ******************************************************************************/

#include "sl_board_control.h"
#include "sl_imu.h"
#include "sl_sleeptimer.h"
#include "app_log.h"
#include "app_config.h"
#include "uptime.h"
#include "sensors/imu.h"

static uint16_t sample_period_ms = DEFAULT_MOTION_PERIOD_MS;

bool imu_is_running(void)
{
  return sl_imu_get_state() == IMU_STATE_READY;
}

void imu_power_on(void)
{
  sl_board_enable_sensor(SL_BOARD_SENSOR_IMU);
  sl_sleeptimer_delay_millisecond(IMU_POWER_UP_MS);

  if (sl_imu_init() != SL_STATUS_OK) {
    app_log_warning("IMU init failed" APP_LOG_NL);
    sl_board_disable_sensor(SL_BOARD_SENSOR_IMU);
    return;
  }
  sl_imu_configure(1000.0f / (float)sample_period_ms);
}

void imu_power_off(void)
{
  if (sl_imu_get_state() != IMU_STATE_DISABLED) {
    sl_imu_deinit();
  }
  sl_board_disable_sensor(SL_BOARD_SENSOR_IMU);
}

bool imu_probe(void)
{
  imu_power_on();
  bool found = imu_is_running();
  imu_power_off();
  return found;
}

void imu_set_period(uint16_t period_ms)
{
  if (period_ms == sample_period_ms) {
    return;
  }
  sample_period_ms = period_ms;

  if (imu_is_running()) {
    imu_power_off();
    imu_power_on();
  }
}

bool imu_read(ble_sensor_motion_t *motion)
{
  if (!imu_is_running() || !sl_imu_is_data_ready()) {
    return false;
  }
  sl_imu_update();
  sl_imu_get_acceleration(motion->accel_mg);
  sl_imu_get_gyro(motion->gyro_dps_x100);
  sl_imu_get_orientation(motion->orientation_deg_x100);
  motion->uptime_ms = uptime_ms();
  return true;
}

sl_status_t imu_calibrate_gyro(void)
{
  if (!imu_is_running()) {
    return SL_STATUS_NOT_INITIALIZED;
  }
  return sl_imu_calibrate_gyro();
}

void imu_reset_orientation(void)
{
  if (imu_is_running()) {
    sl_imu_reset();
  }
}
