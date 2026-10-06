/***************************************************************************//**
 * @file
 * @brief Persistent settings: the live configuration and the stored name.
 *
 * Owns the one copy of the configuration everybody reads, and keeps it in
 * NVM3 flash so it survives reboots and reflashing. Does not apply settings
 * to hardware; see app_apply_config() in app.c.
 ******************************************************************************/

#ifndef SETTINGS_H
#define SETTINGS_H

#include <stdbool.h>
#include <stddef.h>
#include "ble/ble_protocol.h"

// Loads the configuration from flash, or the factory defaults if none is
// stored or the stored one is invalid.
void settings_load(void);

// The live configuration. Never NULL.
const ble_sensor_config_t *settings_config(void);

// True if every field lies within the LIMIT_* values of ble_protocol.h.
bool settings_config_is_valid(const ble_sensor_config_t *config);

// Validates, stores and saves a new configuration. Returns false (and
// changes nothing) if it is out of range.
bool settings_update_config(const ble_sensor_config_t *config);

// Reads the stored device name into name (NUL-terminated). Returns false if
// no name is stored.
bool settings_load_name(char *name, size_t name_size);

void settings_save_name(const char *name);

// Gyro offset from the last calibration (°/s per axis). Returns false if the
// board was never calibrated.
bool settings_load_gyro_bias(float bias_dps[3]);
void settings_save_gyro_bias(const float bias_dps[3]);

// Which readings the OLED shows (DISPLAY_PAGE_BIT_*); the default if never set.
uint16_t settings_load_display_pages(void);
void settings_save_display_pages(uint16_t page_mask);

// How long the OLED shows each reading; the default if never set.
uint16_t settings_load_display_page_ms(void);
void settings_save_display_page_ms(uint16_t page_ms);

// Forgets everything stored and returns the live configuration to the
// factory defaults.
void settings_factory_reset(void);

#endif // SETTINGS_H
