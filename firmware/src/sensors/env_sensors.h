/***************************************************************************//**
 * @file
 * @brief Environmental sensors: temperature/humidity, light, hall, supply.
 *
 * On BRD4184A/B the Si7021, light sensor and Si7210 share one power pin, so
 * they are powered once at boot and stay on; the sensor mask only decides
 * which ones are sampled.
 ******************************************************************************/

#ifndef ENV_SENSORS_H
#define ENV_SENSORS_H

#include <stdint.h>
#include "ble/ble_protocol.h"

// Powers and initializes the sensors. Returns the SENSOR_BIT_* of those that
// answered (RHT, LIGHT, HALL, SUPPLY).
uint8_t env_sensors_init(void);

// Starts conversions for the sensors in mask so the next read has fresh
// values, and forgets old readings of the sensors not in mask.
void env_sensors_start(uint8_t mask);

// Fills the temperature, humidity, light, UV, hall, supply and die
// temperature fields of env for the sensors in mask, and sets their
// ENV_VALID_* bits. Never blocks on a conversion.
void env_sensors_read(ble_sensor_env_t *env, uint8_t mask);

void env_sensors_set_hall_threshold(uint16_t threshold_ut);

#endif // ENV_SENSORS_H
