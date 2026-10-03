/***************************************************************************//**
 * @file
 * @brief The BLE Sensor GATT service: answers reads and writes, sends
 *        notifications.
 *
 * Owns the connection handle and which characteristics the client has
 * subscribed to. Characteristic layouts: ble/ble_protocol.h; UUIDs:
 * config/btconf/gatt_configuration.btconf.
 ******************************************************************************/

#ifndef GATT_SERVICE_H
#define GATT_SERVICE_H

#include <stdbool.h>
#include <stdint.h>
#include "sl_bt_api.h"
#include "ble/ble_protocol.h"

void gatt_service_on_connection_opened(uint8_t connection);
void gatt_service_on_connection_closed(void);
bool gatt_service_is_connected(void);
void gatt_service_on_mtu_exchanged(uint16_t mtu);

// The client switched notifications on or off for a characteristic.
void gatt_service_on_subscription_changed(const sl_bt_evt_gatt_server_characteristic_status_t *status);

// Reads and writes of the characteristics the app answers itself
// (type="user" in the .btconf): LED, Config, Info, Command, Name.
void gatt_service_on_read_request(const sl_bt_evt_gatt_server_user_read_request_t *request);
void gatt_service_on_write_request(const sl_bt_evt_gatt_server_user_write_request_t *request);

// Stores a new value so reads return it, and notifies the client if it
// subscribed. A notification that does not fit the stack's queue is dropped;
// the next sample follows soon.
void gatt_service_publish_env(const ble_sensor_env_t *env);
void gatt_service_publish_motion(const ble_sensor_motion_t *motion);
void gatt_service_publish_button(const ble_sensor_button_t *button);

#endif // GATT_SERVICE_H
