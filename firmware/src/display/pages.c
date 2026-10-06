/***************************************************************************//**
 * @file
 * @brief The screens shown on the OLED, one reading per page.
 *
 * Layout of a reading page (128 x 64):
 *
 *   [icon] TITLE                  (*)   <- y 0..15, Bluetooth mark if connected
 *   ---------------------------------   <- y 18
 *            24.8 °C                    <- big value + unit, y 23..47
 *              . . o . .                <- page dots, y 58
 ******************************************************************************/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "display/canvas.h"
#include "display/display_assets.h"
#include "display/pages.h"

#define HEADER_TEXT_X     21
#define HEADER_TEXT_Y     3
#define DIVIDER_Y         18
#define VALUE_Y           23
#define DOTS_Y            59
#define DOT_SPACING       7

typedef struct {
  const char *title;
  const canvas_bitmap_t *icon;
} page_style_t;

static const page_style_t STYLES[PAGE_COUNT] = {
  [PAGE_TEMPERATURE]      = { "TEMPERATURE", &icon_temperature },
  [PAGE_HUMIDITY]         = { "HUMIDITY", &icon_humidity },
  [PAGE_LIGHT]            = { "LIGHT", &icon_light },
  [PAGE_UV]               = { "UV INDEX", &icon_uv },
  [PAGE_MAGNETIC]         = { "MAGNETIC", &icon_magnet },
  [PAGE_SOUND]            = { "SOUND", &icon_sound },
  [PAGE_SUPPLY]           = { "SUPPLY", &icon_supply },
  [PAGE_CHIP_TEMPERATURE] = { "CHIP TEMP", &icon_chip },
  [PAGE_ORIENTATION]      = { "ORIENTATION", &icon_orientation },
  [PAGE_BUTTON]           = { "BUTTON", &icon_button },
};

// -----------------------------------------------------------------------------
// Number formatting without floating point: value is scaled by 10^scale
// (e.g. 2483 with scale 2 = 24.83), printed with `decimals` digits.

static void format_fixed(char *out, size_t size, int32_t value, int scale, int decimals, bool show_plus)
{
  int32_t divisor = 1;
  for (int i = scale; i > decimals; i--) {
    divisor *= 10;
  }
  // Round half away from zero when dropping digits.
  int32_t rounded = value >= 0 ? (value + divisor / 2) / divisor : -((-value + divisor / 2) / divisor);
  const char *sign = rounded < 0 ? "-" : (show_plus && rounded > 0 ? "+" : "");
  int32_t magnitude = abs(rounded);

  if (decimals == 0) {
    snprintf(out, size, "%s%ld", sign, (long)magnitude);
  } else {
    int32_t unit = 1;
    for (int i = 0; i < decimals; i++) {
      unit *= 10;
    }
    snprintf(out, size, "%s%ld.%0*ld", sign, (long)(magnitude / unit), decimals, (long)(magnitude % unit));
  }
}

// -----------------------------------------------------------------------------
// Building blocks

static void draw_header(page_t page, bool connected)
{
  canvas_bitmap(0, 0, STYLES[page].icon);
  canvas_text(&font_label, HEADER_TEXT_X, HEADER_TEXT_Y, STYLES[page].title, CANVAS_ALIGN_LEFT);
  if (connected) {
    canvas_bitmap(CANVAS_WIDTH - 12, 0, &icon_bluetooth);
  }
  canvas_hline(0, DIVIDER_Y, CANVAS_WIDTH);
}

// Position indicator: one dot per page, the current one filled and larger.
static void draw_dots(int position, int count)
{
  int x = CANVAS_WIDTH / 2 - (count - 1) * DOT_SPACING / 2;
  for (int i = 0; i < count; i++, x += DOT_SPACING) {
    if (i == position) {
      canvas_circle(x, DOTS_Y, 2, true);
    } else {
      canvas_pixel(x, DOTS_Y, true);
    }
  }
}

// Big centred value with its unit next to it, top-aligned.
static void draw_value(const char *value, const char *unit)
{
  int value_width = canvas_text_width(&font_value, value);
  int unit_width = unit[0] ? canvas_text_width(&font_unit, unit) + 3 : 0;
  int x = (CANVAS_WIDTH - value_width - unit_width) / 2;

  canvas_text(&font_value, x, VALUE_Y, value, CANVAS_ALIGN_LEFT);
  if (unit[0]) {
    canvas_text(&font_unit, x + value_width + 3, VALUE_Y + 1, unit, CANVAS_ALIGN_LEFT);
  }
}

// Three columns: axis name on top, whole degrees below.
static void draw_orientation(const int16_t angles_x100[3])
{
  static const char *const AXES[3] = { "X", "Y", "Z" };
  char text[12];
  for (int axis = 0; axis < 3; axis++) {
    int center = CANVAS_WIDTH / 6 + axis * CANVAS_WIDTH / 3;
    format_fixed(text, sizeof(text), angles_x100[axis], 2, 0, false);   // "+" wouldn't fit "130°"
    strcat(text, DISPLAY_DEGREE);
    canvas_text(&font_label, center, DIVIDER_Y + 6, AXES[axis], CANVAS_ALIGN_CENTER);
    canvas_text(&font_unit, center, DIVIDER_Y + 20, text, CANVAS_ALIGN_CENTER);
  }
}

