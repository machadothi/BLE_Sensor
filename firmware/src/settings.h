/***************************************************************************//**
 * @file
 * @brief Persistent configuration and device name, stored in NVM3.
 ******************************************************************************/

#ifndef SETTINGS_H
#define SETTINGS_H

#include <stdbool.h>
#include <stddef.h>
#include "tb_protocol.h"

#define SETTINGS_NAME_MAX_LEN   20

// Loads settings from NVM3, falling back to defaults for anything missing.
void settings_load(tb_config_t *config, char *name, size_t name_size);

// Returns true if every field of config is within the documented limits.
bool settings_config_is_valid(const tb_config_t *config);

void settings_config_defaults(tb_config_t *config);
void settings_name_default(char *name, size_t name_size);

void settings_save_config(const tb_config_t *config);
void settings_save_name(const char *name);

// Deletes everything stored by this module.
void settings_erase(void);

#endif // SETTINGS_H
