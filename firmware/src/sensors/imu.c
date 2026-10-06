/***************************************************************************//**
 * @file
 * @brief ICM-20648 inertial sensor: accelerometer, gyroscope, fused orientation.
 *
 * The SDK's sl_imu driver talks to the chip, and its fusion helpers turn
 * accelerometer + gyro samples into roll/pitch/yaw. We run those helpers on
 * our own fusion state instead of calling sl_imu_update(), because the SDK
 * never removes the gyro's offset (its sl_imu_calibrate_gyro() only restarts
 * the chip). Gravity corrects roll and pitch, but nothing corrects yaw, so an
 * offset of a few °/s made yaw spin on a still board. Here the offset is
 * subtracted before fusion; see the gyro bias section in app_config.h.
 ******************************************************************************/

#include <math.h>
#include <string.h>
#include "sl_board_control.h"
#include "sl_imu.h"
#include "sl_sleeptimer.h"
#include "app_log.h"
#include "app_config.h"
#include "storage/settings.h"
#include "uptime.h"
#include "sensors/imu.h"

#define ICM20648_BASE_RATE_HZ   1125.0f

static uint16_t sample_period_ms = DEFAULT_MOTION_PERIOD_MS;
static sl_imu_sensor_fusion_t fusion;
static float gyro_bias_dps[3];

// -----------------------------------------------------------------------------
// Stillness detection: per-window mean and spread of the gyro and of |accel|

typedef struct {
  uint32_t count;
  float gyro_sum[3];
  float gyro_sum_sq[3];
  float accel_sum;
  float accel_sum_sq;
} window_t;

static window_t window;

static void window_add(window_t *w, const float gyro[3], float accel_magnitude)
{
  for (int i = 0; i < 3; i++) {
    w->gyro_sum[i] += gyro[i];
    w->gyro_sum_sq[i] += gyro[i] * gyro[i];
  }
  w->accel_sum += accel_magnitude;
  w->accel_sum_sq += accel_magnitude * accel_magnitude;
  w->count++;
}

static float stddev(float sum, float sum_sq, uint32_t n)
{
  float mean = sum / n;
  float variance = sum_sq / n - mean * mean;
  return variance > 0 ? sqrtf(variance) : 0.0f;
}

// True if nothing in the window moved more than sensor noise.
static bool window_is_still(const window_t *w)
{
  for (int i = 0; i < 3; i++) {
    if (stddev(w->gyro_sum[i], w->gyro_sum_sq[i], w->count) > GYRO_STILL_MAX_STDDEV_DPS) {
      return false;
    }
  }
  return stddev(w->accel_sum, w->accel_sum_sq, w->count) <= ACCEL_STILL_MAX_STDDEV_G;
}

static uint32_t samples_per(uint32_t duration_ms)
{
  uint32_t n = duration_ms / sample_period_ms;
  return n < 10 ? 10 : n;
}

// Feeds one raw gyro sample to the automatic offset learning.
static void learn_bias(const float gyro_raw[3], float accel_magnitude)
{
  window_add(&window, gyro_raw, accel_magnitude);
  if (window.count < samples_per(GYRO_STILL_WINDOW_MS)) {
    return;
  }
  if (window_is_still(&window)) {
    for (int i = 0; i < 3; i++) {
      float mean = window.gyro_sum[i] / window.count;
      gyro_bias_dps[i] += GYRO_BIAS_LEARN_WEIGHT * (mean - gyro_bias_dps[i]);
    }
  }
  memset(&window, 0, sizeof(window));
}

// -----------------------------------------------------------------------------
// Fusion

// The rate the chip really runs at: it divides 1125 Hz by an integer.
static float actual_rate_hz(void)
{
  float divider = floorf(ICM20648_BASE_RATE_HZ / (1000.0f / sample_period_ms) - 1.0f);
  if (divider < 0) {
    divider = 0;
  }
  if (divider > 255) {
    divider = 255;
  }
  return ICM20648_BASE_RATE_HZ / (divider + 1.0f);
}

static void fusion_start(void)
{
  float rate = actual_rate_hz();
  sl_imu_fuse_new(&fusion);
  sl_imu_fuse_accelerometer_set_sample_rate(&fusion, rate);
  sl_imu_fuse_gyro_set_sample_rate(&fusion, rate);
  sl_imu_fuse_reset(&fusion);
  memset(&window, 0, sizeof(window));
}

