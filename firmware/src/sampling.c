/***************************************************************************//**
 * @file
 * @brief Periodic sensor sampling.
 ******************************************************************************/

#include <stddef.h>
#include "app_timer.h"
#include "app_config.h"
#include "display/display.h"
#include "home_assistant/bthome.h"
#include "ble/gatt_service.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "sampling.h"

// Sensors that only run while a client is connected. The IMU also runs
// without a connection when something else shows the orientation: Home
// Assistant (HOME_ASSISTANT_SEND_ORIENTATION) or the OLED display.
#if (HOME_ASSISTANT_ENABLED && HOME_ASSISTANT_SEND_ORIENTATION) || DISPLAY_ENABLED
#define CONNECTED_ONLY_SENSORS  (SENSOR_BIT_SOUND)
#define MOTION_ALWAYS_ON        true
#else // (HOME_ASSISTANT_ENABLED && HOME_ASSISTANT_SEND_ORIENTATION) || DISPLAY_ENABLED
#define CONNECTED_ONLY_SENSORS  (SENSOR_BIT_IMU | SENSOR_BIT_SOUND)
#define MOTION_ALWAYS_ON        false
#endif // (HOME_ASSISTANT_ENABLED && HOME_ASSISTANT_SEND_ORIENTATION) || DISPLAY_ENABLED

static app_timer_t env_timer;
static app_timer_t motion_timer;
static bool connected;

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
  bthome_publish_env(&env);
  display_publish_env(&env);
}

static void sample_motion(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  ble_sensor_motion_t motion;
  if (sensors_read_motion(&motion)) {
    gatt_service_publish_motion(&motion);
    bthome_publish_orientation(motion.orientation_deg_x100);
    display_publish_orientation(motion.orientation_deg_x100);
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

// The first env sample comes ENV_FIRST_SAMPLE_DELAY_MS after the sensors are
// enabled, when the slowest sensor has finished its first conversion.
static void sample_first_env(app_timer_t *timer, void *data)
{
  sample_env(timer, data);
  start_env_timer();
}

void sampling_apply_sensor_mask(void)
{
  uint8_t mask = settings_config()->sensor_mask;
  sensors_set_enabled(connected ? mask : (uint8_t)(mask & ~CONNECTED_ONLY_SENSORS));
}

void sampling_start(void)
{
  sampling_apply_sensor_mask();
  app_timer_start(&env_timer, ENV_FIRST_SAMPLE_DELAY_MS, sample_first_env, NULL, false);
  if (MOTION_ALWAYS_ON) {
    start_motion_timer();
  }
}

void sampling_set_connected(bool is_connected)
{
  connected = is_connected;
  sampling_apply_sensor_mask();
  if (connected) {
    start_motion_timer();
    // Give the new client a fresh env value right away.
    app_timer_start(&env_timer, ENV_FIRST_SAMPLE_DELAY_MS, sample_first_env, NULL, false);
  } else if (!MOTION_ALWAYS_ON) {
    app_timer_stop(&motion_timer);
  }
}

void sampling_update_periods(const ble_sensor_config_t *previous)
{
  const ble_sensor_config_t *config = settings_config();

  if (previous == NULL || previous->env_period_ms != config->env_period_ms) {
    start_env_timer();
  }
  if ((connected || MOTION_ALWAYS_ON) && (previous == NULL || previous->motion_period_ms != config->motion_period_ms)) {
    start_motion_timer();
  }
}
