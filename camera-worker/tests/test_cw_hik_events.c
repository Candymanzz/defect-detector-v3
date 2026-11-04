#include "cw_hik_events.h"
#include <assert.h>

int main(void) {
    cw_hik_events state = {0};
    state.camera_id = 0;
    state.frequency = 1000000000;
    state.file = tmpfile();
    assert(state.file);
    assert(!pthread_mutex_init(&state.mutex, NULL));
    atomic_init(&state.stopping, 0);
    atomic_init(&state.dropped, 0);
    MV_EVENT_OUT_INFO event = {0};
    strcpy(event.EventName, "Line0RisingEdge");
    event.nTimestampHigh = 1; event.nTimestampLow = 23;
    event.nEventID = 7;
    cw_hik_event_callback(&event, &state);
    strcpy(event.EventName, "ExposureStart");
    event.nTimestampLow = 42;
    cw_hik_event_callback(&event, &state);
    assert(state.count == 2);
    assert(state.records[0].device_ticks == (UINT64_C(1) << 32) + 23);
    assert(state.records[0].host_mono_ns && state.records[0].host_epoch_ns);
    pthread_mutex_lock(&state.mutex);
    cw_hik_event_callback(&event, &state);
    pthread_mutex_unlock(&state.mutex);
    assert(atomic_load(&state.dropped) == 1);
    state.count = CW_EVENT_CAPACITY;
    cw_hik_event_callback(&event, &state);
    assert(atomic_load(&state.dropped) == 2);
    state.count = 2;
    atomic_store(&state.stopping, 1);
    assert(!pthread_create(&state.thread, NULL, cw_hik_event_writer, &state));
    pthread_join(state.thread, NULL);
    rewind(state.file);
    char first[1024], second[1024];
    assert(fgets(first, sizeof(first), state.file));
    assert(fgets(second, sizeof(second), state.file));
    assert(strstr(first, "Line0RisingEdge") && strstr(first, "4294967319"));
    assert(strstr(second, "ExposureStart") && strstr(second, "4294967338"));
    assert(strstr(second, "\"dropped_total\":2"));
    assert(!fgets(first, sizeof(first), state.file));
    fclose(state.file); pthread_mutex_destroy(&state.mutex);
    puts("camera event queue, timestamps, contention and overflow: PASS");
    return 0;
}
