#ifndef CW_CONFIG_H
#define CW_CONFIG_H

#include <stdint.h>

enum {
    CW_PIXEL_PREF_BGR8 = 0,
    CW_PIXEL_PREF_RGB8 = 1,
    CW_PIXEL_PREF_MONO8 = 2
};

enum {
    CW_TRIGGER_MODE_CONTINUOUS = 0,
    CW_TRIGGER_MODE_SOFTWARE = 1,
    CW_TRIGGER_MODE_LINE0 = 2,
    CW_TRIGGER_MODE_LINE1 = 3
};

int cw_parse_trigger_mode(const char *mode);
const char *cw_trigger_mode_name(int trigger_mode);
int cw_parse_pixel_format_pref(const char *raw);
const char *cw_pixel_format_pref_name(int pref);
void cw_write_u32_be(uint8_t *b, uint32_t v);
uint32_t cw_read_u32_be(const uint8_t *b);

#define PIXEL_PREF_BGR8 CW_PIXEL_PREF_BGR8
#define PIXEL_PREF_RGB8 CW_PIXEL_PREF_RGB8
#define PIXEL_PREF_MONO8 CW_PIXEL_PREF_MONO8
#define TRIGGER_MODE_CONTINUOUS CW_TRIGGER_MODE_CONTINUOUS
#define TRIGGER_MODE_SOFTWARE CW_TRIGGER_MODE_SOFTWARE
#define TRIGGER_MODE_LINE0 CW_TRIGGER_MODE_LINE0
#define TRIGGER_MODE_LINE1 CW_TRIGGER_MODE_LINE1
#define parse_trigger_mode cw_parse_trigger_mode
#define trigger_mode_name cw_trigger_mode_name
#define parse_pixel_format_pref cw_parse_pixel_format_pref
#define pixel_format_pref_name cw_pixel_format_pref_name
#define write_u32_be cw_write_u32_be
#define read_u32_be cw_read_u32_be

#endif
