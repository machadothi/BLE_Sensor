/***************************************************************************//**
 * @file
 * @brief BTN0: debounced state and press counter, published over BLE.
 ******************************************************************************/

#include "sl_bluetooth.h"
#include "sl_simple_button_instances.h"
#include "app_timer.h"
#include "app_config.h"
#include "ble/gatt_service.h"
#include "control/button.h"

#define BUTTON (SL_SIMPLE_BUTTON_INSTANCE(0))

static ble_sensor_button_t state;
static app_timer_t debounce_timer;

static bool is_pressed_now(void)
{
  return sl_button_get_state(BUTTON) == SL_SIMPLE_BUTTON_PRESSED;
}

static void publish_state(bool pressed)
{
  if (pressed && !state.pressed) {
    state.press_count++;
  }
  state.pressed = pressed;
  gatt_service_publish_button(&state);
}

// Runs once the contacts have been quiet for BUTTON_DEBOUNCE_MS.
static void debounce_finished(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  bool pressed = is_pressed_now();
  if (pressed != state.pressed) {
    publish_state(pressed);
  }
}

// GPIO interrupt context: overrides the SDK's weak handler.
void sl_button_on_change(const sl_button_t *handle)
{
  (void)handle;
  sl_bt_external_signal(BUTTON_SIGNAL);
}

void button_init(void)
{
  publish_state(is_pressed_now());
}

void button_on_signal(void)
{
  // Every edge restarts the timer, so a burst of bounces becomes one event.
  app_timer_start(&debounce_timer, BUTTON_DEBOUNCE_MS, debounce_finished, NULL, false);
}
