/* Real MVS debayering on synthetic pixels; format negotiation uses a fake device.
 * No physical camera is opened or reconfigured. */
#include <MvCameraControl.h>
#include <math.h>
static int fake_get_enum(void *handle, const char *name, MVCC_ENUMVALUE *value);
static int fake_set_enum(void *handle, const char *name, unsigned int value);
static int fake_set_enum_string(void *handle, const char *name, const char *value);
static int fake_get_frame(void *, unsigned char *, unsigned int, MV_FRAME_OUT_INFO_EX *, unsigned int);
static int fake_get_float(void *, const char *, MVCC_FLOATVALUE *);
static int fake_get_int(void *, const char *, MVCC_INTVALUE *);
#define MV_CC_GetEnumValue fake_get_enum
#define MV_CC_SetEnumValue fake_set_enum
#define MV_CC_SetEnumValueByString fake_set_enum_string
#define MV_CC_GetOneFrameTimeout fake_get_frame
#define MV_CC_GetFloatValue fake_get_float
#define MV_CC_GetIntValue fake_get_int
#define CW_EMBEDDED 1
#include "../src/main.c"
#undef MV_CC_GetEnumValue
#undef MV_CC_SetEnumValue
#undef MV_CC_SetEnumValueByString
#undef MV_CC_GetOneFrameTimeout
#undef MV_CC_GetFloatValue
#undef MV_CC_GetIntValue
#include <assert.h>

static MVCC_ENUMVALUE advertised;
static unsigned int selected;
static int get_result, set_result, set_calls;
static unsigned int fake_frame_length = 256, fake_lost_packets;
static int balance_is_float;

static int fake_get_float(void *handle, const char *name, MVCC_FLOATVALUE *value) {
    (void)handle;
    if (!strcmp(name, "BalanceRatio") && balance_is_float) {
        value->fCurValue = 1.65f;
        return MV_OK;
    }
    return MV_E_SUPPORT;
}
static int fake_get_int(void *handle, const char *name, MVCC_INTVALUE *value) {
    (void)handle;
    assert(!strcmp(name, "BalanceRatio"));
    value->nCurValue = 1690;
    return MV_OK;
}

static int fake_get_frame(void *handle, unsigned char *raw, unsigned int capacity,
                          MV_FRAME_OUT_INFO_EX *info, unsigned int timeout) {
    (void)handle; (void)timeout;
    assert(capacity >= 256);
    const unsigned char tile[] = {220, 100, 100, 20};
    for (int y = 0; y < 16; ++y) for (int x = 0; x < 16; ++x)
        raw[y * 16 + x] = tile[2 * (y % 2) + x % 2];
    info->nWidth = 16; info->nHeight = 16;
    info->enPixelType = PixelType_Gvsp_BayerRG8;
    info->nFrameLen = fake_frame_length; info->nLostPacket = fake_lost_packets;
    return MV_OK;
}

static int fake_get_enum(void *handle, const char *name, MVCC_ENUMVALUE *value) {
    (void)handle;
    assert(strcmp(name, "PixelFormat") == 0);
    *value = advertised;
    return get_result;
}
static int fake_set_enum(void *handle, const char *name, unsigned int value) {
    (void)handle;
    assert(strcmp(name, "PixelFormat") == 0);
    selected = value; ++set_calls;
    return set_result;
}
static int fake_set_enum_string(void *handle, const char *name, const char *value) {
    (void)handle;
    if (!strcmp(name, "BalanceRatioSelector")) {
        assert(!strcmp(value, "Red") || !strcmp(value, "Blue"));
        return MV_OK;
    }
    assert(strcmp(name, "PixelFormat") == 0);
    assert(strcmp(value, "BayerGR8") == 0);
    ++set_calls;
    return set_result;
}

