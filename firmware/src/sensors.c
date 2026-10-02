/***************************************************************************//**
 * @file
 * @brief Thunderboard BG22 sensor access (RHT, light, hall, IMU, sound, supply).
 *
 * On BRD4184A/B the RHT, light and hall sensors share one power-enable pin,
 * so they are powered once at boot and only gated in software. The IMU and
 * the microphone have their own enable pins and are really switched off.
 ******************************************************************************/

#include <math.h>
#include <string.h>
#include "em_emu.h"
#include "sl_board_control.h"
#include "sl_component_catalog.h"
#include "sl_i2cspm_instances.h"
#include "sl_sleeptimer.h"
#include "sl_power_supply.h"
#include "sl_sensor_rht.h"
#include "sl_si70xx.h"
#include "sl_si7210.h"
#include "sl_imu.h"
#include "app_log.h"
#include "sensors.h"
#ifdef SL_CATALOG_SENSOR_LIGHT_PRESENT
#include "sl_sensor_light.h"
#include "sl_si1133.h"
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT
#ifdef SL_CATALOG_SENSOR_LUX_PRESENT
#include "sl_sensor_lux.h"
#endif // SL_CATALOG_SENSOR_LUX_PRESENT
#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
#include "sl_mic.h"
#endif // SL_CATALOG_MIC_DRIVER_PRESENT

#define HALL_HYSTERESIS_MT      0.5f
#define HALL_SCALE_UT           20000   // Si7210 20 mT range
#define BATTERY_ADC_AVERAGE     4
#define IMU_POWER_UP_MS         50
#define MIC_POWER_UP_MS         50
#define MIC_SAMPLE_RATE         44100
#define MIC_BUFFER_FRAMES       1000
#define MIC_IIR_WEIGHT          0.1f
// Conversion times. The SDK's blocking reads sleep through these (Si1133:
// ~210 ms), which starves motion sampling, so conversions are started on one
// env tick and collected on a later one instead.
#define RHT_CONVERSION_MS       25
#define LIGHT_CONVERSION_MS     220

typedef struct {
  bool pending;        // conversion started, result not collected yet
  bool valid;          // value below holds a completed reading
  uint32_t start_ms;
} conversion_t;

static uint8_t available;
static uint8_t enabled;
static uint16_t motion_period_ms = 50;
static float hall_threshold_mt = 3.0f;
static bool hall_alert;

static conversion_t rht_conv;
static uint32_t rht_rh;
static int32_t rht_t;

#ifdef SL_CATALOG_SENSOR_LIGHT_PRESENT
static conversion_t light_conv;
static float light_lux;
static float light_uvi;
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
static int16_t mic_buffer[MIC_BUFFER_FRAMES];
static float sound_level_db;
static bool mic_running;
#endif // SL_CATALOG_MIC_DRIVER_PRESENT

static uint32_t uptime_ms(void)
{
  uint64_t ms = 0;
  sl_sleeptimer_tick64_to_ms(sl_sleeptimer_get_tick_count64(), &ms);
  return (uint32_t)ms;
}

// Collects a finished RHT conversion, then starts the next one.
static void rht_step(void)
{
  uint32_t now = uptime_ms();
  uint32_t rh;
  int32_t t;

  if (rht_conv.pending && now - rht_conv.start_ms >= RHT_CONVERSION_MS) {
    if (sl_si70xx_read_rh_and_temp(sl_i2cspm_sensor, SI7021_ADDR, &rh, &t) == SL_STATUS_OK) {
      rht_rh = rh;
      rht_t = t;
      rht_conv.valid = true;
    }
    rht_conv.pending = false;
  }
  if (!rht_conv.pending
      && sl_si70xx_start_no_hold_measure_rh_and_temp(sl_i2cspm_sensor, SI7021_ADDR) == SL_STATUS_OK) {
    rht_conv.pending = true;
    rht_conv.start_ms = now;
  }
}

#ifdef SL_CATALOG_SENSOR_LIGHT_PRESENT
// Collects a finished Si1133 conversion, then starts the next one.
static void light_step(void)
{
  uint32_t now = uptime_ms();
  float lux, uvi;

  if (light_conv.pending && now - light_conv.start_ms >= LIGHT_CONVERSION_MS) {
    if (sl_si1133_get_measurement(sl_i2cspm_sensor, &lux, &uvi) == SL_STATUS_OK) {
      light_lux = lux;
      light_uvi = uvi;
      light_conv.valid = true;
    }
    light_conv.pending = false;
  }
  if (!light_conv.pending && sl_si1133_force_measurement(sl_i2cspm_sensor) == SL_STATUS_OK) {
    light_conv.pending = true;
    light_conv.start_ms = now;
  }
}
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT

static sl_status_t hall_configure(void)
{
  sl_si7210_configure_t config = {
    .threshold = hall_threshold_mt,
    .hysteresis = HALL_HYSTERESIS_MT,
    .polarity = 0,            // omnipolar
    .output_invert = false,
  };
  return sl_si7210_configure(sl_i2cspm_sensor, &config);
}

