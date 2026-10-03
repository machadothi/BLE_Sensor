/***************************************************************************//**
 * @file
 * @brief The board's name: stored, advertised, and readable over GATT.
 *
 * The name exists in two GATT characteristics: the standard GAP Device Name
 * and our own Name characteristic (BlueZ hides GAP from Linux clients).
 * Both stay in sync. A new name is advertised after the next disconnect.
 ******************************************************************************/

#ifndef DEVICE_NAME_H
#define DEVICE_NAME_H

#include <stddef.h>
#include <stdint.h>

// Loads the stored name, or makes the default DEFAULT_NAME_PREFIX + "XXXX".
// Call after the Bluetooth stack has booted (the default uses its address).
void device_name_load(void);

const char *device_name_get(void);

// Sets, stores and publishes a new name (1..LIMIT_NAME_MAX_LEN bytes).
void device_name_set(const uint8_t *name, size_t len);

// Back to the default name (the stored one must already be erased).
void device_name_reset(void);

// A client wrote the GAP Device Name directly: adopt and store it.
void device_name_on_gap_write(void);

#endif // DEVICE_NAME_H
