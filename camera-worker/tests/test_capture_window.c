/* Exercise cancellation during SDK wait without camera hardware. */
#define HAVE_HIK_MVS 1
#include <MvCameraControl.h>
static int fake_clear(void *h);
static int fake_get(void *h, unsigned char *data, unsigned int size, MV_FRAME_OUT_INFO_EX *info, unsigned int timeout);
#define MV_CC_ClearImageBuffer fake_clear
#define MV_CC_GetOneFrameTimeout fake_get
#define main camera_worker_main
#include "../src/main.c"
#undef main
#include <assert.h>
static const char *gate;
static int calls, clears, mode;
static void set_gate(const char *value) { FILE *f = fopen(gate, "w"); assert(f); fputs(value, f); fclose(f); }
static int fake_clear(void *h) { (void)h; clears++; return MV_OK; }
static int fake_get(void *h, unsigned char *data, unsigned int size, MV_FRAME_OUT_INFO_EX *info, unsigned int timeout) {
    (void)h; (void)data; (void)size; (void)info;
    assert(timeout == 20); calls++;
    if (mode == 1 || mode == 2) {
        memset(info, 0, sizeof(*info));
        info->nWidth = 2; info->nHeight = 2; info->nFrameLen = 4;
        info->enPixelType = PixelType_Gvsp_Mono8;
        memset(data, 127, 4);
        if (mode == 2) set_gate("0");
        return MV_OK;
    }
    set_gate("0");
    return MV_E_NODATA;
}
int main(void) {
    char filename[] = "/tmp/iml-window-c-test-XXXXXX";
    int fd = mkstemp(filename); assert(fd >= 0); close(fd); gate = filename;
    worker_state_t st = {0};
    strcpy(st.capture_source, "hik"); strcpy(st.capture_window_path, gate);
    strcpy(st.capture_window_token, "1"); st.frame_timeout_ms = 15000;
    char err[256]; unsigned char frame[12];
    set_gate("0"); assert(!capture_window_open(&st));
    assert(capture_from_source(&st, frame, 1, 0, 1, err, sizeof(err)) != 0);
    assert(calls == 0 && clears == 0);
    set_gate("1"); assert(capture_window_open(&st));
    uint64_t start = now_ns();
    assert(capture_from_source(&st, frame, 1, 0, 1, err, sizeof(err)) != 0);
    assert(strcmp(err, "capture_window_closed") == 0);
    assert(calls == 1 && clears == 1);
    assert(now_ns() - start < 1000000000ull);
    set_gate("2"); assert(!capture_window_open(&st)); /* Previous cycle may not use next frame. */
    unsigned char raw[4]; st.hik_raw_frame = raw; st.hik_raw_capacity = sizeof(raw);
    st.width = st.height = 2; st.stride = 6;
    mode = 1; set_gate("1");
    assert(capture_from_source(&st, frame, 1, 0, 1, err, sizeof(err)) == 0);
    mode = 2; set_gate("1");
    assert(capture_from_source(&st, frame, 1, 0, 1, err, sizeof(err)) != 0);
    assert(strcmp(err, "capture_window_closed") == 0);
    unlink(gate);
    puts("capture window tests passed"); return 0;
}
