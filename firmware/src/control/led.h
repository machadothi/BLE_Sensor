/***************************************************************************//**
 * @file
 * @brief LED0: off, on or blinking, plus a temporary "identify" blink.
 *
 * The LED state is not stored; it starts off after every reboot.
 ******************************************************************************/

#ifndef LED_H
#define LED_H

#include <stdbool.h>
#include "ble/ble_protocol.h"

// True if mode is known and blink times are not shorter than LED_BLINK_MIN_MS.
bool led_is_valid(const ble_sensor_led_t *led);

// Switches to a new mode. Call led_is_valid() first.
void led_set(const ble_sensor_led_t *led);

const ble_sensor_led_t *led_get(void);

// Blinks fast for IDENTIFY_DURATION_MS, then returns to the current mode.
void led_identify(void);

#endif // LED_H
