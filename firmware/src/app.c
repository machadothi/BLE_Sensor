/***************************************************************************//**
 * @file
 * @brief Thunderboard BG22 BLE sensor / control application.
 *
 * Exposes every on-board sensor over the custom "TB Game" GATT service, lets
 * the client drive the LED, and stores configuration (sample periods, sensor
 * mask, TX power, advertising interval, hall threshold, device name) in NVM3.
 * Protocol details: tb_protocol.h.
 ******************************************************************************/

#include <stdbool.h>
#include <string.h>
#include "sl_bluetooth.h"
#include "sl_component_catalog.h"
#include "sl_main_init.h"
#include "sl_simple_button_instances.h"
#include "sl_simple_led_instances.h"
#include "app_assert.h"
#include "app_log.h"
#include "app_timer.h"
#include "gatt_db.h"
#include "sensors.h"
#include "settings.h"
#include "tb_protocol.h"

#define SIGNAL_BUTTON           (1u << 0)
#define NO_CONNECTION           0xFF
#define IDENTIFY_DURATION_MS    3000
#define IDENTIFY_BLINK_MS       100
#define REBOOT_DELAY_MS         200
#define LED_BLINK_MIN_MS        10
#define ADV_COMPANY_ID          0x02FF  // Silicon Laboratories (Bluetooth SIG)
// The IMU produces one sample per motion period; poll at twice that rate so
// timer jitter never skips a sample (only new samples are sent).
#define MOTION_POLL_MS(period)  ((period) / 2u < 5u ? 5u : (period) / 2u)
#define ENV_FIRST_SAMPLE_MS     250
#define BUTTON_DEBOUNCE_MS      25

// ATT error codes
#define ATT_ERR_INVALID_OFFSET  0x07
#define ATT_ERR_INVALID_LENGTH  0x0D
#define ATT_ERR_VALUE_NOT_ALLOWED 0x13

// Requested connection interval: 7.5-15 ms so 100 Hz motion data fits.
#define CONN_INTERVAL_MIN       6     // x 1.25 ms
#define CONN_INTERVAL_MAX       12    // x 1.25 ms
#define CONN_TIMEOUT            200   // x 10 ms

static tb_config_t config;
static char device_name[SETTINGS_NAME_MAX_LEN + 1];
static tb_led_t led = { .mode = TB_LED_OFF, .on_ms = 500, .off_ms = 500 };
static tb_button_t button;

static uint8_t advertising_set = 0xFF;
static uint8_t connection = NO_CONNECTION;
static uint16_t att_mtu = 23;
static bool notify_env;
static bool notify_motion;
static bool notify_button;

static app_timer_t env_timer;
static app_timer_t motion_timer;
static app_timer_t led_timer;
static app_timer_t identify_timer;
static app_timer_t reboot_timer;
static app_timer_t button_timer;
static bool led_blink_phase_on;
static bool identifying;

// -----------------------------------------------------------------------------
// LED

static void led_apply(void);

static void led_timer_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  uint16_t on_ms = identifying ? IDENTIFY_BLINK_MS : led.on_ms;
  uint16_t off_ms = identifying ? IDENTIFY_BLINK_MS : led.off_ms;

  led_blink_phase_on = !led_blink_phase_on;
  if (led_blink_phase_on) {
    sl_led_turn_on(SL_SIMPLE_LED_INSTANCE(0));
  } else {
    sl_led_turn_off(SL_SIMPLE_LED_INSTANCE(0));
  }
  app_timer_start(&led_timer, led_blink_phase_on ? on_ms : off_ms, led_timer_cb, NULL, false);
}

static void led_apply(void)
{
  app_timer_stop(&led_timer);
  if (identifying || led.mode == TB_LED_BLINK) {
    led_blink_phase_on = false;
    led_timer_cb(NULL, NULL);
  } else if (led.mode == TB_LED_ON) {
    sl_led_turn_on(SL_SIMPLE_LED_INSTANCE(0));
  } else {
    sl_led_turn_off(SL_SIMPLE_LED_INSTANCE(0));
  }
}

static void identify_done_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  identifying = false;
  led_apply();
}

// -----------------------------------------------------------------------------
// Advertising

