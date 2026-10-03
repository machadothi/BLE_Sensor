/***************************************************************************//**
 * @file
 * @brief Application entry points called by the SDK, plus config application.
 *
 * There is no main() in this project's sources: slc generates it into
 * build/<board>/main.c. It calls app_init() once, then app_process_action()
 * in a loop, and the Bluetooth stack delivers events to sl_bt_on_event().
 * See docs/firmware.md.
 ******************************************************************************/

#ifndef APP_H
#define APP_H

#include "ble/ble_protocol.h"

void app_init(void);
void app_process_action(void);

// Pushes the live configuration to the sensors and timers. previous is the
// configuration before the change (NULL = apply everything).
void app_apply_config(const ble_sensor_config_t *previous);

#endif // APP_H
