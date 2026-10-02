/***************************************************************************//**
 * @file
 * @brief Thunderboard BG22 sensor access (RHT, light, hall, IMU, sound, supply).
 ******************************************************************************/

#ifndef SENSORS_H
#define SENSORS_H

#include <stdbool.h>
#include <stdint.h>
#include "sl_status.h"
#include "tb_protocol.h"

// Powers and initializes every sensor this board has. Call once at boot.
void sensors_init(void);

// TB_SENSOR_* bits for the sensors that initialized successfully.
uint8_t sensors_available(void);

// Applies a TB_SENSOR_* mask: the IMU and microphone are powered down when
// cleared; the shared-rail I2C sensors simply stop being sampled.
void sensors_set_enabled(uint8_t mask);

// Sets the IMU output data rate from the motion notification period.
void sensors_set_motion_period(uint16_t period_ms);

// Sets the hall alert threshold (TB_HALL_ALERT).
void sensors_set_hall_threshold(uint16_t threshold_ut);

// Samples every enabled environmental sensor. Blocks for the I2C conversions
// (tens of ms); call from main-loop context, never from an ISR.
void sensors_read_env(tb_env_t *env);

// Reads the latest fused IMU sample. Returns false when the IMU is off or no
// new sample is ready.
bool sensors_read_motion(tb_motion_t *motion);

// Gyro bias calibration; the board must be still. Blocks ~1 s.
sl_status_t sensors_calibrate_gyro(void);

// Zeroes the fused orientation angles.
void sensors_reset_orientation(void);

// Must be called from the main loop; drives the microphone level filter.
void sensors_process(void);

#endif // SENSORS_H
