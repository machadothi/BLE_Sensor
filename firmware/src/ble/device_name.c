/***************************************************************************//**
 * @file
 * @brief The board's name: stored, advertised, and readable over GATT.
 ******************************************************************************/

#include <stdio.h>
#include <string.h>
#include "sl_bluetooth.h"
#include "app_assert.h"
#include "app_log.h"
#include "gatt_db.h"
#include "app_config.h"
#include "storage/settings.h"
#include "ble/device_name.h"

static char name[LIMIT_NAME_MAX_LEN + 1];

// Writes the name into the GAP Device Name characteristic.
static void publish_to_gap(void)
{
  sl_status_t sc = sl_bt_gatt_server_write_attribute_value(gattdb_device_name, 0, strlen(name),
                                                           (const uint8_t *)name);
  app_assert_status(sc);
}

// DEFAULT_NAME_PREFIX + last two address bytes, e.g. "BLE-Sensor-B0DF".
static void make_default(void)
{
  bd_addr address;
  uint8_t address_type;

  if (sl_bt_gap_get_identity_address(&address, &address_type) == SL_STATUS_OK) {
    snprintf(name, sizeof(name), DEFAULT_NAME_PREFIX "%02X%02X", address.addr[1], address.addr[0]);
  } else {
    snprintf(name, sizeof(name), DEFAULT_NAME_PREFIX "0000");
  }
}

void device_name_load(void)
{
  if (!settings_load_name(name, sizeof(name))) {
    make_default();
  }
  publish_to_gap();
}

const char *device_name_get(void)
{
  return name;
}

void device_name_set(const uint8_t *new_name, size_t len)
{
  memcpy(name, new_name, len);
  name[len] = '\0';
  settings_save_name(name);
  publish_to_gap();
  app_log_info("Name set to \"%s\" (advertised after disconnect)" APP_LOG_NL, name);
}

void device_name_reset(void)
{
  make_default();
  publish_to_gap();
}

void device_name_on_gap_write(void)
{
  uint8_t written[LIMIT_NAME_MAX_LEN];
  size_t len = 0;

  if (sl_bt_gatt_server_read_attribute_value(gattdb_device_name, 0, sizeof(written), &len, written)
      == SL_STATUS_OK && len > 0) {
    device_name_set(written, len);
  }
}
