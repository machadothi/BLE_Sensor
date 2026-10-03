/***************************************************************************//**
 * @file
 * @brief Periodic sensor sampling while a client is connected.
 ******************************************************************************/

#include <stddef.h>
#include "app_timer.h"
#include "app_config.h"
#include "ble/gatt_service.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "sampling.h"

static app_timer_t env_timer;
static app_timer_t motion_timer;

// The IMU makes one sample per motion period; polling twice as often means
// timer jitter never makes us skip one. Only new samples are published.
static uint32_t motion_poll_ms(uint16_t motion_period_ms)
{
  uint32_t half = motion_period_ms / 2u;
  return half < MOTION_POLL_MIN_MS ? MOTION_POLL_MIN_MS : half;
}

static void sample_env(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  ble_sensor_env_t env;
  sensors_read_env(&env);
  gatt_service_publish_env(&env);
}

static void sample_motion(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  ble_sensor_motion_t motion;
  if (sensors_read_motion(&motion)) {
    gatt_service_publish_motion(&motion);
  }
}

static void start_env_timer(void)
{
  app_timer_start(&env_timer, settings_config()->env_period_ms, sample_env, NULL, true);
}

static void start_motion_timer(void)
{
  app_timer_start(&motion_timer, motion_poll_ms(settings_config()->motion_period_ms), sample_motion, NULL, true);
}

// The first env sample comes ENV_FIRST_SAMPLE_DELAY_MS after connecting, when
// the slowest sensor has finished its first conversion; then the period applies.
static void sample_first_env(app_timer_t *timer, void *data)
{
  sample_env(timer, data);
  start_env_timer();
}

void sampling_start(void)
{
  sensors_set_enabled(settings_config()->sensor_mask);
  app_timer_start(&env_timer, ENV_FIRST_SAMPLE_DELAY_MS, sample_first_env, NULL, false);
  start_motion_timer();
}

void sampling_stop(void)
{
  app_timer_stop(&env_timer);
  app_timer_stop(&motion_timer);
  sensors_set_enabled(0);
}

void sampling_update_periods(const ble_sensor_config_t *previous)
{
  const ble_sensor_config_t *config = settings_config();

  if (previous == NULL || previous->env_period_ms != config->env_period_ms) {
    start_env_timer();
  }
  if (previous == NULL || previous->motion_period_ms != config->motion_period_ms) {
    start_motion_timer();
  }
}
