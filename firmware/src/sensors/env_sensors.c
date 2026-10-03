/***************************************************************************//**
 * @file
 * @brief Environmental sensors: temperature/humidity, light, hall, supply.
 *
 * The Si7021 and Si1133 need 17-210 ms per conversion. Instead of waiting,
 * each read collects the conversion started by the previous read (if it is
 * done) and starts the next one. Values are therefore up to one env period
 * old, but the main loop never stalls and motion sampling keeps its rate.
 ******************************************************************************/

#include <math.h>
#include "em_emu.h"
#include "sl_board_control.h"
#include "sl_component_catalog.h"
#include "sl_i2cspm_instances.h"
#include "sl_power_supply.h"
#include "sl_sensor_rht.h"
#include "sl_si70xx.h"
#include "sl_si7210.h"
#include "app_log.h"
#include "app_config.h"
#include "uptime.h"
#include "sensors/env_sensors.h"

#if defined(SL_CATALOG_SENSOR_LIGHT_PRESENT)
#include "sl_sensor_light.h"
#include "sl_si1133.h"
#elif defined(SL_CATALOG_SENSOR_LUX_PRESENT)
#include "sl_sensor_lux.h"
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT / SL_CATALOG_SENSOR_LUX_PRESENT

// A conversion that was started and is collected later.
typedef struct {
  bool started;        // a conversion is running, result not collected yet
  bool have_value;     // the cached reading below is valid
  uint32_t started_at_ms;
} conversion_t;

static bool conversion_is_done(const conversion_t *conv, uint32_t duration_ms)
{
  return conv->started && uptime_ms() - conv->started_at_ms >= duration_ms;
}

static void conversion_mark_started(conversion_t *conv)
{
  conv->started = true;
  conv->started_at_ms = uptime_ms();
}

// -----------------------------------------------------------------------------
// Temperature and humidity (Si7021)

static conversion_t rht;
static uint32_t humidity_milli_pct;
static int32_t temperature_milli_c;

static void rht_collect_and_restart(void)
{
  if (conversion_is_done(&rht, RHT_CONVERSION_MS)) {
    // Read into locals: a failed read must not clobber the cached reading.
    uint32_t humidity;
    int32_t temperature;
    if (sl_si70xx_read_rh_and_temp(sl_i2cspm_sensor, SI7021_ADDR, &humidity, &temperature) == SL_STATUS_OK) {
      humidity_milli_pct = humidity;
      temperature_milli_c = temperature;
      rht.have_value = true;
    }
    rht.started = false;
  }
  if (!rht.started
      && sl_si70xx_start_no_hold_measure_rh_and_temp(sl_i2cspm_sensor, SI7021_ADDR) == SL_STATUS_OK) {
    conversion_mark_started(&rht);
  }
}

static void rht_fill(ble_sensor_env_t *env)
{
  rht_collect_and_restart();
  if (rht.have_value) {
    env->temperature_c_x100 = (int16_t)(temperature_milli_c / 10);
    env->humidity_pct_x100 = (uint16_t)(humidity_milli_pct / 10);
    env->valid |= ENV_VALID_TEMPERATURE | ENV_VALID_HUMIDITY;
  }
}

// -----------------------------------------------------------------------------
// Light: Si1133 lux + UV on BRD4184A, VEML6035 lux on BRD4184B

#if defined(SL_CATALOG_SENSOR_LIGHT_PRESENT)

static conversion_t light;
static float light_lux;
static float light_uv_index;

static bool light_init(void)
{
  return sl_sensor_light_init() == SL_STATUS_OK;
}

static void light_collect_and_restart(void)
{
  if (conversion_is_done(&light, LIGHT_CONVERSION_MS)) {
    float lux, uv_index;
    if (sl_si1133_get_measurement(sl_i2cspm_sensor, &lux, &uv_index) == SL_STATUS_OK) {
      light_lux = lux;
      light_uv_index = uv_index;
      light.have_value = true;
    }
    light.started = false;
  }
  if (!light.started && sl_si1133_force_measurement(sl_i2cspm_sensor) == SL_STATUS_OK) {
    conversion_mark_started(&light);
  }
}

static void light_forget(void)
{
  light.have_value = false;
}

static void light_fill(ble_sensor_env_t *env)
{
  light_collect_and_restart();
  if (light.have_value) {
    env->lux_x100 = (uint32_t)(light_lux * 100.0f);
    env->uv_index_x100 = (uint16_t)(light_uv_index * 100.0f);
    env->valid |= ENV_VALID_LUX | ENV_VALID_UV;
  }
}

#elif defined(SL_CATALOG_SENSOR_LUX_PRESENT)

