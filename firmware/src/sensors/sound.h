/***************************************************************************//**
 * @file
 * @brief PDM microphone sound level (BRD4184B only).
 *
 * On boards without the microphone component every function is a no-op and
 * sound_is_present() returns false.
 ******************************************************************************/

#ifndef SOUND_H
#define SOUND_H

#include <stdbool.h>

bool sound_is_present(void);

void sound_start(void);
void sound_stop(void);

// Smoothed sound level in dB. Returns false while the microphone is off.
bool sound_level_db(float *level_db);

// Call from the main loop: turns each full audio buffer into a level.
void sound_process(void);

#endif // SOUND_H