// -----------------------------------------------------------------------------
// Public interface

// page_t values double as bit numbers in Display.page_mask.
_Static_assert(DISPLAY_PAGE_BIT_TEMPERATURE == 1u << PAGE_TEMPERATURE, "page order");
_Static_assert(DISPLAY_PAGE_BIT_BUTTON == 1u << PAGE_BUTTON, "page order");
_Static_assert(DISPLAY_PAGE_ALL == (1u << PAGE_COUNT) - 1, "page order");

static bool has_data(const display_data_t *data, page_t page)
{
  uint16_t valid = data->have_env ? data->env.valid : 0;
  switch (page) {
    case PAGE_TEMPERATURE:      return valid & ENV_VALID_TEMPERATURE;
    case PAGE_HUMIDITY:         return valid & ENV_VALID_HUMIDITY;
    case PAGE_LIGHT:            return valid & ENV_VALID_LUX;
    case PAGE_UV:               return valid & ENV_VALID_UV;
    case PAGE_MAGNETIC:         return valid & ENV_VALID_HALL;
    case PAGE_SOUND:            return valid & ENV_VALID_SOUND;
    case PAGE_SUPPLY:           return valid & ENV_VALID_SUPPLY;
    case PAGE_CHIP_TEMPERATURE: return valid & ENV_VALID_DIE_TEMPERATURE;
    case PAGE_ORIENTATION:      return data->have_orientation;
    case PAGE_BUTTON:           return true;
    default:                    return false;
  }
}

int pages_available(const display_data_t *data, page_t pages[PAGE_COUNT])
{
  int n = 0;
  for (int page = 0; page < PAGE_COUNT; page++) {
    if ((data->page_mask & (1u << page)) && has_data(data, (page_t)page)) {
      pages[n++] = (page_t)page;
    }
  }
  return n;
}

void pages_draw(page_t page, const display_data_t *data, int position, int count)
{
  const ble_sensor_env_t *env = &data->env;
  char value[16];

  canvas_clear();
  draw_header(page, data->connected);

  switch (page) {
    case PAGE_TEMPERATURE:
      format_fixed(value, sizeof(value), env->temperature_c_x100, 2, 1, false);
      draw_value(value, DISPLAY_DEGREE "C");
      break;
    case PAGE_HUMIDITY:
      format_fixed(value, sizeof(value), env->humidity_pct_x100, 2, 1, false);
      draw_value(value, "%");
      break;
    case PAGE_LIGHT:
      // Below 10 lx one decimal is useful; above it just clutters.
      format_fixed(value, sizeof(value), (int32_t)env->lux_x100, 2, env->lux_x100 < 1000 ? 1 : 0, false);
      draw_value(value, "lx");
      break;
    case PAGE_UV:
      format_fixed(value, sizeof(value), env->uv_index_x100, 2, 1, false);
      draw_value(value, "");
      break;
    case PAGE_MAGNETIC:
      format_fixed(value, sizeof(value), env->hall_ut, 3, 2, false);
      draw_value(value, "mT");
      break;
    case PAGE_SOUND:
      format_fixed(value, sizeof(value), env->sound_db_x100, 2, 0, false);
      draw_value(value, "dB");
      break;
    case PAGE_SUPPLY:
      format_fixed(value, sizeof(value), env->supply_mv, 3, 2, false);
      draw_value(value, "V");
      break;
    case PAGE_CHIP_TEMPERATURE:
      format_fixed(value, sizeof(value), env->die_temp_c_x100, 2, 1, false);
      draw_value(value, DISPLAY_DEGREE "C");
      break;
    case PAGE_ORIENTATION:
      draw_orientation(data->orientation_deg_x100);
      break;
    case PAGE_BUTTON:
      format_fixed(value, sizeof(value), (int32_t)data->button_presses, 0, 0, false);
      draw_value(value, data->button_presses == 1 ? "press" : "presses");
      break;
    default:
      break;
  }
  draw_dots(position, count);
}

void pages_draw_empty(void)
{
  canvas_clear();
  canvas_text(&font_unit, CANVAS_WIDTH / 2, 16, "No reading", CANVAS_ALIGN_CENTER);
  canvas_text(&font_unit, CANVAS_WIDTH / 2, 32, "selected", CANVAS_ALIGN_CENTER);
  canvas_text(&font_label, CANVAS_WIDTH / 2, 52, "choose in the app", CANVAS_ALIGN_CENTER);
}

void pages_draw_splash(const char *name)
{
  canvas_clear();
  canvas_bitmap((CANVAS_WIDTH - 16) / 2, 6, &icon_chip);
  canvas_text(&font_unit, CANVAS_WIDTH / 2, 28, "BLE Sensor", CANVAS_ALIGN_CENTER);
  canvas_text(&font_label, CANVAS_WIDTH / 2, 48, name, CANVAS_ALIGN_CENTER);
}
