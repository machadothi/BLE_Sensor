/***************************************************************************//**
 * @file
 * @brief The BLE Sensor GATT service: answers reads and writes, sends
 *        notifications.
 ******************************************************************************/

#include <string.h>
#include "sl_bluetooth.h"
#include "app_log.h"
#include "gatt_db.h"
#include "app.h"
#include "ble/device_name.h"
#include "control/commands.h"
#include "control/led.h"
#include "display/display.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "ble/gatt_service.h"

// ATT error codes (Bluetooth Core Specification, Vol 3, Part F, 3.4.1.1)
#define ATT_OK                      0x00
#define ATT_ERR_INVALID_OFFSET      0x07
#define ATT_ERR_INVALID_LENGTH      0x0D
#define ATT_ERR_VALUE_NOT_ALLOWED   0x13

#define NO_CONNECTION               0xFF
#define ATT_DEFAULT_MTU             23      // until the client negotiates more
#define ATT_NOTIFY_HEADER_LEN       3       // opcode + handle

static uint8_t connection = NO_CONNECTION;
static uint16_t att_mtu = ATT_DEFAULT_MTU;
static bool env_subscribed;
static bool motion_subscribed;
static bool button_subscribed;

// -----------------------------------------------------------------------------
// Connection

void gatt_service_on_connection_opened(uint8_t new_connection)
{
  connection = new_connection;
  att_mtu = ATT_DEFAULT_MTU;
}

void gatt_service_on_connection_closed(void)
{
  connection = NO_CONNECTION;
  env_subscribed = false;
  motion_subscribed = false;
  button_subscribed = false;
}

bool gatt_service_is_connected(void)
{
  return connection != NO_CONNECTION;
}

void gatt_service_on_mtu_exchanged(uint16_t mtu)
{
  att_mtu = mtu;
  app_log_info("ATT MTU %u" APP_LOG_NL, att_mtu);
}

void gatt_service_on_subscription_changed(const sl_bt_evt_gatt_server_characteristic_status_t *status)
{
  if (status->status_flags != sl_bt_gatt_server_client_config) {
    return;   // a confirmation, not a subscription change
  }
  bool subscribed = (status->client_config_flags & sl_bt_gatt_server_notification) != 0;

  switch (status->characteristic) {
    case gattdb_env:    env_subscribed = subscribed;    break;
    case gattdb_motion: motion_subscribed = subscribed; break;
    case gattdb_button: button_subscribed = subscribed; break;
    default: break;
  }
}

// -----------------------------------------------------------------------------
// Notifications

static void publish(uint16_t characteristic, bool subscribed, const void *value, size_t len)
{
  sl_bt_gatt_server_write_attribute_value(characteristic, 0, len, value);

  bool fits = len <= (size_t)(att_mtu - ATT_NOTIFY_HEADER_LEN);
  if (subscribed && gatt_service_is_connected() && fits) {
    (void)sl_bt_gatt_server_send_notification(connection, characteristic, len, value);
  }
}

void gatt_service_publish_env(const ble_sensor_env_t *env)
{
  publish(gattdb_env, env_subscribed, env, sizeof(*env));
}

void gatt_service_publish_motion(const ble_sensor_motion_t *motion)
{
  publish(gattdb_motion, motion_subscribed, motion, sizeof(*motion));
}

void gatt_service_publish_button(const ble_sensor_button_t *button)
{
  publish(gattdb_button, button_subscribed, button, sizeof(*button));
}

// -----------------------------------------------------------------------------
// Reads

void gatt_service_on_read_request(const sl_bt_evt_gatt_server_user_read_request_t *request)
{
  ble_sensor_info_t info;
  ble_sensor_display_t display_state;
  const void *value;
  size_t len;

  switch (request->characteristic) {
    case gattdb_led:
      value = led_get();
      len = sizeof(ble_sensor_led_t);
      break;
    case gattdb_config:
      value = settings_config();
      len = sizeof(ble_sensor_config_t);
      break;
    case gattdb_info:
      info = (ble_sensor_info_t) {
        .protocol_version = BLE_SENSOR_PROTOCOL_VERSION,
        .board_id = sensors_board_id(),
        .available_mask = sensors_available(),
      };
      value = &info;
      len = sizeof(info);
      break;
    case gattdb_name:
      value = device_name_get();
      len = strlen(device_name_get());
      break;
    case gattdb_display:
      display_state = (ble_sensor_display_t) {
        .present = display_is_present() ? 1 : 0,
        .page_mask = display_page_mask(),
        .page_ms = display_page_ms(),
      };
      value = &display_state;
      len = sizeof(display_state);
      break;
    default:
      sl_bt_gatt_server_send_user_read_response(request->connection, request->characteristic,
                                                ATT_ERR_VALUE_NOT_ALLOWED, 0, NULL, NULL);
      return;
  }

  // Long values are read in pieces; offset says where this piece starts.
  if (request->offset > len) {
    sl_bt_gatt_server_send_user_read_response(request->connection, request->characteristic,
                                              ATT_ERR_INVALID_OFFSET, 0, NULL, NULL);
    return;
  }
  sl_bt_gatt_server_send_user_read_response(request->connection, request->characteristic, ATT_OK,
                                            len - request->offset,
                                            (const uint8_t *)value + request->offset, NULL);
}

