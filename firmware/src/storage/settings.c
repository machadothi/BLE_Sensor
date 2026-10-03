/***************************************************************************//**
 * @file
 * @brief Persistent settings: the live configuration and the stored name.
 ******************************************************************************/

#include <string.h>
#include "nvm3_default.h"
#include "app_log.h"
#include "app_config.h"
#include "storage/settings.h"

static ble_sensor_config_t live_config;

static void config_set_defaults(ble_sensor_config_t *config)
{
  *config = (ble_sensor_config_t) {
    .version = BLE_SENSOR_PROTOCOL_VERSION,
    .sensor_mask = DEFAULT_SENSOR_MASK,
    .env_period_ms = DEFAULT_ENV_PERIOD_MS,
    .motion_period_ms = DEFAULT_MOTION_PERIOD_MS,
    .tx_power_dbm_x10 = DEFAULT_TX_POWER_DBM_X10,
    .adv_interval_ms = DEFAULT_ADV_INTERVAL_MS,
    .hall_threshold_ut = DEFAULT_HALL_THRESHOLD_UT,
  };
}

static void save(nvm3_ObjectKey_t key, const void *data, size_t len, const char *what)
{
  sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, key, data, len);
  if (sc != SL_STATUS_OK) {
    app_log_status_error_f(sc, "Saving %s failed" APP_LOG_NL, what);
  }
}

static bool in_range(int32_t value, int32_t min, int32_t max)
{
  return value >= min && value <= max;
}

bool settings_config_is_valid(const ble_sensor_config_t *config)
{
  return in_range(config->env_period_ms, LIMIT_ENV_PERIOD_MIN_MS, LIMIT_ENV_PERIOD_MAX_MS)
         && in_range(config->motion_period_ms, LIMIT_MOTION_PERIOD_MIN_MS, LIMIT_MOTION_PERIOD_MAX_MS)
         && in_range(config->tx_power_dbm_x10, LIMIT_TX_POWER_MIN_DBM_X10, LIMIT_TX_POWER_MAX_DBM_X10)
         && in_range(config->adv_interval_ms, LIMIT_ADV_INTERVAL_MIN_MS, LIMIT_ADV_INTERVAL_MAX_MS)
         && in_range(config->hall_threshold_ut, LIMIT_HALL_THRESHOLD_MIN_UT, LIMIT_HALL_THRESHOLD_MAX_UT)
         && (config->sensor_mask & ~SENSOR_BIT_ALL) == 0;
}

void settings_load(void)
{
  sl_status_t sc = nvm3_readData(nvm3_defaultHandle, NVM3_KEY_CONFIG, &live_config, sizeof(live_config));
  if (sc != SL_STATUS_OK || !settings_config_is_valid(&live_config)) {
    config_set_defaults(&live_config);
  }
  live_config.version = BLE_SENSOR_PROTOCOL_VERSION;
}

const ble_sensor_config_t *settings_config(void)
{
  return &live_config;
}

bool settings_update_config(const ble_sensor_config_t *config)
{
  if (!settings_config_is_valid(config)) {
    return false;
  }
  live_config = *config;
  live_config.version = BLE_SENSOR_PROTOCOL_VERSION;
  save(NVM3_KEY_CONFIG, &live_config, sizeof(live_config), "config");
  return true;
}

bool settings_load_name(char *name, size_t name_size)
{
  uint32_t type;
  size_t len;

  if (nvm3_getObjectInfo(nvm3_defaultHandle, NVM3_KEY_NAME, &type, &len) != SL_STATUS_OK
      || len == 0 || len >= name_size
      || nvm3_readData(nvm3_defaultHandle, NVM3_KEY_NAME, name, len) != SL_STATUS_OK) {
    return false;
  }
  name[len] = '\0';
  return true;
}

void settings_save_name(const char *name)
{
  save(NVM3_KEY_NAME, name, strlen(name), "name");
}

void settings_factory_reset(void)
{
  nvm3_deleteObject(nvm3_defaultHandle, NVM3_KEY_CONFIG);
  nvm3_deleteObject(nvm3_defaultHandle, NVM3_KEY_NAME);
  config_set_defaults(&live_config);
}
