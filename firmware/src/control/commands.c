/***************************************************************************//**
 * @file
 * @brief One-shot commands written to the Command characteristic.
 ******************************************************************************/

#include "sl_bluetooth.h"
#include "app_log.h"
#include "app_timer.h"
#include "app.h"
#include "app_config.h"
#include "ble/device_name.h"
#include "control/led.h"
#include "display/display.h"
#include "sensors/sensors.h"
#include "storage/settings.h"
#include "control/commands.h"

static app_timer_t reboot_timer;

static void reboot_now(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  sl_bt_system_reboot();
}

static void factory_reset(void)
{
  ble_sensor_config_t previous = *settings_config();
  settings_factory_reset();
  device_name_reset();
  display_set_page_mask(DEFAULT_DISPLAY_PAGES);
  display_set_page_ms(DEFAULT_DISPLAY_PAGE_MS);
  app_apply_config(&previous);
  app_log_info("Factory reset" APP_LOG_NL);
}

bool commands_is_known(uint8_t code)
{
  return code >= COMMAND_CALIBRATE_GYRO && code <= COMMAND_RESET_ORIENTATION;
}

void commands_run(uint8_t code)
{
  switch (code) {
    case COMMAND_CALIBRATE_GYRO:
      app_log_info("Gyro calibration: 0x%04lX" APP_LOG_NL, (unsigned long)sensors_calibrate_gyro());
      break;
    case COMMAND_FACTORY_RESET:
      factory_reset();
      break;
    case COMMAND_REBOOT:
      // Not immediate: the write response must reach the client first.
      app_timer_start(&reboot_timer, REBOOT_DELAY_MS, reboot_now, NULL, false);
      break;
    case COMMAND_IDENTIFY:
      led_identify();
      break;
    case COMMAND_RESET_ORIENTATION:
      sensors_reset_orientation();
      break;
    default:
      break;
  }
}