static uint8_t board_id(void)
{
#if defined(SL_CATALOG_SENSOR_LUX_PRESENT)
  return TB_BOARD_BRD4184B;
#else // SL_CATALOG_SENSOR_LUX_PRESENT
  return TB_BOARD_BRD4184A;
#endif // SL_CATALOG_SENSOR_LUX_PRESENT
}

static void advertising_start(void)
{
  sl_status_t sc;
  int16_t set_min, set_max;
  uint32_t interval = (uint32_t)config.adv_interval_ms * 1000u / 625u; // 0.625 ms units

  // Advertising packet: flags, the 128-bit TB Game service UUID and
  // manufacturer data; the device name goes in the scan response.
  // BlueZ suppresses repeated advertisements from known devices unless they
  // carry manufacturer/service data, so the manufacturer data also keeps
  // Linux scanners reliably seeing the board.
  static const uint8_t service_uuid[16] = {
    0x47, 0x1d, 0x2e, 0x0f, 0x8c, 0x6b, 0x3e, 0x9d,
    0x1a, 0x4f, 0x2b, 0x5c, 0x00, 0x00, 0xe4, 0xa7
  };
  uint8_t adv[3 + 2 + sizeof(service_uuid) + 6];
  uint8_t scan_rsp[2 + SETTINGS_NAME_MAX_LEN];
  size_t name_len = strlen(device_name);
  uint8_t *p = adv;

  *p++ = 2;
  *p++ = 0x01;                  // Flags
  *p++ = 0x06;                  // LE General Discoverable, BR/EDR not supported
  *p++ = 1 + sizeof(service_uuid);
  *p++ = 0x07;                  // Complete list of 128-bit service UUIDs
  memcpy(p, service_uuid, sizeof(service_uuid));
  p += sizeof(service_uuid);
  *p++ = 5;
  *p++ = 0xFF;                  // Manufacturer specific data
  *p++ = (uint8_t)(ADV_COMPANY_ID & 0xFF);
  *p++ = (uint8_t)(ADV_COMPANY_ID >> 8);
  *p++ = board_id();
  *p++ = TB_PROTOCOL_VERSION;

  scan_rsp[0] = (uint8_t)(1 + name_len);
  scan_rsp[1] = 0x09;           // Complete local name
  memcpy(&scan_rsp[2], device_name, name_len);

  // TX power can only change while the radio is idle, i.e. right here.
  sc = sl_bt_system_set_tx_power(TB_TX_POWER_MIN_DBM_X10, config.tx_power_dbm_x10, &set_min, &set_max);
  if (sc == SL_STATUS_OK) {
    app_log_info("TX power max %s%d.%d dBm" APP_LOG_NL, set_max < 0 ? "-" : "",
                 (set_max < 0 ? -set_max : set_max) / 10, (set_max < 0 ? -set_max : set_max) % 10);
  } else {
    app_log_status_error_f(sc, "Setting TX power failed" APP_LOG_NL);
  }

  sc = sl_bt_advertiser_set_timing(advertising_set, interval, interval, 0, 0);
  app_assert_status(sc);
  sc = sl_bt_legacy_advertiser_set_data(advertising_set, sl_bt_advertiser_advertising_data_packet, sizeof(adv), adv);
  app_assert_status(sc);
  sc = sl_bt_legacy_advertiser_set_data(advertising_set, sl_bt_advertiser_scan_response_packet, 2 + name_len, scan_rsp);
  app_assert_status(sc);
  sc = sl_bt_legacy_advertiser_start(advertising_set, sl_bt_legacy_advertiser_connectable);
  app_assert_status(sc);
  app_log_info("Advertising as \"%s\"" APP_LOG_NL, device_name);
}

// -----------------------------------------------------------------------------
// Sensor sampling

static void notify(uint16_t characteristic, bool enabled, const void *value, size_t len)
{
  sl_bt_gatt_server_write_attribute_value(characteristic, 0, len, value);
  if (enabled && connection != NO_CONNECTION && len <= (size_t)(att_mtu - 3)) {
    // Drop the sample if the stack's TX queue is full; the next one follows soon.
    (void)sl_bt_gatt_server_send_notification(connection, characteristic, len, value);
  }
}

