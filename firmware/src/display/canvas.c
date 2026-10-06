/***************************************************************************//**
 * @file
 * @brief Drawing into a 128x64 one-bit frame buffer.
 ******************************************************************************/

#include <string.h>
#include "display/canvas.h"

uint8_t canvas_buffer[CANVAS_PAGES * CANVAS_WIDTH];

void canvas_clear(void)
{
  memset(canvas_buffer, 0, sizeof(canvas_buffer));
}

void canvas_pixel(int x, int y, bool on)
{
  if (x < 0 || x >= CANVAS_WIDTH || y < 0 || y >= CANVAS_HEIGHT) {
    return;   // clipping: callers may draw partly off screen
  }
  uint8_t *byte = &canvas_buffer[(y / 8) * CANVAS_WIDTH + x];
  uint8_t bit = (uint8_t)(1u << (y % 8));
  *byte = on ? (uint8_t)(*byte | bit) : (uint8_t)(*byte & ~bit);
}

void canvas_hline(int x, int y, int length)
{
  for (int i = 0; i < length; i++) {
    canvas_pixel(x + i, y, true);
  }
}

void canvas_fill_rect(int x, int y, int width, int height, bool on)
{
  for (int row = 0; row < height; row++) {
    for (int col = 0; col < width; col++) {
      canvas_pixel(x + col, y + row, on);
    }
  }
}

void canvas_circle(int cx, int cy, int radius, bool filled)
{
  for (int dy = -radius; dy <= radius; dy++) {
    for (int dx = -radius; dx <= radius; dx++) {
      int d2 = dx * dx + dy * dy;
      bool inside = d2 <= radius * radius + radius;
      bool edge = d2 >= (radius - 1) * (radius - 1) + (radius - 1);
      if (inside && (filled || edge)) {
        canvas_pixel(cx + dx, cy + dy, true);
      }
    }
  }
}

// Draws a row-major, byte-padded bitmap (the format of glyphs and icons).
static void draw_bits(int x, int y, int width, int height, const uint8_t *bits)
{
  int bytes_per_row = (width + 7) / 8;
  for (int row = 0; row < height; row++) {
    for (int col = 0; col < width; col++) {
      if (bits[row * bytes_per_row + col / 8] & (0x80u >> (col % 8))) {
        canvas_pixel(x + col, y + row, true);
      }
    }
  }
}

void canvas_bitmap(int x, int y, const canvas_bitmap_t *bitmap)
{
  draw_bits(x, y, bitmap->width, bitmap->height, bitmap->bits);
}

static const canvas_glyph_t *find_glyph(const canvas_font_t *font, char c)
{
  for (int i = 0; i < font->glyph_count; i++) {
    if (font->glyphs[i].code == (uint8_t)c) {
      return &font->glyphs[i];
    }
  }
  return NULL;   // not in this font: skipped
}

int canvas_text_width(const canvas_font_t *font, const char *text)
{
  int width = 0;
  for (; *text; text++) {
    const canvas_glyph_t *glyph = find_glyph(font, *text);
    if (glyph != NULL) {
      width += glyph->width;
    }
  }
  return width;
}

int canvas_text(const canvas_font_t *font, int x, int y, const char *text, canvas_align_t align)
{
  int width = canvas_text_width(font, text);
  if (align == CANVAS_ALIGN_CENTER) {
    x -= width / 2;
  } else if (align == CANVAS_ALIGN_RIGHT) {
    x -= width;
  }
  for (; *text; text++) {
    const canvas_glyph_t *glyph = find_glyph(font, *text);
    if (glyph != NULL) {
      draw_bits(x, y, glyph->width, font->height, &font->bitmaps[glyph->offset]);
      x += glyph->width;
    }
  }
  return width;
}
