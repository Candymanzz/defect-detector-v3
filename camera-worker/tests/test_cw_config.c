#include "cw_config.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_trigger_modes(void) {
    assert(cw_parse_trigger_mode(NULL) == CW_TRIGGER_MODE_CONTINUOUS);
    assert(cw_parse_trigger_mode("software") == CW_TRIGGER_MODE_SOFTWARE);
    assert(cw_parse_trigger_mode("hardware") == CW_TRIGGER_MODE_LINE0);
    assert(cw_parse_trigger_mode("line1") == CW_TRIGGER_MODE_LINE1);
    assert(strcmp(cw_trigger_mode_name(CW_TRIGGER_MODE_SOFTWARE), "software") == 0);
}

static void test_pixel_formats(void) {
    assert(cw_parse_pixel_format_pref("Mono8") == CW_PIXEL_PREF_MONO8);
    assert(cw_parse_pixel_format_pref("RGB8") == CW_PIXEL_PREF_RGB8);
    assert(cw_parse_pixel_format_pref("unknown") == CW_PIXEL_PREF_BGR8);
    assert(strcmp(cw_pixel_format_pref_name(CW_PIXEL_PREF_MONO8), "Mono8") == 0);
}

static void test_u32_endian(void) {
    uint8_t buf[4];
    cw_write_u32_be(buf, 0x01020304u);
    assert(cw_read_u32_be(buf) == 0x01020304u);
}

int main(void) {
    test_trigger_modes();
    test_pixel_formats();
    test_u32_endian();
    printf("camera-worker tests passed\n");
    return 0;
}