static void negotiation(void) {
    int active = -1;
    advertised.nCurValue = PixelType_Gvsp_Mono8;
    advertised.nSupportedNum = 3;
    advertised.nSupportValue[0] = PixelType_Gvsp_Mono8;
    advertised.nSupportValue[1] = PixelType_Gvsp_BayerBG8;
    advertised.nSupportValue[2] = PixelType_Gvsp_BayerRG8;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER8, &active) == 0);
    assert(selected == PixelType_Gvsp_BayerBG8 && active == CW_PIXEL_PREF_BAYER_BG8);
    advertised.nCurValue = PixelType_Gvsp_BayerRG8;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER8, &active) == 0);
    assert(selected == PixelType_Gvsp_BayerRG8 && active == CW_PIXEL_PREF_BAYER_RG8);

    advertised.nCurValue = PixelType_Gvsp_Mono8;
    advertised.nSupportedNum = 1;
    set_calls = 0;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER8, &active) != 0);
    assert(set_calls == 0); // Never silently change to 3-byte RGB or monochrome.
    get_result = MV_E_PARAMETER;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER8, &active) != 0);
    assert(set_calls == 0);
    get_result = MV_OK;
    advertised.nSupportedNum = 2;
    set_result = MV_E_PARAMETER;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER8, &active) != 0);
    assert(set_calls == 1);
    set_calls = 0;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER_GR8, &active) != 0);
    assert(set_calls == 1);
    set_result = MV_OK;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER_GR8, &active) == 0);
    assert(active == CW_PIXEL_PREF_BAYER_GR8);
}

static void conversion(void) {
    MV_CC_DEVICE_INFO device = {0};
    device.nTLayerType = MV_GIGE_DEVICE;
    void *handle = NULL;
    assert(MV_CC_CreateHandle(&handle, &device) == MV_OK);
    const enum MvGvspPixelType types[] = {PixelType_Gvsp_BayerRG8, PixelType_Gvsp_BayerGR8,
                                        PixelType_Gvsp_BayerGB8, PixelType_Gvsp_BayerBG8};
    const unsigned char tiles[][4] = {{220,100,100,20}, {100,220,20,100},
                                     {100,20,220,100}, {20,100,100,220}};
    for (int pattern = 0; pattern < 4; ++pattern) for (int padded = 0; padded < 2; ++padded) {
        const int width = 16, height = 16;
        const int src_stride = width + (padded ? 3 : 0);
        const int dst_stride = width * 3 + (padded ? 5 : 0);
        unsigned char raw[16 * 19], output[16 * 53 + 16];
        memset(raw, 0xEE, sizeof(raw)); memset(output, 0xDD, sizeof(output));
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x)
            raw[y * src_stride + x] = tiles[pattern][2 * (y % 2) + x % 2];
        worker_state_t state = {0};
        state.hik_handle = handle;
        state.hik_raw_frame = raw; state.hik_raw_capacity = sizeof(raw);
        state.width = width; state.height = height; state.stride = dst_stride;
        state.frame_bytes = (size_t)height * dst_stride;
        MV_FRAME_OUT_INFO_EX info = {0};
        info.nWidth = width; info.nHeight = height;
        info.nFrameLen = (unsigned int)src_stride * height; info.enPixelType = types[pattern];
        assert(hik_copy_frame_to_bgr(&state, &info, output) == 0);
        for (int y = 2; y < height - 2; ++y) for (int x = 2; x < width - 2; ++x) {
            const unsigned char *pixel = output + y * dst_stride + x * 3;
            assert(abs(pixel[0] - 20) <= 1 && abs(pixel[1] - 100) <= 1 && abs(pixel[2] - 220) <= 1);
        }
        for (int y = 0; y < height; ++y) for (int x = width * 3; x < dst_stride; ++x)
            assert(output[y * dst_stride + x] == 0);
        for (size_t i = state.frame_bytes; i < sizeof(output); ++i) assert(output[i] == 0xDD);

        // Incomplete Bayer input must never publish stale/partial colors.
        info.nFrameLen = width * height - 1;
        memset(output, 0xDD, sizeof(output));
        assert(hik_copy_frame_to_bgr(&state, &info, output) != 0);
        for (size_t i = 0; i < sizeof(output); ++i) assert(output[i] == 0xDD);
        info.nFrameLen = width * height; info.nWidth = width - 1;
        assert(hik_copy_frame_to_bgr(&state, &info, output) != 0);
    }
    // Exercise capture validation too: Bayer input is one byte per pixel.
    worker_state_t state = {0};
    unsigned char raw[256], output[768];
    char error[256] = {0};
    strcpy(state.capture_source, "hik");
    state.hik_handle = handle; state.hik_raw_frame = raw; state.hik_raw_capacity = sizeof(raw);
    state.width = 16; state.height = 16; state.stride = 48; state.frame_bytes = sizeof(output);
    state.frame_timeout_ms = 50;
    state.hik_pixel_format_active = CW_PIXEL_PREF_BAYER_RG8;
    assert(capture_from_source(&state, output, 0, 0, 0, error, sizeof(error)) == 0);
    assert(output[8 * 48 + 8 * 3] == 20 && output[8 * 48 + 8 * 3 + 2] == 220);
    assert(hik_apply_gamma(&state, 0.7f) == MV_OK);
    assert(capture_from_source(&state, output, 0, 0, 0, error, sizeof(error)) == 0);
    assert(output[8 * 48 + 8 * 3] != 20 || output[8 * 48 + 8 * 3 + 2] != 220);
    assert(hik_apply_gamma(&state, 1.0f) == MV_OK);
    memset(output, 0xDD, sizeof(output));
    fake_frame_length = 255;
    assert(capture_from_source(&state, output, 0, 0, 0, error, sizeof(error)) != 0);
    assert(strstr(error, "too short"));
    fake_frame_length = 256; fake_lost_packets = 1;
    assert(capture_from_source(&state, output, 0, 0, 0, error, sizeof(error)) != 0);
    assert(strstr(error, "lost_packets"));
    for (size_t i = 0; i < sizeof(output); ++i) assert(output[i] == 0xDD);
    assert(MV_CC_DestroyHandle(handle) == MV_OK);
}

