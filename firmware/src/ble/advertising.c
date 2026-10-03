/***************************************************************************//**
 * @file
 * @brief Connectable advertising with the service UUID, board id and name.
 *
 * Advertising packet (27 of 31 bytes):
 *   flags | complete list of 128-bit UUIDs (our service) |
 *   manufacturer data: company id, board id, protocol version
 * Scan response: complete local name.
 *
 * The manufacturer data also has a practical reason: BlueZ drops repeated
 * advertisements from known devices unless they carry manufacturer or
 * service data, which made the board hard to find from Linux.
 ******************************************************************************/

#include <string.h>
#include "sl_bluetooth.h"
#include "app_assert.h"
#include "app_log.h"
#include "app_config.h"
#include "ble/device_name.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "ble/advertising.h"

// Advertising data (AD) types, Bluetooth Core Specification Supplement
#define AD_TYPE_FLAGS                     0x01
#define AD_TYPE_COMPLETE_128BIT_UUIDS     0x07
#define AD_TYPE_COMPLETE_LOCAL_NAME       0x09
#define AD_TYPE_MANUFACTURER_DATA         0xFF

#define AD_FLAG_LE_GENERAL_DISCOVERABLE   0x02
#define AD_FLAG_BR_EDR_NOT_SUPPORTED      0x04

#define MAX_LEGACY_ADV_DATA_LEN           31

static uint8_t advertising_set = 0xFF;

// Appends one AD structure (length, type, payload) and returns the new length.
static size_t append_ad(uint8_t *packet, size_t len, uint8_t type, const void *payload, size_t payload_len)
{
  packet[len++] = (uint8_t)(1 + payload_len);
  packet[len++] = type;
  memcpy(&packet[len], payload, payload_len);
  return len + payload_len;
}

static void set_advertising_data(void)
{
  static const uint8_t service_uuid[16] = BLE_SENSOR_SERVICE_UUID_BYTES;
  const uint8_t flags = AD_FLAG_LE_GENERAL_DISCOVERABLE | AD_FLAG_BR_EDR_NOT_SUPPORTED;
  const uint8_t manufacturer_data[] = {
    ADV_COMPANY_ID & 0xFF, ADV_COMPANY_ID >> 8,   // little-endian company id
    sensors_board_id(),
    BLE_SENSOR_PROTOCOL_VERSION,
  };
  uint8_t packet[MAX_LEGACY_ADV_DATA_LEN];
  size_t len = 0;

  len = append_ad(packet, len, AD_TYPE_FLAGS, &flags, sizeof(flags));
  len = append_ad(packet, len, AD_TYPE_COMPLETE_128BIT_UUIDS, service_uuid, sizeof(service_uuid));
  len = append_ad(packet, len, AD_TYPE_MANUFACTURER_DATA, manufacturer_data, sizeof(manufacturer_data));
  sl_status_t sc = sl_bt_legacy_advertiser_set_data(advertising_set, sl_bt_advertiser_advertising_data_packet,
                                                    len, packet);
  app_assert_status(sc);

  const char *name = device_name_get();
  len = append_ad(packet, 0, AD_TYPE_COMPLETE_LOCAL_NAME, name, strlen(name));
  sc = sl_bt_legacy_advertiser_set_data(advertising_set, sl_bt_advertiser_scan_response_packet, len, packet);
  app_assert_status(sc);
}

static void apply_tx_power(int16_t max_dbm_x10)
{
  int16_t set_min, set_max;
  sl_status_t sc = sl_bt_system_set_tx_power(LIMIT_TX_POWER_MIN_DBM_X10, max_dbm_x10, &set_min, &set_max);
  if (sc != SL_STATUS_OK) {
    app_log_status_error_f(sc, "Setting TX power failed" APP_LOG_NL);
    return;
  }
  // The radio rounds to the nearest level it supports; log what it chose.
  int magnitude = set_max < 0 ? -set_max : set_max;
  app_log_info("TX power max %s%d.%d dBm" APP_LOG_NL, set_max < 0 ? "-" : "", magnitude / 10, magnitude % 10);
}

void advertising_init(void)
{
  sl_status_t sc = sl_bt_advertiser_create_set(&advertising_set);
  app_assert_status(sc);
}

void advertising_start(void)
{
  const ble_sensor_config_t *config = settings_config();
  uint32_t interval = (uint32_t)config->adv_interval_ms * 1000u / 625u;   // in units of 0.625 ms

  apply_tx_power(config->tx_power_dbm_x10);

  sl_status_t sc = sl_bt_advertiser_set_timing(advertising_set, interval, interval, 0, 0);
  app_assert_status(sc);
  set_advertising_data();
  sc = sl_bt_legacy_advertiser_start(advertising_set, sl_bt_legacy_advertiser_connectable);
  app_assert_status(sc);

  app_log_info("Advertising as \"%s\"" APP_LOG_NL, device_name_get());
}
