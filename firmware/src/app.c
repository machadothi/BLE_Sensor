/***************************************************************************//**
 * @file
 * @brief BLE Sensor application: startup, main-loop hook, Bluetooth events.
 *
 * This file only decides *which module handles what*. The work happens in:
 *   ble/       advertising, GATT service, device name, wire protocol
 *   home_assistant/  BTHome broadcast that Home Assistant reads
 *   display/   optional OLED screen showing one reading at a time
 *   control/   LED, button, commands
 *   sensors/   all sensor access
 *   storage/   settings in flash
 *   sampling   periodic sensor timers
 * Tunable values: app_config.h.
 ******************************************************************************/

#include "sl_bluetooth.h"
#include "app_log.h"
#include "gatt_db.h"
#include "app_config.h"
#include "ble/advertising.h"
#include "display/display.h"
#include "home_assistant/bthome.h"
#include "ble/device_name.h"
#include "ble/gatt_service.h"
#include "control/button.h"
#include "sampling.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "app.h"

void app_init(void)
{
  app_log_info("BLE Sensor firmware " FIRMWARE_VERSION ", protocol v%d" APP_LOG_NL,
               BLE_SENSOR_PROTOCOL_VERSION);
  sensors_init();
}

void app_process_action(void)
{
  sensors_process();
}

void app_apply_config(const ble_sensor_config_t *previous)
{
  const ble_sensor_config_t *config = settings_config();

  sensors_set_motion_period(config->motion_period_ms);
  sensors_set_hall_threshold(config->hall_threshold_ut);

  // TX power and advertising interval are applied by advertising_start().
  sampling_apply_sensor_mask();
  sampling_update_periods(previous);
}

// -----------------------------------------------------------------------------
// Bluetooth events

static void on_boot(void)
{
  settings_load();
  device_name_load();       // needs the stack: the default name uses the address
  app_apply_config(NULL);
  bthome_init();            // Home Assistant broadcast; before anything publishes to it
  display_init(device_name_get());   // OLED, if connected
  button_init();
  advertising_init();
  advertising_start();
  sampling_start();
}

static void on_connection_opened(const sl_bt_evt_connection_opened_t *event)
{
  app_log_info("Connected" APP_LOG_NL);
  gatt_service_on_connection_opened(event->connection);
  sl_bt_connection_set_parameters(event->connection, CONN_INTERVAL_MIN, CONN_INTERVAL_MAX,
                                  CONN_PERIPHERAL_LATENCY, CONN_SUPERVISION_TIMEOUT, 0, 0xFFFF);
  sampling_set_connected(true);
  display_set_connected(true);
}

static void on_connection_closed(const sl_bt_evt_connection_closed_t *event)
{
  app_log_info("Disconnected (reason 0x%04X)" APP_LOG_NL, event->reason);
  gatt_service_on_connection_closed();
  sampling_set_connected(false);
  display_set_connected(false);
  advertising_start();      // also applies a new name, TX power or interval
}

void sl_bt_on_event(sl_bt_msg_t *evt)
{
  switch (SL_BT_MSG_ID(evt->header)) {
    case sl_bt_evt_system_boot_id:
      on_boot();
      break;

    case sl_bt_evt_connection_opened_id:
      on_connection_opened(&evt->data.evt_connection_opened);
      break;

    case sl_bt_evt_connection_closed_id:
      on_connection_closed(&evt->data.evt_connection_closed);
      break;

    case sl_bt_evt_gatt_mtu_exchanged_id:
      gatt_service_on_mtu_exchanged(evt->data.evt_gatt_mtu_exchanged.mtu);
      break;

    case sl_bt_evt_gatt_server_characteristic_status_id:
      gatt_service_on_subscription_changed(&evt->data.evt_gatt_server_characteristic_status);
      break;

    case sl_bt_evt_gatt_server_user_read_request_id:
      gatt_service_on_read_request(&evt->data.evt_gatt_server_user_read_request);
      break;

    case sl_bt_evt_gatt_server_user_write_request_id:
      gatt_service_on_write_request(&evt->data.evt_gatt_server_user_write_request);
      break;

    case sl_bt_evt_gatt_server_attribute_value_id:
      // Only the GAP Device Name is stored by the stack and writable.
      if (evt->data.evt_gatt_server_attribute_value.attribute == gattdb_device_name) {
        device_name_on_gap_write();
      }
      break;

    case sl_bt_evt_system_external_signal_id:
      if (evt->data.evt_system_external_signal.extsignals & BUTTON_SIGNAL) {
        button_on_signal();
      }
      break;

    default:
      break;
  }
}