static void packed12_conversion(void) {
    int active = -1;
    set_result = MV_OK;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER_RG12_PACKED, &active) == 0);
    assert(selected == PixelType_Gvsp_BayerRG12_Packed && active == CW_PIXEL_PREF_BAYER_RG12_PACKED);
    set_result = MV_E_PARAMETER;
    assert(hik_apply_pixel_format(NULL, 0, CW_PIXEL_PREF_BAYER_RG12_PACKED, &active) != 0);
    set_result = MV_OK;
    MV_CC_DEVICE_INFO device = {0};
    device.nTLayerType = MV_GIGE_DEVICE;
    void *handle = NULL;
    assert(MV_CC_CreateHandle(&handle, &device) == MV_OK);
    for (int padded = 0; padded < 2; ++padded) {
        unsigned char raw[16 * 27], output[16 * 53 + 16];
        const int source_stride = 24 + padded * 3, destination_stride = 48 + padded * 5;
        memset(raw, 0xFF, sizeof(raw));
        memset(output, 0xDD, sizeof(output));
        worker_state_t state = {0};
        state.hik_handle = handle; state.hik_raw_frame = raw; state.hik_raw_capacity = sizeof(raw);
        state.width = 16; state.height = 16; state.stride = destination_stride;
        state.frame_bytes = 16 * destination_stride;
        state.hik_pixel_format_active = CW_PIXEL_PREF_BAYER_RG12_PACKED;
        MV_FRAME_OUT_INFO_EX info = {0};
        info.nWidth = 16; info.nHeight = 16; info.nFrameLen = 16 * source_stride;
        info.enPixelType = PixelType_Gvsp_BayerRG12_Packed;
        assert(hik_copy_frame_to_bgr(&state, &info, output) == 0);
        for (int y = 2; y < 14; ++y) for (int x = 6; x < 42; ++x)
            assert(output[y * destination_stride + x] >= 254);
        for (int y = 0; y < 16; ++y) for (int x = 48; x < destination_stride; ++x)
            assert(output[y * destination_stride + x] == 0);
        for (size_t i = state.frame_bytes; i < sizeof(output); ++i) assert(output[i] == 0xDD);
        info.nFrameLen = 383;
        memset(output, 0xDD, sizeof(output));
        assert(hik_copy_frame_to_bgr(&state, &info, output) != 0);
        for (size_t i = 0; i < sizeof(output); ++i) assert(output[i] == 0xDD);
        assert(hik_apply_gamma(&state, 0.7f) == MV_OK);
    }
    assert(MV_CC_DestroyHandle(handle) == MV_OK);
}

int main(void) {
    negotiation(); conversion(); packed12_conversion();
    float ratio = 0;
    assert(hik_read_balance_ratio_channel(NULL, "Red", "BalanceRatioRed", &ratio) == MV_OK);
    assert(fabsf(ratio - 1690.0f / 1024.0f) < 0.0001f);
    balance_is_float = 1;
    assert(hik_read_balance_ratio_channel(NULL, "Red", "BalanceRatioRed", &ratio) == MV_OK);
    assert(fabsf(ratio - 1.65f) < 0.0001f);
    puts("Bayer8/12 negotiation, SDK colors, strides and truncated-frame checks passed");
    return 0;
}
