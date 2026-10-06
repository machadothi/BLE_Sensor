/***************************************************************************//**
 * @file
 * @brief Periodic sensor sampling.
 *
 * Environmental sensors are sampled all the time: the readings go to the GATT
 * service (for a connected client) and to the BTHome broadcast (for Home
 * Assistant). The microphone only runs while a client is connected. The IMU
 * does too, unless Home Assistant gets the orientation angles
 * (HOME_ASSISTANT_SEND_ORIENTATION in app_config.h).
 ******************************************************************************/

#ifndef SAMPLING_H
#define SAMPLING_H

#include <stdbool.h>
#include "ble/ble_protocol.h"

// Starts environmental sampling. Call once at boot, after bthome_init().
void sampling_start(void);

// A client connected / disconnected: switch motion sampling on / off.
void sampling_set_connected(bool connected);

// Applies the configured sensor mask (respecting connection-only sensors).
void sampling_apply_sensor_mask(void);

// Restarts the timers whose period differs from previous (NULL = both).
void sampling_update_periods(const ble_sensor_config_t *previous);

#endif // SAMPLING_H
