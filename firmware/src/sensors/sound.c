/***************************************************************************//**
 * @file
 * @brief PDM microphone sound level (BRD4184B only).
 ******************************************************************************/

#include "sl_component_catalog.h"
#include "sensors/sound.h"

#ifdef SL_CATALOG_MIC_DRIVER_PRESENT

#include "sl_board_control.h"
#include "sl_mic.h"
#include "sl_sleeptimer.h"
#include "app_log.h"
#include "app_config.h"

static int16_t audio_buffer[MIC_BUFFER_FRAMES];
static float smoothed_level_db;
static bool running;

bool sound_is_present(void)
{
  return true;
}

void sound_start(void)
{
  sl_board_enable_sensor(SL_BOARD_SENSOR_MICROPHONE);
  sl_sleeptimer_delay_millisecond(MIC_POWER_UP_MS);

  if (sl_mic_init(MIC_SAMPLE_RATE_HZ, 1) != SL_STATUS_OK) {
    app_log_warning("Microphone init failed" APP_LOG_NL);
    sl_board_disable_sensor(SL_BOARD_SENSOR_MICROPHONE);
    return;
  }
  sl_mic_get_n_samples(audio_buffer, MIC_BUFFER_FRAMES);
  running = true;
}

void sound_stop(void)
{
  if (running) {
    sl_mic_deinit();
    running = false;
  }
  sl_board_disable_sensor(SL_BOARD_SENSOR_MICROPHONE);
}

bool sound_level_db(float *level_db)
{
  *level_db = smoothed_level_db;
  return running;
}

void sound_process(void)
{
  float level_db;

  if (!running || !sl_mic_sample_buffer_ready()) {
    return;
  }
  if (sl_mic_calculate_sound_level(&level_db, audio_buffer, MIC_BUFFER_FRAMES, 0) == SL_STATUS_OK) {
    smoothed_level_db = MIC_LEVEL_SMOOTHING * level_db
                        + (1.0f - MIC_LEVEL_SMOOTHING) * smoothed_level_db;
  }
  sl_mic_get_n_samples(audio_buffer, MIC_BUFFER_FRAMES);   // start the next buffer
}

#else // SL_CATALOG_MIC_DRIVER_PRESENT

bool sound_is_present(void)
{
  return false;
}

void sound_start(void)
{
}

void sound_stop(void)
{
}

bool sound_level_db(float *level_db)
{
  *level_db = 0.0f;
  return false;
}

void sound_process(void)
{
}

#endif // SL_CATALOG_MIC_DRIVER_PRESENT
