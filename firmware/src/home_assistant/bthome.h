/***************************************************************************//**
 * @file
 * @brief Home Assistant: BTHome v2 broadcasts of the sensor readings.
 *
 * Everything the firmware does for Home Assistant lives in this folder.
 * Switch it off with HOME_ASSISTANT_ENABLED in app_config.h; the functions
 * below then do nothing. Setup guide: docs/home-assistant.md.
 *
 * A second, non-connectable advertising set carries the latest readings in
 * the BTHome format (https://bthome.io): temperature, humidity, light, UV,
 * button presses, and the X/Y/Z orientation angles. Home Assistant's BTHome integration
 * discovers it on its own: no pairing, no connection, read-only. It runs next
 * to the normal connectable advertising and keeps going while a client
 * (phone app, ble-sensor) is connected.
 ******************************************************************************/

#ifndef BTHOME_H
#define BTHOME_H

#include "ble/ble_protocol.h"

// Creates the broadcast and starts it (empty until the first reading).
void bthome_init(void);

// Latest environmental readings; sent in the next environment packet.
void bthome_publish_env(const ble_sensor_env_t *env);

// Latest roll, pitch, yaw (° x 100); sent in the next orientation packet.
void bthome_publish_orientation(const int16_t orientation_deg_x100[3]);

// Broadcasts a button press event right away (one event in Home Assistant).
void bthome_button_pressed(void);

// The radio's TX power can only change while nothing advertises, so
// advertising.c pauses the broadcast around that change.
void bthome_pause(void);
void bthome_resume(void);

#endif // BTHOME_H