// Reads one sample (raw: g and °/s) from the chip, in the SDK fusion's axes.
static void read_raw(float accel_g[3], float gyro_dps[3])
{
  sl_imu_get_acceleration_raw_data(accel_g);
  sl_imu_get_gyro_raw_data(gyro_dps);
  gyro_dps[0] = -gyro_dps[0];   // same axis convention as sl_imu_fuse_update()
  gyro_dps[1] = -gyro_dps[1];
}

// -----------------------------------------------------------------------------
// Public interface

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
  fusion_start();

  // Start from the last calibration; still periods refine it from there.
  static bool bias_loaded;
  if (!bias_loaded) {
    bias_loaded = settings_load_gyro_bias(gyro_bias_dps);
  }
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
  float accel_g[3];
  float gyro_dps[3];

  if (!imu_is_running() || !sl_imu_is_data_ready()) {
    return false;
  }
  read_raw(accel_g, gyro_dps);

  float accel_magnitude = sqrtf(accel_g[0] * accel_g[0] + accel_g[1] * accel_g[1] + accel_g[2] * accel_g[2]);
  learn_bias(gyro_dps, accel_magnitude);
  for (int i = 0; i < 3; i++) {
    gyro_dps[i] -= gyro_bias_dps[i];
  }
  if (fabsf(gyro_dps[2]) < YAW_DEADBAND_DPS) {
    gyro_dps[2] = 0.0f;
  }

  // The SDK fusion steps, as in sl_imu_fuse_update(), with corrected gyro.
  memcpy(fusion.aVector, accel_g, sizeof(accel_g));
  memcpy(fusion.gVector, gyro_dps, sizeof(gyro_dps));
  sl_imu_fuse_gyro_update(&fusion, fusion.gVector);
  sl_imu_fuse_gyro_calculate_correction_vector(&fusion, true, false, 0);

  for (int i = 0; i < 3; i++) {
    motion->accel_mg[i] = (int16_t)(1000.0f * accel_g[i]);
    motion->gyro_dps_x100[i] = (int16_t)(100.0f * gyro_dps[i]);
    motion->orientation_deg_x100[i] = (int16_t)(100.0f * (float)IMU_RAD_TO_DEG_FACTOR * fusion.orientation[i]);
  }
  motion->uptime_ms = uptime_ms();
  return true;
}

sl_status_t imu_calibrate_gyro(void)
{
  window_t samples = { 0 };
  float accel_g[3];
  float gyro_dps[3];
  uint32_t wanted = samples_per(GYRO_CALIBRATION_MS);
  uint32_t deadline = uptime_ms() + 2 * GYRO_CALIBRATION_MS + 500;

  if (!imu_is_running()) {
    return SL_STATUS_NOT_INITIALIZED;
  }
  while (samples.count < wanted && (int32_t)(deadline - uptime_ms()) > 0) {
    if (sl_imu_is_data_ready()) {
      read_raw(accel_g, gyro_dps);
      window_add(&samples, gyro_dps, sqrtf(accel_g[0] * accel_g[0] + accel_g[1] * accel_g[1] + accel_g[2] * accel_g[2]));
    } else {
      sl_sleeptimer_delay_millisecond(1);
    }
  }
  if (samples.count < wanted / 2) {
    return SL_STATUS_TIMEOUT;
  }
  if (!window_is_still(&samples)) {
    app_log_warning("Gyro calibration: the board moved; result may be off" APP_LOG_NL);
  }

  for (int i = 0; i < 3; i++) {
    gyro_bias_dps[i] = samples.gyro_sum[i] / samples.count;
  }
  settings_save_gyro_bias(gyro_bias_dps);
  sl_imu_fuse_reset(&fusion);     // start from fresh angles with the new offset
  app_log_info("Gyro offset: %d, %d, %d (0.01 deg/s)" APP_LOG_NL,
               (int)(gyro_bias_dps[0] * 100), (int)(gyro_bias_dps[1] * 100), (int)(gyro_bias_dps[2] * 100));
  return SL_STATUS_OK;
}

void imu_reset_orientation(void)
{
  if (imu_is_running()) {
    sl_imu_fuse_reset(&fusion);
  }
}
