/***************************************************************************//**
 * @file
 * @brief The screens shown on the OLED, one reading per page.
 *
 * Pure drawing: no hardware access, so it can also be compiled on a PC to
 * preview the screens (see docs/display.md).
 ******************************************************************************/

#ifndef PAGES_H
#define PAGES_H

#include <stdbool.h>
#include <stdint.h>
#include "ble/ble_protocol.h"

typedef enum {
  PAGE_TEMPERATURE,
  PAGE_HUMIDITY,
  PAGE_LIGHT,
  PAGE_UV,
  PAGE_MAGNETIC,
  PAGE_SOUND,
  PAGE_SUPPLY,
  PAGE_CHIP_TEMPERATURE,
  PAGE_ORIENTATION,
  PAGE_BUTTON,
  PAGE_COUNT,
} page_t;

// Latest readings the pages draw from.
typedef struct {
  ble_sensor_env_t env;
  bool have_env;
  int16_t orientation_deg_x100[3];   // roll, pitch, yaw
  bool have_orientation;
  uint32_t button_presses;
  bool connected;                    // a client (app / ble-sensor) is connected
  uint16_t page_mask;                // DISPLAY_PAGE_BIT_* chosen in the app
} display_data_t;

// Fills pages[] with the pages that are selected (page_mask) and have data,
// in display order; returns how many (0 = nothing to show).
int pages_available(const display_data_t *data, page_t pages[PAGE_COUNT]);

// Draws one page into the canvas. position/count drive the dots at the bottom.
void pages_draw(page_t page, const display_data_t *data, int position, int count);

// Startup screen.
void pages_draw_splash(const char *name);

// Shown when no selected page has data (e.g. everything switched off).
void pages_draw_empty(void);

#endif // PAGES_H
