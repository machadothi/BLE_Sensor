/***************************************************************************//**
 * @file
 * @brief One-shot commands written to the Command characteristic.
 ******************************************************************************/

#ifndef COMMANDS_H
#define COMMANDS_H

#include <stdbool.h>
#include <stdint.h>

// True for every COMMAND_* code in ble_protocol.h.
bool commands_is_known(uint8_t code);

// Runs a command. Some block briefly (gyro calibration ~1 s), so answer the
// client's write before calling this.
void commands_run(uint8_t code);

#endif // COMMANDS_H
