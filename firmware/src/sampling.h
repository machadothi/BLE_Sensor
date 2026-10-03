/***************************************************************************//**
 * @file
 * @brief Periodic sensor sampling while a client is connected.
 *
 * Two timers: one reads the environmental sensors every env period, the other
 * polls the IMU at half the motion period and publishes each new sample.
 ******************************************************************************/

#ifndef SAMPLING_H
#define SAMPLING_H

#include "ble/ble_protocol.h"

// Enables the configured sensors and starts both timers.
void sampling_start(void);

// Stops both timers and switches every sensor off.
void sampling_stop(void);

// Restarts the timers whose period differs from previous (NULL = both).
// Only call while sampling is running.
void sampling_update_periods(const ble_sensor_config_t *previous);

#endif // SAMPLING_H