static bool light_init(void)
{
  return sl_sensor_lux_init() == SL_STATUS_OK;
}

// The VEML6035 measures continuously; reading it does not block.
static void light_collect_and_restart(void)
{
}

static void light_forget(void)
{
}

static void light_fill(ble_sensor_env_t *env)
{
  float lux;
  if (sl_sensor_lux_get(&lux) == SL_STATUS_OK) {
    env->lux_x100 = (uint32_t)(lux * 100.0f);
    env->valid |= ENV_VALID_LUX;
  }
}

#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT / SL_CATALOG_SENSOR_LUX_PRESENT

// -----------------------------------------------------------------------------
// Magnetic field (Si7210 hall sensor)

static float hall_threshold_mt = DEFAULT_HALL_THRESHOLD_UT / 1000.0f;
static bool hall_alert;
static bool hall_present;

static sl_status_t hall_configure(void)
{
  sl_si7210_configure_t config = {
    .threshold = hall_threshold_mt,
    .hysteresis = HALL_HYSTERESIS_MT,
    .polarity = 0,            // omnipolar: either magnet pole counts
    .output_invert = false,
  };
  return sl_si7210_configure(sl_i2cspm_sensor, &config);
}

static void hall_fill(ble_sensor_env_t *env)
{
  float field_mt;
  if (sl_si7210_measure(sl_i2cspm_sensor, HALL_RANGE_UT, &field_mt) != SL_STATUS_OK) {
    return;
  }

  // Hysteresis: the alert turns on above threshold + h and off below threshold - h.
  float magnitude_mt = fabsf(field_mt);
  if (magnitude_mt > hall_threshold_mt + HALL_HYSTERESIS_MT) {
    hall_alert = true;
  } else if (magnitude_mt < hall_threshold_mt - HALL_HYSTERESIS_MT) {
    hall_alert = false;
  }

  env->hall_ut = (int32_t)(field_mt * 1000.0f);
  env->hall_flags = 0;
  if (hall_alert) {
    env->hall_flags |= HALL_FLAG_ALERT;
  }
  if (magnitude_mt > sl_si7210_get_tamper_threshold()) {
    env->hall_flags |= HALL_FLAG_TAMPER;
  }
  env->valid |= ENV_VALID_HALL;
}

void env_sensors_set_hall_threshold(uint16_t threshold_ut)
{
  hall_threshold_mt = threshold_ut / 1000.0f;
  if (hall_present) {
    hall_configure();
  }
}

// -----------------------------------------------------------------------------
// Public interface

uint8_t env_sensors_init(void)
{
  uint8_t found = SENSOR_BIT_SUPPLY;   // the ADC is always there
  sl_power_supply_probe();

  if (sl_sensor_rht_init() == SL_STATUS_OK) {
    found |= SENSOR_BIT_RHT;
  } else {
    app_log_warning("Si7021 (temperature/humidity) not found" APP_LOG_NL);
  }

  if (light_init()) {
    found |= SENSOR_BIT_LIGHT;
  } else {
    app_log_warning("Light sensor not found" APP_LOG_NL);
  }

  sl_board_enable_sensor(SL_BOARD_SENSOR_HALL);
  hall_present = sl_si7210_init(sl_i2cspm_sensor) == SL_STATUS_OK && hall_configure() == SL_STATUS_OK;
  if (hall_present) {
    found |= SENSOR_BIT_HALL;
  } else {
    app_log_warning("Si7210 (hall) not found" APP_LOG_NL);
  }
  return found;
}

void env_sensors_start(uint8_t mask)
{
  if (mask & SENSOR_BIT_RHT) {
    rht_collect_and_restart();
  } else {
    rht.have_value = false;
  }

  if (mask & SENSOR_BIT_LIGHT) {
    light_collect_and_restart();
  } else {
    light_forget();
  }
}

void env_sensors_read(ble_sensor_env_t *env, uint8_t mask)
{
  if (mask & SENSOR_BIT_RHT) {
    rht_fill(env);
  }
  if (mask & SENSOR_BIT_LIGHT) {
    light_fill(env);
  }
  if (mask & SENSOR_BIT_HALL) {
    hall_fill(env);
  }
  if (mask & SENSOR_BIT_SUPPLY) {
    float supply_v = sl_power_supply_measure_voltage(SUPPLY_ADC_AVERAGE);
    env->supply_mv = (uint16_t)(supply_v * 1000.0f);
    env->valid |= ENV_VALID_SUPPLY;
  }

  // The chip's own temperature sensor is always available.
  env->die_temp_c_x100 = (int16_t)(EMU_TemperatureGet() * 100.0f);
  env->valid |= ENV_VALID_DIE_TEMPERATURE;
}
