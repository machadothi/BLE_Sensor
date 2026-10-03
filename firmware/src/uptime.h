/***************************************************************************//**
 * @file
 * @brief Milliseconds since boot, used to timestamp packets.
 ******************************************************************************/

#ifndef UPTIME_H
#define UPTIME_H

#include <stdint.h>
#include "sl_sleeptimer.h"

// Wraps after ~49.7 days; packet timestamps are 32-bit anyway.
static inline uint32_t uptime_ms(void)
{
  uint64_t ms = 0;
  sl_sleeptimer_tick64_to_ms(sl_sleeptimer_get_tick_count64(), &ms);
  return (uint32_t)ms;
}

#endif // UPTIME_H