static void imu_start(void)
{
  sl_board_enable_sensor(SL_BOARD_SENSOR_IMU);
  sl_sleeptimer_delay_millisecond(IMU_POWER_UP_MS);
  if (sl_imu_init() != SL_STATUS_OK) {
    app_log_warning("IMU init failed" APP_LOG_NL);
    sl_board_disable_sensor(SL_BOARD_SENSOR_IMU);
    return;
  }
  sl_imu_configure(1000.0f / (float)motion_period_ms);
}

static void imu_stop(void)
{
  if (sl_imu_get_state() != IMU_STATE_DISABLED) {
    sl_imu_deinit();
  }
  sl_board_disable_sensor(SL_BOARD_SENSOR_IMU);
}

static bool imu_running(void)
{
  return sl_imu_get_state() == IMU_STATE_READY;
}

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
static void mic_start(void)
{
  sl_board_enable_sensor(SL_BOARD_SENSOR_MICROPHONE);
  sl_sleeptimer_delay_millisecond(MIC_POWER_UP_MS);
  if (sl_mic_init(MIC_SAMPLE_RATE, 1) != SL_STATUS_OK) {
    app_log_warning("Microphone init failed" APP_LOG_NL);
    sl_board_disable_sensor(SL_BOARD_SENSOR_MICROPHONE);
    return;
  }
  sl_mic_get_n_samples(mic_buffer, MIC_BUFFER_FRAMES);
  mic_running = true;
}

static void mic_stop(void)
{
  if (mic_running) {
    sl_mic_deinit();
    mic_running = false;
  }
  sl_board_disable_sensor(SL_BOARD_SENSOR_MICROPHONE);
}
#endif // SL_CATALOG_MIC_DRIVER_PRESENT

void sensors_init(void)
{
  available = TB_SENSOR_BATTERY;
  sl_power_supply_probe();

  if (sl_sensor_rht_init() == SL_STATUS_OK) {
    available |= TB_SENSOR_RHT;
  } else {
    app_log_warning("Si7021 (RHT) not found" APP_LOG_NL);
  }

#if defined(SL_CATALOG_SENSOR_LIGHT_PRESENT)
  if (sl_sensor_light_init() == SL_STATUS_OK) {
    available |= TB_SENSOR_LIGHT;
  } else {
    app_log_warning("Si1133 (light/UV) not found" APP_LOG_NL);
  }
#elif defined(SL_CATALOG_SENSOR_LUX_PRESENT)
  if (sl_sensor_lux_init() == SL_STATUS_OK) {
    available |= TB_SENSOR_LIGHT;
  } else {
    app_log_warning("VEML6035 (light) not found" APP_LOG_NL);
  }
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT / SL_CATALOG_SENSOR_LUX_PRESENT

  sl_board_enable_sensor(SL_BOARD_SENSOR_HALL);
  if (sl_si7210_init(sl_i2cspm_sensor) == SL_STATUS_OK && hall_configure() == SL_STATUS_OK) {
    available |= TB_SENSOR_HALL;
  } else {
    app_log_warning("Si7210 (hall) not found" APP_LOG_NL);
  }

  // Probe the IMU once, then leave it to sensors_set_enabled().
  imu_start();
  if (imu_running()) {
    available |= TB_SENSOR_IMU;
  }
  imu_stop();

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
  available |= TB_SENSOR_SOUND;
#endif // SL_CATALOG_MIC_DRIVER_PRESENT

  enabled = 0;
  app_log_info("Sensors available: 0x%02X" APP_LOG_NL, available);
}

uint8_t sensors_available(void)
{
  return available;
}

void sensors_set_enabled(uint8_t mask)
{
  uint8_t previous = enabled;
  enabled = mask & available;

  // Start conversions right away so the first env read has fresh values;
  // forget old readings when a sensor is switched off.
  if (enabled & TB_SENSOR_RHT) {
    rht_step();
  } else {
    rht_conv.valid = false;
  }
#ifdef SL_CATALOG_SENSOR_LIGHT_PRESENT
  if (enabled & TB_SENSOR_LIGHT) {
    light_step();
  } else {
    light_conv.valid = false;
  }
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT

  if ((enabled & TB_SENSOR_IMU) && !(previous & TB_SENSOR_IMU)) {
    imu_start();
  } else if (!(enabled & TB_SENSOR_IMU) && (previous & TB_SENSOR_IMU)) {
    imu_stop();
  }

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
  if ((enabled & TB_SENSOR_SOUND) && !(previous & TB_SENSOR_SOUND)) {
    mic_start();
  } else if (!(enabled & TB_SENSOR_SOUND) && (previous & TB_SENSOR_SOUND)) {
    mic_stop();
  }
#endif // SL_CATALOG_MIC_DRIVER_PRESENT
}

