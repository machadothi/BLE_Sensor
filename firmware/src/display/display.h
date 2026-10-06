/***************************************************************************//**
 * @file
 * @brief OLED display: shows one reading at a time (temperature, humidity,
 *        light, ...), switching after a time chosen in the app.
 *
 * Everything for the display lives in this folder: ssd1306 (the controller),
 * canvas (drawing), pages (the screens), display_assets (generated fonts and
 * icons). Settings: the Display section of app_config.h. Guide: docs/display.md.
 * With DISPLAY_ENABLED 0, or no display connected, every function does nothing.
 ******************************************************************************/

#ifndef DISPLAY_H
#define DISPLAY_H

#include <stdbool.h>
#include <stdint.h>
#include "ble/ble_protocol.h"

// Finds the display, shows the startup screen, starts the page rotation.
// Call after the device name is known.
void display_init(const char *device_name);

// Latest readings to show.
void display_publish_env(const ble_sensor_env_t *env);
void display_publish_orientation(const int16_t orientation_deg_x100[3]);
void display_publish_button(uint32_t press_count);
void display_set_connected(bool connected);

// True if a display answered at boot.
bool display_is_present(void);

// Which readings to show (DISPLAY_PAGE_BIT_*). Takes effect at the next page
// change; stored in flash by the caller.
void display_set_page_mask(uint16_t page_mask);
uint16_t display_page_mask(void);

// How long each reading stays on screen. Takes effect from the next page.
void display_set_page_ms(uint16_t page_ms);
uint16_t display_page_ms(void);

#endif // DISPLAY_H
