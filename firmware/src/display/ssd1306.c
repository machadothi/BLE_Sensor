/***************************************************************************//**
 * @file
 * @brief SSD1306 128x64 OLED controller over I2C.
 ******************************************************************************/

#include "sl_i2cspm.h"
#include "sl_i2cspm_instances.h"
#include "app_config.h"
#include "display/canvas.h"
#include "display/ssd1306.h"

// First byte of every I2C write: what the following bytes are
#define CONTROL_COMMANDS          0x00
#define CONTROL_DATA              0x40

// Commands (SSD1306 datasheet, section 9)
#define CMD_DISPLAY_OFF           0xAE
#define CMD_DISPLAY_ON            0xAF
#define CMD_SET_CLOCK             0xD5
#define CMD_SET_MULTIPLEX         0xA8
#define CMD_SET_OFFSET            0xD3
#define CMD_SET_START_LINE        0x40
#define CMD_CHARGE_PUMP           0x8D
#define CMD_ADDRESSING_MODE       0x20
#define CMD_SEGMENT_REMAP_ON      0xA1   // column 127 = SEG0 (normal orientation)
#define CMD_SEGMENT_REMAP_OFF     0xA0
#define CMD_COM_SCAN_DOWN         0xC8   // row 63 = COM0 (normal orientation)
#define CMD_COM_SCAN_UP           0xC0
#define CMD_COM_PINS              0xDA
#define CMD_SET_CONTRAST          0x81
#define CMD_SET_PRECHARGE         0xD9
#define CMD_SET_VCOM_DESELECT     0xDB
#define CMD_RESUME_FROM_RAM       0xA4
#define CMD_NORMAL_DISPLAY        0xA6
#define CMD_STOP_SCROLL           0x2E
#define CMD_PAGE_ADDRESS          0xB0   // + page 0..7
#define CMD_COLUMN_LOW            0x00   // + low nibble
#define CMD_COLUMN_HIGH           0x10   // + high nibble

static uint8_t address;

static bool write(uint8_t control, const uint8_t *bytes, uint16_t length)
{
  I2C_TransferSeq_TypeDef seq = {
    .addr = (uint16_t)(address << 1),
    .flags = I2C_FLAG_WRITE_WRITE,
  };
  seq.buf[0].data = &control;
  seq.buf[0].len = 1;
  seq.buf[1].data = (uint8_t *)bytes;
  seq.buf[1].len = length;
  return I2CSPM_Transfer(sl_i2cspm_sensor, &seq) == i2cTransferDone;
}

static bool commands(const uint8_t *bytes, uint16_t length)
{
  return write(CONTROL_COMMANDS, bytes, length);
}

bool ssd1306_init(void)
{
  static const uint8_t PROBE[] = { CMD_DISPLAY_OFF };
  static const uint8_t SETUP[] = {
    CMD_SET_CLOCK, 0x80,
    CMD_SET_MULTIPLEX, CANVAS_HEIGHT - 1,
    CMD_SET_OFFSET, 0x00,
    CMD_SET_START_LINE | 0,
    CMD_CHARGE_PUMP, 0x14,            // internal charge pump on (4-pin modules need it)
    CMD_ADDRESSING_MODE, 0x02,        // page addressing: we send one page at a time
#if DISPLAY_ROTATE_180
    CMD_SEGMENT_REMAP_OFF,
    CMD_COM_SCAN_UP,
#else // DISPLAY_ROTATE_180
    CMD_SEGMENT_REMAP_ON,
    CMD_COM_SCAN_DOWN,
#endif // DISPLAY_ROTATE_180
    CMD_COM_PINS, 0x12,
    CMD_SET_CONTRAST, DISPLAY_CONTRAST,
    CMD_SET_PRECHARGE, 0xF1,
    CMD_SET_VCOM_DESELECT, 0x40,
    CMD_RESUME_FROM_RAM,
    CMD_NORMAL_DISPLAY,
    CMD_STOP_SCROLL,
  };
  static const uint8_t ON[] = { CMD_DISPLAY_ON };
  static const uint8_t ADDRESSES[] = { 0x3C, 0x3D };   // the two the SSD1306 can use

  for (unsigned i = 0; i < sizeof(ADDRESSES); i++) {
    address = ADDRESSES[i];
    if (commands(PROBE, sizeof(PROBE))) {
      break;
    }
    address = 0;
  }
  if (address == 0) {
    return false;
  }

  // Start from a blank screen so no random RAM content flashes up.
  static const uint8_t BLANK[CANVAS_WIDTH] = { 0 };
  commands(SETUP, sizeof(SETUP));
  for (uint8_t page = 0; page < CANVAS_PAGES; page++) {
    ssd1306_write_page(page, BLANK);
  }
  commands(ON, sizeof(ON));
  return true;
}

void ssd1306_write_page(uint8_t page, const uint8_t *columns)
{
  uint8_t column = DISPLAY_COLUMN_OFFSET;   // SH1106 clones start at column 2
  uint8_t position[] = {
    (uint8_t)(CMD_PAGE_ADDRESS | page),
    (uint8_t)(CMD_COLUMN_LOW | (column & 0x0F)),
    (uint8_t)(CMD_COLUMN_HIGH | (column >> 4)),
  };
  commands(position, sizeof(position));
  write(CONTROL_DATA, columns, CANVAS_WIDTH);
}

void ssd1306_set_contrast(uint8_t contrast)
{
  uint8_t bytes[] = { CMD_SET_CONTRAST, contrast };
  commands(bytes, sizeof(bytes));
}
