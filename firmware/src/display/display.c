/***************************************************************************//**
 * @file
 * @brief OLED display: shows one reading at a time, switching every page_ms
 *        (DEFAULT_DISPLAY_PAGE_MS until the app sets another time).
 *
 * A full screen update is 1 KB over a 100 kHz I2C bus shared with the
 * sensors, about 90 ms. Instead of blocking that long, the frame is sent one
 * page (1/8 of the screen) per timer tick, so motion sampling and Bluetooth
 * keep running in between.
 *
 * Page change: the contrast fades down, the new page is drawn and sent, the
 * contrast fades back up. While a page is shown, it is redrawn every
 * DISPLAY_REFRESH_MS so its value stays live.
 ******************************************************************************/

#include "app_config.h"
#include "display/display.h"

#if DISPLAY_ENABLED

#include <string.h>
#include "app_log.h"
#include "app_timer.h"
#include "display/canvas.h"
#include "display/pages.h"
#include "display/ssd1306.h"
#include "storage/settings.h"

#define FADE_STEPS          4
#define FADE_STEP_MS        30
#define SEND_PAGE_GAP_MS    1      // pause between screen pieces: lets other work run

typedef enum {
  STATE_OFF,            // no display found
  STATE_SPLASH,         // startup screen
  STATE_SHOWING,        // a page is on screen
  STATE_FADING_OUT,
  STATE_SENDING,        // new frame on its way
  STATE_FADING_IN,
} state_t;

static state_t state = STATE_OFF;
static display_data_t data;
static page_t current_page = PAGE_COUNT;   // none yet
static int fade_step;
static uint8_t next_page_to_send;
static bool fade_in_after_send;
// A page change that came while the screen was busy (e.g. a refresh was being
// sent). The page and refresh timers share a rhythm (2000 ms is a multiple of
// 500 ms), so without this the page change always hit a busy screen and was
// lost: the display never moved on.
static bool page_change_pending;
static uint16_t page_ms = DEFAULT_DISPLAY_PAGE_MS;
static bool splash_done;

static app_timer_t page_timer;      // next page
static app_timer_t refresh_timer;   // redraw the current page
static app_timer_t step_timer;      // fade steps and screen pieces

static void step(app_timer_t *timer, void *context);
static void start_page_change(void);

// -----------------------------------------------------------------------------
// Drawing and sending

// Picks the page after the current one among those that are selected and
// have data. current_page = PAGE_COUNT means "nothing to show".
static void advance_page(void)
{
  page_t pages[PAGE_COUNT];
  int count = pages_available(&data, pages);
  if (count == 0) {
    current_page = PAGE_COUNT;
    return;
  }
  int next = 0;
  for (int i = 0; i < count; i++) {
    if (pages[i] == current_page) {
      next = (i + 1) % count;
      break;
    }
  }
  current_page = pages[next];
}

static void draw_current_page(void)
{
  page_t pages[PAGE_COUNT];
  int count = pages_available(&data, pages);
  if (current_page == PAGE_COUNT) {
    pages_draw_empty();
    return;
  }
  int position = 0;
  for (int i = 0; i < count; i++) {
    if (pages[i] == current_page) {
      position = i;
    }
  }
  pages_draw(current_page, &data, position, count);
}

// Sends the canvas piece by piece; step() continues it.
static void start_sending(bool then_fade_in)
{
  state = STATE_SENDING;
  next_page_to_send = 0;
  fade_in_after_send = then_fade_in;
  app_timer_start(&step_timer, SEND_PAGE_GAP_MS, step, NULL, false);
}

static void set_fade_contrast(int level)   // level 0..FADE_STEPS
{
  ssd1306_set_contrast((uint8_t)(DISPLAY_CONTRAST * level / FADE_STEPS));
}

// -----------------------------------------------------------------------------
// State machine, driven by step_timer

static void step(app_timer_t *timer, void *context)
{
  (void)timer;
  (void)context;

  switch (state) {
    case STATE_FADING_OUT:
      set_fade_contrast(--fade_step);
      if (fade_step > 0) {
        app_timer_start(&step_timer, FADE_STEP_MS, step, NULL, false);
      } else {
        advance_page();
        draw_current_page();
        start_sending(true);
      }
      break;

    case STATE_SENDING:
      ssd1306_write_page(next_page_to_send, &canvas_buffer[next_page_to_send * CANVAS_WIDTH]);
      if (++next_page_to_send < CANVAS_PAGES) {
        app_timer_start(&step_timer, SEND_PAGE_GAP_MS, step, NULL, false);
      } else if (fade_in_after_send) {
        state = STATE_FADING_IN;
        fade_step = 0;
        app_timer_start(&step_timer, FADE_STEP_MS, step, NULL, false);
      } else {
        state = STATE_SHOWING;
        if (page_change_pending) {
          start_page_change();
        }
      }
      break;

    case STATE_FADING_IN:
      set_fade_contrast(++fade_step);
      if (fade_step < FADE_STEPS) {
        app_timer_start(&step_timer, FADE_STEP_MS, step, NULL, false);
      } else {
        state = STATE_SHOWING;
        if (page_change_pending) {
          start_page_change();
        }
      }
      break;

    default:
      break;
  }
}