static void env_timer_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  tb_env_t env;
  sensors_read_env(&env);
  notify(gattdb_tb_env, notify_env, &env, sizeof(env));
}

static void motion_timer_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  tb_motion_t motion;
  if (sensors_read_motion(&motion)) {
    notify(gattdb_tb_motion, notify_motion, &motion, sizeof(motion));
  }
}

// First env sample shortly after connecting, once the slowest conversion
// (Si1133, ~220 ms) has finished; then switch to the configured period.
static void env_first_cb(app_timer_t *timer, void *data)
{
  env_timer_cb(timer, data);
  app_timer_start(&env_timer, config.env_period_ms, env_timer_cb, NULL, true);
}

static void sampling_start(void)
{
  sensors_set_enabled(config.sensor_mask);
  app_timer_start(&env_timer, ENV_FIRST_SAMPLE_MS, env_first_cb, NULL, false);
  app_timer_start(&motion_timer, MOTION_POLL_MS(config.motion_period_ms), motion_timer_cb, NULL, true);
}

static void sampling_stop(void)
{
  app_timer_stop(&env_timer);
  app_timer_stop(&motion_timer);
  sensors_set_enabled(0);
}

// -----------------------------------------------------------------------------
// Configuration

static void config_apply(const tb_config_t *previous)
{
  sensors_set_motion_period(config.motion_period_ms);
  sensors_set_hall_threshold(config.hall_threshold_ut);

  if (connection == NO_CONNECTION) {
    return;   // sampling starts on the next connection with these values
  }
  sensors_set_enabled(config.sensor_mask);
  if (previous == NULL || previous->env_period_ms != config.env_period_ms) {
    app_timer_start(&env_timer, config.env_period_ms, env_timer_cb, NULL, true);
  }
  if (previous == NULL || previous->motion_period_ms != config.motion_period_ms) {
    app_timer_start(&motion_timer, MOTION_POLL_MS(config.motion_period_ms), motion_timer_cb, NULL, true);
  }
}

static void device_name_publish(void)
{
  sl_status_t sc = sl_bt_gatt_server_write_attribute_value(gattdb_device_name, 0, strlen(device_name),
                                                           (const uint8_t *)device_name);
  app_assert_status(sc);
}

static void device_name_set(const uint8_t *name, size_t len)
{
  memcpy(device_name, name, len);
  device_name[len] = '\0';
  settings_save_name(device_name);
  app_log_info("Device name set to \"%s\" (advertised after disconnect)" APP_LOG_NL, device_name);
}

// Called when a client wrote the GAP Device Name characteristic directly.
static void gap_device_name_written(void)
{
  size_t len = 0;
  uint8_t name[SETTINGS_NAME_MAX_LEN];

  if (sl_bt_gatt_server_read_attribute_value(gattdb_device_name, 0, sizeof(name), &len, name) == SL_STATUS_OK
      && len > 0) {
    device_name_set(name, len);
  }
}

// -----------------------------------------------------------------------------
// Commands

static void reboot_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  sl_bt_system_reboot();
}

static uint8_t command_run(uint8_t command)
{
  switch (command) {
    case TB_CMD_CALIBRATE_GYRO: {
      sl_status_t sc = sensors_calibrate_gyro();
      app_log_info("Gyro calibration: 0x%04lX" APP_LOG_NL, (unsigned long)sc);
      return 0;
    }
    case TB_CMD_FACTORY_RESET: {
      tb_config_t previous = config;
      settings_erase();
      settings_config_defaults(&config);
      settings_name_default(device_name, sizeof(device_name));
      device_name_publish();
      config_apply(&previous);
      app_log_info("Factory reset" APP_LOG_NL);
      return 0;
    }
    case TB_CMD_REBOOT:
      // Delay so the write response reaches the client first.
      app_timer_start(&reboot_timer, REBOOT_DELAY_MS, reboot_cb, NULL, false);
      return 0;
    case TB_CMD_IDENTIFY:
      identifying = true;
      led_apply();
      app_timer_start(&identify_timer, IDENTIFY_DURATION_MS, identify_done_cb, NULL, false);
      return 0;
    case TB_CMD_RESET_ORIENT:
      sensors_reset_orientation();
      return 0;
    default:
      return ATT_ERR_VALUE_NOT_ALLOWED;
  }
}

