/***************************************************************************//**
 * @file
 * @brief Persistent configuration and device name, stored in NVM3.
 ******************************************************************************/

#include <stdio.h>
#include <string.h>
#include "nvm3_default.h"
#include "sl_bluetooth.h"
#include "app_log.h"
#include "settings.h"

// NVM3 keys 0x40000-0x4FFFF belong to the Bluetooth stack; stay well below.
#define NVM3_KEY_CONFIG         0x01001
#define NVM3_KEY_NAME           0x01002

#define NAME_PREFIX             "TB-Game-"

void settings_config_defaults(tb_config_t *config)
{
  *config = (tb_config_t) {
    .version = TB_PROTOCOL_VERSION,
    .sensor_mask = TB_SENSOR_ALL,
    .env_period_ms = 1000,
    .motion_period_ms = 50,
    .tx_power_dbm_x10 = 0,
    .adv_interval_ms = 100,
    .hall_threshold_ut = 3000,
  };
}

void settings_name_default(char *name, size_t name_size)
{
  bd_addr address;
  uint8_t address_type;

  // Suffix with the last two address bytes so several boards are distinguishable.
  if (sl_bt_gap_get_identity_address(&address, &address_type) == SL_STATUS_OK) {
    snprintf(name, name_size, NAME_PREFIX "%02X%02X", address.addr[1], address.addr[0]);
  } else {
    snprintf(name, name_size, NAME_PREFIX "0000");
  }
}

bool settings_config_is_valid(const tb_config_t *config)
{
  return config->env_period_ms >= TB_ENV_PERIOD_MIN_MS
         && config->env_period_ms <= TB_ENV_PERIOD_MAX_MS
         && config->motion_period_ms >= TB_MOTION_PERIOD_MIN_MS
         && config->motion_period_ms <= TB_MOTION_PERIOD_MAX_MS
         && config->tx_power_dbm_x10 >= TB_TX_POWER_MIN_DBM_X10
         && config->tx_power_dbm_x10 <= TB_TX_POWER_MAX_DBM_X10
         && config->adv_interval_ms >= TB_ADV_INTERVAL_MIN_MS
         && config->adv_interval_ms <= TB_ADV_INTERVAL_MAX_MS
         && config->hall_threshold_ut >= TB_HALL_THRESHOLD_MIN_UT
         && config->hall_threshold_ut <= TB_HALL_THRESHOLD_MAX_UT
         && (config->sensor_mask & ~TB_SENSOR_ALL) == 0;
}

void settings_load(tb_config_t *config, char *name, size_t name_size)
{
  uint32_t type;
  size_t len;

  if (nvm3_readData(nvm3_defaultHandle, NVM3_KEY_CONFIG, config, sizeof(*config)) != SL_STATUS_OK
      || !settings_config_is_valid(config)) {
    settings_config_defaults(config);
  }
  config->version = TB_PROTOCOL_VERSION;

  memset(name, 0, name_size);
  if (nvm3_getObjectInfo(nvm3_defaultHandle, NVM3_KEY_NAME, &type, &len) == SL_STATUS_OK
      && len > 0 && len < name_size
      && nvm3_readData(nvm3_defaultHandle, NVM3_KEY_NAME, name, len) == SL_STATUS_OK) {
    name[len] = '\0';
  } else {
    settings_name_default(name, name_size);
  }
}

void settings_save_config(const tb_config_t *config)
{
  sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, NVM3_KEY_CONFIG, config, sizeof(*config));
  if (sc != SL_STATUS_OK) {
    app_log_status_error_f(sc, "Saving config failed" APP_LOG_NL);
  }
}

void settings_save_name(const char *name)
{
  sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, NVM3_KEY_NAME, name, strlen(name));
  if (sc != SL_STATUS_OK) {
    app_log_status_error_f(sc, "Saving name failed" APP_LOG_NL);
  }
}

void settings_erase(void)
{
  nvm3_deleteObject(nvm3_defaultHandle, NVM3_KEY_CONFIG);
  nvm3_deleteObject(nvm3_defaultHandle, NVM3_KEY_NAME);
}
