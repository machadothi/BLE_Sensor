/***************************************************************************//**
 * @file
 * @brief Drawing into a 128x64 one-bit frame buffer.
 *
 * The buffer uses the SSD1306's own memory layout (8 horizontal "pages" of
 * 8 rows; one byte = one column of 8 pixels, bit 0 at the top), so it can be
 * sent to the display without conversion.
 ******************************************************************************/

#ifndef CANVAS_H
#define CANVAS_H

#include <stdbool.h>
#include <stdint.h>

#define CANVAS_WIDTH    128
#define CANVAS_HEIGHT   64
#define CANVAS_PAGES    (CANVAS_HEIGHT / 8)

// Glyph and icon bitmaps: row by row, rows padded to whole bytes, MSB = left.
typedef struct {
  uint8_t code;       // character (0x7F = degree sign)
  uint8_t width;      // advance width in pixels
  uint16_t offset;    // into the font's bitmap array
} canvas_glyph_t;

typedef struct {
  uint8_t height;
  uint8_t glyph_count;
  const canvas_glyph_t *glyphs;
  const uint8_t *bitmaps;
} canvas_font_t;

typedef struct {
  uint8_t width;
  uint8_t height;
  const uint8_t *bits;
} canvas_bitmap_t;

typedef enum {
  CANVAS_ALIGN_LEFT,
  CANVAS_ALIGN_CENTER,
  CANVAS_ALIGN_RIGHT,
} canvas_align_t;

// The frame buffer, in SSD1306 page layout (page * 128 + column).
extern uint8_t canvas_buffer[CANVAS_PAGES * CANVAS_WIDTH];

void canvas_clear(void);
void canvas_pixel(int x, int y, bool on);
void canvas_hline(int x, int y, int length);
void canvas_fill_rect(int x, int y, int width, int height, bool on);
void canvas_circle(int cx, int cy, int radius, bool filled);
void canvas_bitmap(int x, int y, const canvas_bitmap_t *bitmap);

// Width in pixels of text drawn with font.
int canvas_text_width(const canvas_font_t *font, const char *text);

// Draws text with its top edge at y; x is the left edge, centre or right
// edge depending on align. Returns the width drawn.
int canvas_text(const canvas_font_t *font, int x, int y, const char *text, canvas_align_t align);

#endif // CANVAS_H
