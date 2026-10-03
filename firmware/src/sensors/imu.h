/***************************************************************************//**
 * @file
 * @brief ICM-20648 inertial sensor: accelerometer, gyroscope, fused orientation.
 *
 * Wraps the SDK's sl_imu driver (which also does the sensor fusion). The IMU
 * has its own power pin, so it is really switched off when not in use.
 ******************************************************************************/

#ifndef IMU_H
#define IMU_H

#include <stdbool.h>
#include <stdint.h>
#include "sl_status.h"
#include "ble/ble_protocol.h"

// Powers the IMU up once to see whether it answers, then off again.
bool imu_probe(void);

void imu_power_on(void);
void imu_power_off(void);
bool imu_is_running(void);

// Output data rate = one sample per period. Restarts a running IMU, because
// the sensor rate and the fusion time step are only set at start.
void imu_set_period(uint16_t period_ms);

// Fills motion with the newest sample. Returns false if the IMU is off or no
// new sample has arrived since the last call.
bool imu_read(ble_sensor_motion_t *motion);

// Measures the gyro bias; the board must be still. Blocks about one second.
sl_status_t imu_calibrate_gyro(void);

// Zeroes roll, pitch and yaw.
void imu_reset_orientation(void);

#endif // IMU_H
