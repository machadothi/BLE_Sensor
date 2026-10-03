/***************************************************************************//**
 * @file
 * @brief BTN0: debounced state and press counter, published over BLE.
 *
 * The GPIO interrupt may not call Bluetooth APIs, so it only raises
 * BUTTON_SIGNAL with sl_bt_external_signal(). app.c passes that event to
 * button_on_signal(), which runs in normal (main loop) context.
 ******************************************************************************/

#ifndef BUTTON_H
#define BUTTON_H

#include <stdint.h>

// Bit used with sl_bt_external_signal() for button edges.
#define BUTTON_SIGNAL   (1u << 0)

// Reads the initial state and publishes it.
void button_init(void);

// Handles BUTTON_SIGNAL: restarts the debounce timer.
void button_on_signal(void);

#endif // BUTTON_H
