/***************************************************************************//**
 * @file
 * @brief Home Assistant: BTHome v2 broadcasts of the sensor readings.
 *
 * A classic advertisement holds 31 bytes, too few for everything, so the
 * broadcast alternates two packets every HOME_ASSISTANT_PACKET_SWITCH_MS:
 *
 *   A, environment (24 bytes):
 *     flags | service data 0xFCD2: device info,
 *     0x00 packet id, 0x02 temperature, 0x03 humidity, 0x05 illuminance,
 *     0x3A button event, 0x46 UV index
 *   B, orientation (19 bytes, if HOME_ASSISTANT_SEND_ORIENTATION):
 *     flags | service data 0xFCD2: device info,
 *     0x00 packet id, 0x3F rotation X (roll), 0x3F rotation Y (pitch),
 *     0x3F rotation Z (yaw)
 *
 * Each packet is repeated on air every HOME_ASSISTANT_BROADCAST_INTERVAL_MS
 * until the next switch. Home Assistant skips a packet whose packet id equals
 * the previous one, so repeats are harmless, and every switch takes a new id.
 * A button press goes into the next environment packet only, so it fires one
 * event in Home Assistant however many copies arrive.
 *
 * Objects of disabled or absent sensors are left out. Not sent: supply
 * voltage, magnetic field (no BTHome type), sound.
 ******************************************************************************/

#include "app_config.h"
#include "home_assistant/bthome.h"

#if HOME_ASSISTANT_ENABLED

#include <string.h>
#include "sl_bluetooth.h"
#include "app_assert.h"
#include "app_timer.h"

// Advertising data types
#define AD_TYPE_FLAGS                     0x01
#define AD_TYPE_SERVICE_DATA_16BIT_UUID   0x16
#define AD_FLAGS_GENERAL_LE_ONLY          0x06   // required: BlueZ ignores the packet without it

#define BTHOME_UUID                       0xFCD2
#define BTHOME_INFO_V2_UNENCRYPTED        0x40   // bits 5-7: version 2; bit 0: no encryption

// BTHome object ids (https://bthome.io/format/)
#define OBJ_PACKET_ID                     0x00   // uint8
#define OBJ_TEMPERATURE_X100              0x02   // sint16, 0.01 °C
#define OBJ_HUMIDITY_X100                 0x03   // uint16, 0.01 %
#define OBJ_ILLUMINANCE_X100              0x05   // uint24, 0.01 lx
#define OBJ_BUTTON_EVENT                  0x3A   // uint8
#define OBJ_ROTATION_X10                  0x3F   // sint16, 0.1 °
#define OBJ_UV_INDEX_X10                  0x46   // uint8, 0.1

#define BUTTON_EVENT_NONE                 0x00
#define BUTTON_EVENT_PRESS                0x01

#define MAX_LEGACY_ADV_DATA_LEN           31

typedef enum {
  PACKET_ENVIRONMENT,
  PACKET_ORIENTATION,
} packet_kind_t;

static uint8_t advertising_set = 0xFF;
static app_timer_t switch_timer;
static uint8_t packet_id;
static packet_kind_t next_kind = PACKET_ENVIRONMENT;

static ble_sensor_env_t env;
static bool have_env;
static int16_t orientation_deg_x100[3];
static bool have_orientation;
static bool button_press_pending;

// -----------------------------------------------------------------------------
// Packet building: little-endian writers, each returns the position after
// what it wrote.

static uint8_t *put_u8(uint8_t *p, uint8_t v)
{
  *p++ = v;
  return p;
}

static uint8_t *put_u16(uint8_t *p, uint16_t v)
{
  *p++ = (uint8_t)v;
  *p++ = (uint8_t)(v >> 8);
  return p;
}

static uint8_t *put_u24(uint8_t *p, uint32_t v)
{
  p = put_u16(p, (uint16_t)v);
  return put_u8(p, (uint8_t)(v >> 16));
}

static uint8_t *put_environment(uint8_t *p, bool button_pressed)
{
  if (have_env && (env.valid & ENV_VALID_TEMPERATURE)) {
    p = put_u8(p, OBJ_TEMPERATURE_X100);
    p = put_u16(p, (uint16_t)env.temperature_c_x100);
  }
  if (have_env && (env.valid & ENV_VALID_HUMIDITY)) {
    p = put_u8(p, OBJ_HUMIDITY_X100);
    p = put_u16(p, env.humidity_pct_x100);
  }
  if (have_env && (env.valid & ENV_VALID_LUX)) {
    p = put_u8(p, OBJ_ILLUMINANCE_X100);
    p = put_u24(p, env.lux_x100 > 0xFFFFFF ? 0xFFFFFF : env.lux_x100);
  }
  p = put_u8(p, OBJ_BUTTON_EVENT);
  p = put_u8(p, button_pressed ? BUTTON_EVENT_PRESS : BUTTON_EVENT_NONE);
  if (have_env && (env.valid & ENV_VALID_UV)) {
    p = put_u8(p, OBJ_UV_INDEX_X10);
    p = put_u8(p, (uint8_t)(env.uv_index_x100 / 10));
  }
  return p;
}