// -----------------------------------------------------------------------------
// Writes. Each handler returns the ATT result for the client.

static uint8_t write_led(const uint8_t *data, size_t len)
{
  ble_sensor_led_t led;
  if (len != sizeof(led)) {
    return ATT_ERR_INVALID_LENGTH;
  }
  memcpy(&led, data, sizeof(led));
  if (!led_is_valid(&led)) {
    return ATT_ERR_VALUE_NOT_ALLOWED;
  }
  led_set(&led);
  return ATT_OK;
}

static uint8_t write_config(const uint8_t *data, size_t len)
{
  ble_sensor_config_t config;
  if (len != sizeof(config)) {
    return ATT_ERR_INVALID_LENGTH;
  }
  memcpy(&config, data, sizeof(config));

  ble_sensor_config_t previous = *settings_config();
  if (!settings_update_config(&config)) {
    return ATT_ERR_VALUE_NOT_ALLOWED;
  }
  app_apply_config(&previous);
  app_log_info("Config updated" APP_LOG_NL);
  return ATT_OK;
}

static uint8_t write_name(const uint8_t *data, size_t len)
{
  if (len == 0 || len > LIMIT_NAME_MAX_LEN) {
    return ATT_ERR_INVALID_LENGTH;
  }
  device_name_set(data, len);
  return ATT_OK;
}

static uint8_t write_display(const uint8_t *data, size_t len)
{
  ble_sensor_display_t display;
  if (len != sizeof(display)) {
    return ATT_ERR_INVALID_LENGTH;
  }
  memcpy(&display, data, sizeof(display));
  if ((display.page_mask & ~DISPLAY_PAGE_ALL) != 0
      || display.page_ms < LIMIT_DISPLAY_PAGE_MIN_MS || display.page_ms > LIMIT_DISPLAY_PAGE_MAX_MS) {
    return ATT_ERR_VALUE_NOT_ALLOWED;
  }
  if (display.page_mask != display_page_mask()) {
    display_set_page_mask(display.page_mask);
    settings_save_display_pages(display.page_mask);
  }
  if (display.page_ms != display_page_ms()) {
    display_set_page_ms(display.page_ms);
    settings_save_display_page_ms(display.page_ms);
  }
  app_log_info("Display: pages 0x%03X, %u ms each" APP_LOG_NL, display.page_mask, display.page_ms);
  return ATT_OK;
}

static void respond(const sl_bt_evt_gatt_server_user_write_request_t *request, uint8_t result)
{
  // "Write without response" gets no answer; only write requests do.
  if (request->att_opcode == sl_bt_gatt_write_request) {
    sl_bt_gatt_server_send_user_write_response(request->connection, request->characteristic, result);
  }
}

void gatt_service_on_write_request(const sl_bt_evt_gatt_server_user_write_request_t *request)
{
  const uint8_t *data = request->value.data;
  size_t len = request->value.len;

  if (request->offset != 0) {
    respond(request, ATT_ERR_INVALID_OFFSET);
    return;
  }

  switch (request->characteristic) {
    case gattdb_led:
      respond(request, write_led(data, len));
      break;
    case gattdb_config:
      respond(request, write_config(data, len));
      break;
    case gattdb_name:
      respond(request, write_name(data, len));
      break;
    case gattdb_display:
      respond(request, write_display(data, len));
      break;
    case gattdb_command:
      if (len != 1) {
        respond(request, ATT_ERR_INVALID_LENGTH);
        break;
      }
      // Answer first: some commands block (gyro calibration takes ~1 s).
      respond(request, commands_is_known(data[0]) ? ATT_OK : ATT_ERR_VALUE_NOT_ALLOWED);
      commands_run(data[0]);
      break;
    default:
      respond(request, ATT_ERR_VALUE_NOT_ALLOWED);
      break;
  }
}
