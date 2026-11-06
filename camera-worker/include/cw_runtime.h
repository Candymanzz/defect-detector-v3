#ifndef CW_RUNTIME_H
#define CW_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

/* Called by the event dispatcher, never by the vendor SDK callback. Set before opening cameras. */
typedef void (*cw_camera_event_listener)(int camera_id, int kind, uint64_t device_ticks,
                                         uint64_t host_mono_ns, unsigned long dropped);
void cw_camera_set_event_listener(cw_camera_event_listener listener);

typedef struct cw_camera cw_camera;
typedef struct {
    int type;
    char json[2048];
} cw_response;

/* The caller serializes commands and closes only after the last command returns. */
cw_camera *cw_camera_open(const char *config_path, int camera_id, char *error, size_t error_size);
int cw_camera_command(cw_camera *camera, const char *header_json, cw_response *response);
/* May run concurrently with a command; the handle must remain alive. */
void cw_camera_cancel(cw_camera *camera);
void cw_camera_close(cw_camera *camera);

#endif