// -----------------------------------------------------------------------------
// GATT user characteristics

static void handle_user_read(sl_bt_evt_gatt_server_user_read_request_t *req)
{
  const void *value = NULL;
  size_t len = 0;
  tb_info_t info;
  uint8_t error = 0;

  switch (req->characteristic) {
    case gattdb_tb_led:
      value = &led;
      len = sizeof(led);
      break;
    case gattdb_tb_config:
      value = &config;
      len = sizeof(config);
      break;
    case gattdb_tb_info:
      info.board = board_id();
      info.protocol_version = TB_PROTOCOL_VERSION;
      info.available_mask = sensors_available();
      info.reserved = 0;
      value = &info;
      len = sizeof(info);
      break;
    case gattdb_tb_name:
      value = device_name;
      len = strlen(device_name);
      break;
    default:
      error = ATT_ERR_VALUE_NOT_ALLOWED;
      break;
  }

  if (error == 0 && req->offset > len) {
    error = ATT_ERR_INVALID_OFFSET;
  }
  if (error != 0) {
    sl_bt_gatt_server_send_user_read_response(req->connection, req->characteristic, error, 0, NULL, NULL);
  } else {
    sl_bt_gatt_server_send_user_read_response(req->connection, req->characteristic, 0, len - req->offset,
                                              (const uint8_t *)value + req->offset, NULL);
  }
}

static void handle_user_write(sl_bt_evt_gatt_server_user_write_request_t *req)
{
  uint8_t error = 0;
  const uint8_t *data = req->value.data;
  size_t len = req->value.len;

  if (req->offset != 0) {
    error = ATT_ERR_INVALID_OFFSET;
  } else {
    switch (req->characteristic) {
      case gattdb_tb_led: {
        tb_led_t next;
        if (len != sizeof(next)) {
          error = ATT_ERR_INVALID_LENGTH;
          break;
        }
        memcpy(&next, data, sizeof(next));
        if (next.mode > TB_LED_BLINK
            || (next.mode == TB_LED_BLINK && (next.on_ms < LED_BLINK_MIN_MS || next.off_ms < LED_BLINK_MIN_MS))) {
          error = ATT_ERR_VALUE_NOT_ALLOWED;
          break;
        }
        led = next;
        led_apply();
        break;
      }
      case gattdb_tb_config: {
        tb_config_t next;
        tb_config_t previous = config;
        if (len != sizeof(next)) {
          error = ATT_ERR_INVALID_LENGTH;
          break;
        }
        memcpy(&next, data, sizeof(next));
        next.version = TB_PROTOCOL_VERSION;
        if (!settings_config_is_valid(&next)) {
          error = ATT_ERR_VALUE_NOT_ALLOWED;
          break;
        }
        config = next;
        settings_save_config(&config);
        config_apply(&previous);
        app_log_info("Config updated" APP_LOG_NL);
        break;
      }
      case gattdb_tb_command:
        if (len != 1) {
          error = ATT_ERR_INVALID_LENGTH;
          break;
        }
        // Respond before running: calibration blocks for about a second.
        sl_bt_gatt_server_send_user_write_response(req->connection, req->characteristic,
                                                   data[0] >= TB_CMD_CALIBRATE_GYRO && data[0] <= TB_CMD_RESET_ORIENT
                                                   ? 0 : ATT_ERR_VALUE_NOT_ALLOWED);
        command_run(data[0]);
        return;
      case gattdb_tb_name:
        if (len == 0 || len > SETTINGS_NAME_MAX_LEN) {
          error = ATT_ERR_INVALID_LENGTH;
          break;
        }
        device_name_set(data, len);
        device_name_publish();
        break;
      default:
        error = ATT_ERR_VALUE_NOT_ALLOWED;
        break;
    }
  }

  if (req->att_opcode == sl_bt_gatt_write_request) {
    sl_bt_gatt_server_send_user_write_response(req->connection, req->characteristic, error);
  }
}

