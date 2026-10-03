/***************************************************************************//**
 * @file
 * @brief LED0: off, on or blinking, plus a temporary "identify" blink.
 ******************************************************************************/

#include "sl_simple_led_instances.h"
#include "app_timer.h"
#include "app_config.h"
#include "control/led.h"

#define LED (SL_SIMPLE_LED_INSTANCE(0))

static ble_sensor_led_t current = {
  .mode = LED_MODE_OFF,
  .on_ms = DEFAULT_LED_ON_MS,
  .off_ms = DEFAULT_LED_OFF_MS,
};
static bool identifying;      // identify overrides the mode while active
static bool blink_is_lit;
static app_timer_t blink_timer;
static app_timer_t identify_timer;

// Toggles the LED and schedules the next toggle.
static void blink_step(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  uint16_t on_ms = identifying ? IDENTIFY_BLINK_MS : current.on_ms;
  uint16_t off_ms = identifying ? IDENTIFY_BLINK_MS : current.off_ms;

  blink_is_lit = !blink_is_lit;
  if (blink_is_lit) {
    sl_led_turn_on(LED);
  } else {
    sl_led_turn_off(LED);
  }
  app_timer_start(&blink_timer, blink_is_lit ? on_ms : off_ms, blink_step, NULL, false);
}

static void show_current_mode(void)
{
  app_timer_stop(&blink_timer);

  if (identifying || current.mode == LED_MODE_BLINK) {
    blink_is_lit = false;
    blink_step(NULL, NULL);
  } else if (current.mode == LED_MODE_ON) {
    sl_led_turn_on(LED);
  } else {
    sl_led_turn_off(LED);
  }
}

static void identify_finished(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  identifying = false;
  show_current_mode();
}

bool led_is_valid(const ble_sensor_led_t *led)
{
  if (led->mode > LED_MODE_BLINK) {
    return false;
  }
  if (led->mode == LED_MODE_BLINK) {
    return led->on_ms >= LED_BLINK_MIN_MS && led->off_ms >= LED_BLINK_MIN_MS;
  }
  return true;
}

void led_set(const ble_sensor_led_t *led)
{
  current = *led;
  show_current_mode();
}

const ble_sensor_led_t *led_get(void)
{
  return &current;
}

void led_identify(void)
{
  identifying = true;
  show_current_mode();
  app_timer_start(&identify_timer, IDENTIFY_DURATION_MS, identify_finished, NULL, false);
}