static void start_page_change(void)
{
  page_change_pending = false;
  state = STATE_FADING_OUT;
  fade_step = FADE_STEPS;
  step(NULL, NULL);
}

static void next_page(app_timer_t *timer, void *context)
{
  (void)timer;
  (void)context;
  if (state == STATE_SHOWING || state == STATE_SPLASH) {
    start_page_change();
  } else if (state != STATE_OFF) {
    page_change_pending = true;   // busy: change as soon as it's done
  }
}

// The splash screen ends with the first page change; from then on pages
// change every page_ms.
static void end_splash(app_timer_t *timer, void *context)
{
  splash_done = true;
  next_page(timer, context);
  app_timer_start(&page_timer, page_ms, next_page, NULL, true);
}

static void refresh(app_timer_t *timer, void *context)
{
  (void)timer;
  (void)context;
  if (state == STATE_SHOWING && !page_change_pending) {
    draw_current_page();
    start_sending(false);
  }
}

// -----------------------------------------------------------------------------
// Public interface

void display_init(const char *device_name)
{
  if (!ssd1306_init()) {
    app_log_info("No OLED display found" APP_LOG_NL);
    return;
  }
  app_log_info("OLED display found" APP_LOG_NL);

  data.page_mask = settings_load_display_pages();
  page_ms = settings_load_display_page_ms();
  state = STATE_SPLASH;
  pages_draw_splash(device_name);
  for (uint8_t page = 0; page < CANVAS_PAGES; page++) {
    ssd1306_write_page(page, &canvas_buffer[page * CANVAS_WIDTH]);   // once at boot: blocking is fine
  }

  app_timer_start(&page_timer, DISPLAY_SPLASH_MS, end_splash, NULL, false);
  app_timer_start(&refresh_timer, DISPLAY_REFRESH_MS, refresh, NULL, true);
}

void display_publish_env(const ble_sensor_env_t *env)
{
  data.env = *env;
  data.have_env = true;
}

void display_publish_orientation(const int16_t orientation_deg_x100[3])
{
  memcpy(data.orientation_deg_x100, orientation_deg_x100, sizeof(data.orientation_deg_x100));
  data.have_orientation = true;
}

void display_publish_button(uint32_t press_count)
{
  data.button_presses = press_count;
}

void display_set_connected(bool connected)
{
  data.connected = connected;
}

bool display_is_present(void)
{
  return state != STATE_OFF;
}

void display_set_page_mask(uint16_t page_mask)
{
  data.page_mask = page_mask & DISPLAY_PAGE_ALL;
}

uint16_t display_page_mask(void)
{
  return data.page_mask;
}

void display_set_page_ms(uint16_t new_page_ms)
{
  page_ms = new_page_ms;
  // Restart the rhythm with the new time (during the splash, end_splash does).
  if (splash_done) {
    app_timer_start(&page_timer, page_ms, next_page, NULL, true);
  }
}

uint16_t display_page_ms(void)
{
  return page_ms;
}

#else // DISPLAY_ENABLED

void display_init(const char *device_name)
{
  (void)device_name;
}

void display_publish_env(const ble_sensor_env_t *env)
{
  (void)env;
}

void display_publish_orientation(const int16_t orientation_deg_x100[3])
{
  (void)orientation_deg_x100;
}

void display_publish_button(uint32_t press_count)
{
  (void)press_count;
}

void display_set_connected(bool connected)
{
  (void)connected;
}

bool display_is_present(void)
{
  return false;
}

void display_set_page_mask(uint16_t page_mask)
{
  (void)page_mask;
}

uint16_t display_page_mask(void)
{
  return 0;
}

void display_set_page_ms(uint16_t page_ms)
{
  (void)page_ms;
}

uint16_t display_page_ms(void)
{
  return DEFAULT_DISPLAY_PAGE_MS;
}

#endif // DISPLAY_ENABLED
