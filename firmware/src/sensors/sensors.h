/***************************************************************************//**
 * @file
 * @brief All on-board sensors behind one interface.
 *
 * The rest of the firmware only talks to this module. It knows which sensors
 * the board has and which are enabled, and delegates to env_sensors, imu and
 * sound.
 ******************************************************************************/

#ifndef SENSORS_H
#define SENSORS_H

#include <stdbool.h>
#include <stdint.h>
#include "sl_status.h"
#include "ble/ble_protocol.h"

// Probes every sensor. Call once at boot; all sensors start disabled.
void sensors_init(void);

// SENSOR_BIT_* of the sensors this board has.
uint8_t sensors_available(void);

// BOARD_ID_* of the board revision this firmware was built for.
uint8_t sensors_board_id(void);

// Enables exactly the sensors in mask (limited to the available ones).
// The IMU and microphone are powered down when disabled.
void sensors_set_enabled(uint8_t mask);

void sensors_set_motion_period(uint16_t period_ms);
void sensors_set_hall_threshold(uint16_t threshold_ut);

// Builds an Env packet from every enabled sensor. Fields of disabled or not
// yet ready sensors are 0 and their ENV_VALID_* bit is clear.
void sensors_read_env(ble_sensor_env_t *env);

// Returns false if the IMU is disabled or has no new sample.
bool sensors_read_motion(ble_sensor_motion_t *motion);

sl_status_t sensors_calibrate_gyro(void);
void sensors_reset_orientation(void);

// Call from the main loop (drives the microphone level).
void sensors_process(void);

#endif // SENSORS_H
