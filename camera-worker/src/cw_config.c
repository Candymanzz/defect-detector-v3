#include "cw_config.h"

#include <string.h>
#ifndef _WIN32
#include <strings.h>
#endif

#ifdef _WIN32
#define CW_STRICMP _stricmp
#else
#define CW_STRICMP strcasecmp
#endif

int cw_parse_trigger_mode(const char *mode) {
    if (!mode || mode[0] == '\0') {
        return CW_TRIGGER_MODE_CONTINUOUS;
    }
    if (CW_STRICMP(mode, "software") == 0 || CW_STRICMP(mode, "sync") == 0) {
        return CW_TRIGGER_MODE_SOFTWARE;
    }
    if (CW_STRICMP(mode, "line0") == 0 || CW_STRICMP(mode, "line") == 0 || CW_STRICMP(mode, "hardware") == 0) {
        return CW_TRIGGER_MODE_LINE0;
    }
    if (CW_STRICMP(mode, "line1") == 0) {
        return CW_TRIGGER_MODE_LINE1;
    }
    return CW_TRIGGER_MODE_CONTINUOUS;
}

const char *cw_trigger_mode_name(int trigger_mode) {
    switch (trigger_mode) {
        case CW_TRIGGER_MODE_SOFTWARE:
            return "software";
        case CW_TRIGGER_MODE_LINE0:
            return "line0";
        case CW_TRIGGER_MODE_LINE1:
            return "line1";
        default:
            return "continuous";
    }
}

int cw_parse_pixel_format_pref(const char *raw) {
    if (!raw || raw[0] == '\0') {
        return CW_PIXEL_PREF_BGR8;
    }
    if (CW_STRICMP(raw, "Mono8") == 0 || CW_STRICMP(raw, "MONO8") == 0 || CW_STRICMP(raw, "gray") == 0
        || CW_STRICMP(raw, "Gray8") == 0) {
        return CW_PIXEL_PREF_MONO8;
    }
    if (CW_STRICMP(raw, "RGB8") == 0 || CW_STRICMP(raw, "RGB8Packed") == 0) {
        return CW_PIXEL_PREF_RGB8;
    }
    return CW_PIXEL_PREF_BGR8;
}

const char *cw_pixel_format_pref_name(int pref) {
    switch (pref) {
        case CW_PIXEL_PREF_MONO8:
            return "Mono8";
        case CW_PIXEL_PREF_RGB8:
            return "RGB8";
        default:
            return "BGR8";
    }
}

void cw_write_u32_be(uint8_t *b, uint32_t v) {
    b[0] = (uint8_t)((v >> 24) & 0xFF);
    b[1] = (uint8_t)((v >> 16) & 0xFF);
    b[2] = (uint8_t)((v >> 8) & 0xFF);
    b[3] = (uint8_t)(v & 0xFF);
}

uint32_t cw_read_u32_be(const uint8_t *b) {
    return ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) | ((uint32_t)b[2] << 8) | (uint32_t)b[3];
}