static void handle_characteristic_status(sl_bt_evt_gatt_server_characteristic_status_t *st)
{
  bool enabled = (st->client_config_flags & sl_bt_gatt_server_notification) != 0;

  if (st->status_flags != sl_bt_gatt_server_client_config) {
    return;
  }
  switch (st->characteristic) {
    case gattdb_tb_env:
      notify_env = enabled;
      break;
    case gattdb_tb_motion:
      notify_motion = enabled;
      break;
    case gattdb_tb_button:
      notify_button = enabled;
      break;
    default:
      break;
  }
}

// -----------------------------------------------------------------------------
// Button (ISR context: only signal the stack, handle it in sl_bt_on_event)

void sl_button_on_change(const sl_button_t *handle)
{
  (void)handle;
  sl_bt_external_signal(SIGNAL_BUTTON);
}

static void button_update(void)
{
  uint8_t pressed = sl_button_get_state(SL_SIMPLE_BUTTON_INSTANCE(0)) == SL_SIMPLE_BUTTON_PRESSED;
  if (pressed && !button.pressed) {
    button.press_count++;
  }
  button.pressed = pressed;
  notify(gattdb_tb_button, notify_button, &button, sizeof(button));
}

// Runs once the contacts have been quiet for BUTTON_DEBOUNCE_MS.
static void button_debounce_cb(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  uint8_t pressed = sl_button_get_state(SL_SIMPLE_BUTTON_INSTANCE(0)) == SL_SIMPLE_BUTTON_PRESSED;
  if (pressed != button.pressed) {
    button_update();
  }
}

// -----------------------------------------------------------------------------
// Application hooks

void app_init(void)
{
  app_log_info("TB Game firmware, protocol v%d" APP_LOG_NL, TB_PROTOCOL_VERSION);
  sensors_init();
}

void app_process_action(void)
{
  sensors_process();
}

void sl_bt_on_event(sl_bt_msg_t *evt)
{
  sl_status_t sc;

  switch (SL_BT_MSG_ID(evt->header)) {
    case sl_bt_evt_system_boot_id:
      settings_load(&config, device_name, sizeof(device_name));
      device_name_publish();
      config_apply(NULL);
      button_update();
      sc = sl_bt_advertiser_create_set(&advertising_set);
      app_assert_status(sc);
      advertising_start();
      break;

    case sl_bt_evt_connection_opened_id:
      connection = evt->data.evt_connection_opened.connection;
      att_mtu = 23;
      app_log_info("Connected" APP_LOG_NL);
      sl_bt_connection_set_parameters(connection, CONN_INTERVAL_MIN, CONN_INTERVAL_MAX, 0, CONN_TIMEOUT, 0, 0xFFFF);
      sampling_start();
      break;

    case sl_bt_evt_connection_closed_id:
      app_log_info("Disconnected (reason 0x%04X)" APP_LOG_NL, evt->data.evt_connection_closed.reason);
      connection = NO_CONNECTION;
      notify_env = notify_motion = notify_button = false;
      sampling_stop();
      advertising_start();
      break;

    case sl_bt_evt_gatt_mtu_exchanged_id:
      att_mtu = evt->data.evt_gatt_mtu_exchanged.mtu;
      app_log_info("ATT MTU %u" APP_LOG_NL, att_mtu);
      break;

    case sl_bt_evt_gatt_server_characteristic_status_id:
      handle_characteristic_status(&evt->data.evt_gatt_server_characteristic_status);
      break;

    case sl_bt_evt_gatt_server_user_read_request_id:
      handle_user_read(&evt->data.evt_gatt_server_user_read_request);
      break;

    case sl_bt_evt_gatt_server_user_write_request_id:
      handle_user_write(&evt->data.evt_gatt_server_user_write_request);
      break;

    case sl_bt_evt_gatt_server_attribute_value_id:
      if (evt->data.evt_gatt_server_attribute_value.attribute == gattdb_device_name) {
        gap_device_name_written();
      }
      break;

    case sl_bt_evt_system_external_signal_id:
      if (evt->data.evt_system_external_signal.extsignals & SIGNAL_BUTTON) {
        // Every edge restarts the timer, so contact bounce collapses into one event.
        app_timer_start(&button_timer, BUTTON_DEBOUNCE_MS, button_debounce_cb, NULL, false);
      }
      break;

    default:
      break;
  }
}
