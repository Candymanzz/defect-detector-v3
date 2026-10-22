/* Exercise the MVS transaction using a simulated handle; no physical SDK device is opened. */
#define CW_EMBEDDED 1
#include "../src/main.c"
#include <assert.h>

typedef struct {
    cw_roi_rect roi;
    int grabbing, stop_count, start_count, fail_target_y;
} simulated_camera;

int MV_CC_StopGrabbing(void *handle) {
    simulated_camera *camera = handle;
    camera->grabbing = 0;
    camera->stop_count++;
    return MV_OK;
}
int MV_CC_StartGrabbing(void *handle) {
    simulated_camera *camera = handle;
    camera->grabbing = 1;
    camera->start_count++;
    return MV_OK;
}
int MV_CC_SetIntValue(void *handle, const char *name, unsigned int value) {
    simulated_camera *camera = handle;
    assert(!camera->grabbing);
    if (strcmp(name, "OffsetY") == 0 && camera->fail_target_y && value == 44) {
        camera->fail_target_y = 0;
        return MV_E_PARAMETER;
    }
    if (strcmp(name, "OffsetX") == 0) camera->roi.x = (int)value;
    if (strcmp(name, "OffsetY") == 0) camera->roi.y = (int)value;
    if (strcmp(name, "Width") == 0) camera->roi.width = (int)value;
    if (strcmp(name, "Height") == 0) camera->roi.height = (int)value;
    assert(camera->roi.x + camera->roi.width <= 1024);
    assert(camera->roi.y + camera->roi.height <= 768);
    return MV_OK;
}
int MV_CC_GetIntValue(void *handle, const char *name, MVCC_INTVALUE *value) {
    simulated_camera *camera = handle;
    assert(strcmp(name, "PayloadSize") == 0);
    value->nCurValue = (unsigned int)(camera->roi.width * camera->roi.height);
    return MV_OK;
}

int main(void) {
    simulated_camera camera = {{0, 0, 1024, 768}, 1, 0, 0, 0};
    worker_state_t state = {0};
    state.hik_handle = &camera;
    state.hik_raw_capacity = 1024 * 768;
    state.roi_supported = 1;
    state.capture_backend_ready = 1;
    state.width = 1024; state.height = 768;
    state.roi_sensor_width = 1024; state.roi_sensor_height = 768;
    state.roi_x_range = (cw_roi_range){0, 1024, 4};
    state.roi_y_range = (cw_roi_range){0, 768, 2};
    state.roi_width_range = (cw_roi_range){16, 1024, 8};
    state.roi_height_range = (cw_roi_range){8, 768, 2};
    char error[256];
    cw_roi_rect crop = {100, 20, 512, 400};
    assert(apply_capture_roi(&state, crop, error, sizeof(error)) == 0);
    assert(camera.grabbing && camera.stop_count == 1 && camera.start_count == 1);
    assert(state.width == 512 && state.height == 400 && state.roi_x == 100);
    camera.fail_target_y = 1;
    assert(apply_capture_roi(&state, (cw_roi_rect){120, 44, 512, 400}, error, sizeof(error)) != 0);
    assert(camera.grabbing && state.capture_backend_ready);
    assert(camera.roi.x == 100 && camera.roi.y == 20 && camera.roi.width == 512);
    assert(state.roi_x == 100 && state.roi_y == 20 && state.width == 512);
    assert(apply_capture_roi(&state, (cw_roi_rect){0, 0, 1024, 768}, error, sizeof(error)) == 0);
    assert(camera.grabbing && camera.roi.x == 0 && state.width == 1024);
    assert(apply_capture_roi(&state, (cw_roi_rect){1, 0, 512, 400}, error, sizeof(error)) != 0);
    assert(state.width == 1024 && camera.grabbing);
    return 0;
}
