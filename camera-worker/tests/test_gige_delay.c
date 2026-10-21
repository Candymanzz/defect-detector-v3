/* Verify explicit zero resets SDK nodes instead of skipping or auto-staggering. */
#define HAVE_HIK_MVS 1
#include <MvCameraControl.h>
static int fake_get_int(void *, const char *, MVCC_INTVALUE *);
static int fake_set_int(void *, const char *, unsigned int);
#define MV_CC_GetIntValue fake_get_int
#define MV_CC_SetIntValue fake_set_int
#define main camera_worker_main
#include "../src/main.c"
#undef main
#include <assert.h>

static unsigned int packet_delay = 10000u, frame_delay = 1500000u;
static int writes;
static int fake_get_int(void *handle, const char *name, MVCC_INTVALUE *value) {
    (void)handle; (void)name;
    memset(value, 0, sizeof(*value));
    value->nMax = UINT_MAX;
    value->nInc = 1;
    return MV_OK;
}
static int fake_set_int(void *handle, const char *name, unsigned int value) {
    (void)handle;
    if (strcmp(name, "GevSCPD") == 0) packet_delay = value;
    else if (strcmp(name, "GevSCFTD") == 0) frame_delay = value;
    else assert(0);
    writes++;
    return MV_OK;
}
int main(void) {
    worker_state_t st;
    memset(&st, 0, sizeof(st));
    st.hik_handle = (void *)1;
    st.camera_id = 1;
    st.gige_transfer_slot = 1;
    st.gige_switch_buffer_kb = 48; /* Explicit zero must also override automatic floor. */
    st.gige_ftd_cameras_per_link = 2;
    assert(hik_effective_inter_packet_delay(&st) == 0);
    hik_apply_gige_inter_packet_delay(st.hik_handle, hik_effective_inter_packet_delay(&st), st.camera_id);
    assert(packet_delay == 0 && writes == 1);
    assert(hik_effective_frame_transfer_delay_step(&st) == 0);
    hik_apply_gige_frame_transfer_delay(&st);
    assert(frame_delay == 0 && writes == 2);
    st.gige_frame_transfer_delay_step = -1;
    assert(hik_effective_frame_transfer_delay_step(&st) > 0);
    st.gige_inter_packet_delay = 10000;
    assert(hik_effective_inter_packet_delay(&st) >= 10000);
    puts("explicit-zero SDK delay regression: PASS");
    return 0;
}
