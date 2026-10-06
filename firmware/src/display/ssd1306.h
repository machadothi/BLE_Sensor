/***************************************************************************//**
 * @file
 * @brief SSD1306 128x64 OLED controller over I2C.
 *
 * The display shares the board's sensor I2C bus (EXP pin 15 = SCL / PD3,
 * pin 16 = SDA / PD2). All calls block on the bus; ssd1306_write_page()
 * sends one eighth of the screen (~12 ms at 100 kHz) so callers can spread
 * a full update over several main-loop turns.
 ******************************************************************************/

#ifndef SSD1306_H
#define SSD1306_H

#include <stdbool.h>
#include <stdint.h>

// Looks for the display at 0x3C, then 0x3D, and configures it.
// Returns false if nothing answers (no display connected).
bool ssd1306_init(void);

// Sends one page (8 pixel rows, 128 columns; one byte per column, bit 0 on top).
void ssd1306_write_page(uint8_t page, const uint8_t *columns);

void ssd1306_set_contrast(uint8_t contrast);

#endif // SSD1306_H