void sensors_set_motion_period(uint16_t period_ms)
{
  if (period_ms == motion_period_ms) {
    return;
  }
  motion_period_ms = period_ms;
  // The ICM-20648 rate and the fusion filter time step are both set by
  // sl_imu_configure(), so restart the IMU to apply a new rate.
  if (enabled & TB_SENSOR_IMU) {
    imu_stop();
    imu_start();
  }
}

void sensors_set_hall_threshold(uint16_t threshold_ut)
{
  hall_threshold_mt = (float)threshold_ut / 1000.0f;
  if (available & TB_SENSOR_HALL) {
    hall_configure();
  }
}

void sensors_read_env(tb_env_t *env)
{
  memset(env, 0, sizeof(*env));
  env->uptime_ms = uptime_ms();

  if (enabled & TB_SENSOR_RHT) {
    rht_step();
    if (rht_conv.valid) {
      env->temperature_cx100 = (int16_t)(rht_t / 10);  // millidegrees -> 0.01 degC
      env->humidity_x100 = (uint16_t)(rht_rh / 10);    // milli-%RH -> 0.01 %RH
      env->valid |= TB_ENV_TEMPERATURE | TB_ENV_HUMIDITY;
    }
  }

  if (enabled & TB_SENSOR_LIGHT) {
#if defined(SL_CATALOG_SENSOR_LIGHT_PRESENT)
    light_step();
    if (light_conv.valid) {
      env->lux_x100 = (uint32_t)(light_lux * 100.0f);
      env->uv_index_x100 = (uint16_t)(light_uvi * 100.0f);
      env->valid |= TB_ENV_LUX | TB_ENV_UV;
    }
#elif defined(SL_CATALOG_SENSOR_LUX_PRESENT)
    float lux = 0.0f;
    if (sl_sensor_lux_get(&lux) == SL_STATUS_OK) {
      env->lux_x100 = (uint32_t)(lux * 100.0f);
      env->valid |= TB_ENV_LUX;
    }
#endif // SL_CATALOG_SENSOR_LIGHT_PRESENT / SL_CATALOG_SENSOR_LUX_PRESENT
  }

  if (enabled & TB_SENSOR_HALL) {
    float field_mt;
    if (sl_si7210_measure(sl_i2cspm_sensor, HALL_SCALE_UT, &field_mt) == SL_STATUS_OK) {
      float magnitude = fabsf(field_mt);
      if (magnitude < hall_threshold_mt - HALL_HYSTERESIS_MT) {
        hall_alert = false;
      } else if (magnitude > hall_threshold_mt + HALL_HYSTERESIS_MT) {
        hall_alert = true;
      }
      env->hall_ut = (int32_t)(field_mt * 1000.0f);
      env->hall_flags = (hall_alert ? TB_HALL_ALERT : 0)
                        | (magnitude > sl_si7210_get_tamper_threshold() ? TB_HALL_TAMPER : 0);
      env->valid |= TB_ENV_HALL;
    }
  }

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
  if ((enabled & TB_SENSOR_SOUND) && mic_running) {
    env->sound_db_x100 = (int16_t)(sound_level_db * 100.0f);
    env->valid |= TB_ENV_SOUND;
  }
#endif // SL_CATALOG_MIC_DRIVER_PRESENT

  if (enabled & TB_SENSOR_BATTERY) {
    env->battery_mv = (uint16_t)(sl_power_supply_measure_voltage(BATTERY_ADC_AVERAGE) * 1000.0f);
    env->valid |= TB_ENV_BATTERY;
  }

  env->die_temp_cx100 = (int16_t)(EMU_TemperatureGet() * 100.0f);
  env->valid |= TB_ENV_DIE_TEMPERATURE;
}

bool sensors_read_motion(tb_motion_t *motion)
{
  if (!(enabled & TB_SENSOR_IMU) || !imu_running() || !sl_imu_is_data_ready()) {
    return false;
  }
  sl_imu_update();
  sl_imu_get_acceleration(motion->accel_mg);
  sl_imu_get_gyro(motion->gyro_cdps);
  sl_imu_get_orientation(motion->orientation_cdeg);
  motion->uptime_ms = uptime_ms();
  return true;
}

sl_status_t sensors_calibrate_gyro(void)
{
  if (!imu_running()) {
    return SL_STATUS_NOT_INITIALIZED;
  }
  return sl_imu_calibrate_gyro();
}

void sensors_reset_orientation(void)
{
  if (imu_running()) {
    sl_imu_reset();
  }
}

void sensors_process(void)
{
#ifdef SL_CATALOG_MIC_DRIVER_PRESENT
  float level;
  if (mic_running && sl_mic_sample_buffer_ready()) {
    if (sl_mic_calculate_sound_level(&level, mic_buffer, MIC_BUFFER_FRAMES, 0) == SL_STATUS_OK) {
      sound_level_db = MIC_IIR_WEIGHT * level + (1.0f - MIC_IIR_WEIGHT) * sound_level_db;
    }
    sl_mic_get_n_samples(mic_buffer, MIC_BUFFER_FRAMES);
  }
#endif // SL_CATALOG_MIC_DRIVER_PRESENT
}