// X, Y, Z in that order: Home Assistant names them rotation, rotation 2, rotation 3.
static uint8_t *put_orientation(uint8_t *p)
{
  for (int axis = 0; axis < 3; axis++) {
    p = put_u8(p, OBJ_ROTATION_X10);
    p = put_u16(p, (uint16_t)(int16_t)(orientation_deg_x100[axis] / 10));
  }
  return p;
}

static void broadcast(packet_kind_t kind)
{
  uint8_t packet[MAX_LEGACY_ADV_DATA_LEN];
  uint8_t *p = packet;

  p = put_u8(p, 2);
  p = put_u8(p, AD_TYPE_FLAGS);
  p = put_u8(p, AD_FLAGS_GENERAL_LE_ONLY);

  uint8_t *service_data_len = p++;
  p = put_u8(p, AD_TYPE_SERVICE_DATA_16BIT_UUID);
  p = put_u16(p, BTHOME_UUID);
  p = put_u8(p, BTHOME_INFO_V2_UNENCRYPTED);
  p = put_u8(p, OBJ_PACKET_ID);
  p = put_u8(p, ++packet_id);   // new content, new id

  if (kind == PACKET_ENVIRONMENT) {
    p = put_environment(p, button_press_pending);
    button_press_pending = false;
  } else {
    p = put_orientation(p);
  }
  *service_data_len = (uint8_t)(p - service_data_len - 1);

  sl_status_t sc = sl_bt_legacy_advertiser_set_data(advertising_set, sl_bt_advertiser_advertising_data_packet,
                                                    (size_t)(p - packet), packet);
  app_assert_status(sc);
}

// Every HOME_ASSISTANT_PACKET_SWITCH_MS: send the other packet kind.
static void switch_packet(app_timer_t *timer, void *data)
{
  (void)timer;
  (void)data;
  packet_kind_t kind = next_kind;
  if (HOME_ASSISTANT_SEND_ORIENTATION && have_orientation) {
    next_kind = (kind == PACKET_ENVIRONMENT) ? PACKET_ORIENTATION : PACKET_ENVIRONMENT;
  }
  broadcast(kind);
}

// -----------------------------------------------------------------------------
// Broadcast address

// The broadcast gets its own address. Many Bluetooth controllers drop
// repeated reports from one address, so when it shared the board's address
// with the connectable advertising, scanners only ever saw one of the two.
// The address is derived from the board's own one, so it never changes:
// the same bytes with the top two bits set (the mark of a "static random"
// address), e.g. 58:8E:81:66:B0:DF -> D8:8E:81:66:B0:DF.
static void use_own_address(void)
{
  bd_addr identity;
  bd_addr broadcast_address;
  uint8_t identity_type;

  sl_status_t sc = sl_bt_gap_get_identity_address(&identity, &identity_type);
  app_assert_status(sc);
  broadcast_address = identity;
  broadcast_address.addr[5] |= 0xC0;
  if (memcmp(&broadcast_address, &identity, sizeof(identity)) == 0) {
    broadcast_address.addr[4] ^= 0x01;   // identity was already static: still differ
  }
  sc = sl_bt_advertiser_set_random_address(advertising_set, sl_bt_gap_static_address, broadcast_address, &broadcast_address);
  app_assert_status(sc);
}

// -----------------------------------------------------------------------------
// Public interface

void bthome_init(void)
{
  uint32_t interval = (uint32_t)HOME_ASSISTANT_BROADCAST_INTERVAL_MS * 1000u / 625u;   // in units of 0.625 ms

  sl_status_t sc = sl_bt_advertiser_create_set(&advertising_set);
  app_assert_status(sc);
  use_own_address();
  sc = sl_bt_advertiser_set_timing(advertising_set, interval, interval, 0, 0);
  app_assert_status(sc);
  broadcast(PACKET_ENVIRONMENT);
  bthome_resume();
  app_timer_start(&switch_timer, HOME_ASSISTANT_PACKET_SWITCH_MS, switch_packet, NULL, true);
}

void bthome_publish_env(const ble_sensor_env_t *new_env)
{
  env = *new_env;
  have_env = true;
}

void bthome_publish_orientation(const int16_t new_orientation_deg_x100[3])
{
  memcpy(orientation_deg_x100, new_orientation_deg_x100, sizeof(orientation_deg_x100));
  have_orientation = true;
}

void bthome_button_pressed(void)
{
  // Send it right away instead of waiting for the next switch.
  button_press_pending = true;
  broadcast(PACKET_ENVIRONMENT);
  next_kind = PACKET_ORIENTATION;
  app_timer_start(&switch_timer, HOME_ASSISTANT_PACKET_SWITCH_MS, switch_packet, NULL, true);
}

void bthome_pause(void)
{
  (void)sl_bt_advertiser_stop(advertising_set);
}

void bthome_resume(void)
{
  sl_status_t sc = sl_bt_legacy_advertiser_start(advertising_set, sl_bt_legacy_advertiser_non_connectable);
  app_assert_status(sc);
}

#else // HOME_ASSISTANT_ENABLED

void bthome_init(void)
{
}

void bthome_publish_env(const ble_sensor_env_t *env)
{
  (void)env;
}

void bthome_publish_orientation(const int16_t orientation_deg_x100[3])
{
  (void)orientation_deg_x100;
}

void bthome_button_pressed(void)
{
}

void bthome_pause(void)
{
}

void bthome_resume(void)
{
}

#endif // HOME_ASSISTANT_ENABLED
