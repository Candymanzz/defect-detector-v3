#ifndef CW_HIK_EVENTS_H
#define CW_HIK_EVENTS_H
/* Linux diagnostics on the existing camera handle. Callback never writes files. */
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <MvCameraControl.h>

#define CW_EVENT_CAPACITY 256
typedef struct {
    uint64_t device_ticks, block_id, host_mono_ns, host_epoch_ns;
    unsigned short id;
    int kind;
} cw_hik_event_record;
typedef struct {
    pthread_mutex_t mutex;
    pthread_t thread;
    atomic_int stopping;
    atomic_ulong dropped;
    cw_hik_event_record records[CW_EVENT_CAPACITY];
    unsigned int head, count;
    int camera_id, enabled[2], registered[2];
    unsigned int old_notification[2], old_selector;
    int selector_saved;
    uint64_t frequency;
    FILE *file;
} cw_hik_events;
static const char *cw_hik_event_names[2] = {"Line0RisingEdge", "ExposureStart"};

static uint64_t cw_event_clock(clockid_t clock) {
    struct timespec ts;
    if (clock_gettime(clock, &ts)) return 0;
    return (uint64_t)ts.tv_sec * UINT64_C(1000000000) + ts.tv_nsec;
}
static void __stdcall cw_hik_event_callback(MV_EVENT_OUT_INFO *event, void *user) {
    cw_hik_events *state = user;
    if (!event || atomic_load(&state->stopping)) return;
    cw_hik_event_record record = {0};
    record.host_mono_ns = cw_event_clock(CLOCK_MONOTONIC);
    record.host_epoch_ns = cw_event_clock(CLOCK_REALTIME);
    record.kind = strncmp(event->EventName, cw_hik_event_names[0], MAX_EVENT_NAME_SIZE) == 0 ? 0 : 1;
    record.device_ticks = ((uint64_t)event->nTimestampHigh << 32) | event->nTimestampLow;
    record.block_id = ((uint64_t)event->nBlockIdHigh << 32) | event->nBlockIdLow;
    record.id = event->nEventID;
    if (pthread_mutex_trylock(&state->mutex)) {
        atomic_fetch_add(&state->dropped, 1);
        return;
    }
    if (state->count == CW_EVENT_CAPACITY) atomic_fetch_add(&state->dropped, 1);
    else {
        state->records[(state->head + state->count) % CW_EVENT_CAPACITY] = record;
        ++state->count;
    }
    pthread_mutex_unlock(&state->mutex);
}
static void *cw_hik_event_writer(void *user) {
    cw_hik_events *state = user;
    for (;;) {
        cw_hik_event_record record;
        pthread_mutex_lock(&state->mutex);
        int available = state->count != 0;
        if (available) {
            record = state->records[state->head];
            state->head = (state->head + 1) % CW_EVENT_CAPACITY;
            --state->count;
        }
        pthread_mutex_unlock(&state->mutex);
        if (!available) {
            if (atomic_load(&state->stopping)) break;
            struct timespec wait = {0, 10000000};
            nanosleep(&wait, NULL);
            continue;
        }
        fprintf(state->file,
            "{\"type\":\"camera_event\",\"camera_id\":%d,\"event\":\"%s\","
            "\"event_id\":%u,\"device_timestamp_ticks\":%" PRIu64 ","
            "\"device_tick_frequency_hz\":%" PRIu64 ",\"block_id\":%" PRIu64 ","
            "\"host_monotonic_ns\":%" PRIu64 ",\"host_epoch_ns\":%" PRIu64 ","
            "\"dropped_total\":%lu}\n",
            state->camera_id, cw_hik_event_names[record.kind], record.id,
            record.device_ticks, state->frequency, record.block_id,
            record.host_mono_ns, record.host_epoch_ns, atomic_load(&state->dropped));
        fflush(state->file);
    }
    return NULL;
}
static cw_hik_events *cw_hik_events_start(void *handle, int camera_id) {
    const char *setting = getenv("IML_CAMERA_EVENT_CAMERA");
    int selected = setting ? atoi(setting) : 0;
    if (camera_id != selected) return NULL; /* -1 disables; one diagnostic camera. */
    MVCC_ENUMVALUE selector = {0};
    int result = MV_CC_GetEnumValue(handle, "EventSelector", &selector);
    if (result != MV_OK) {
        fprintf(stderr, "camera_events cam=%d EventSelector unavailable result=0x%x\n", camera_id, result);
        return NULL;
    }
    for (unsigned int i = 0; i < selector.nSupportedNum && i < 64; ++i) {
        MVCC_ENUMENTRY entry = {0}; entry.nValue = selector.nSupportValue[i];
        if (MV_CC_GetEnumEntrySymbolic(handle, "EventSelector", &entry) == MV_OK)
            fprintf(stderr, "camera_events cam=%d supported_event=%s\n", camera_id, entry.chSymbolic);
    }
    cw_hik_events *state = calloc(1, sizeof(*state));
    if (!state) return NULL;
    state->camera_id = camera_id;
    state->old_selector = selector.nCurValue;
    state->selector_saved = 1;
    atomic_init(&state->stopping, 0); atomic_init(&state->dropped, 0);
    MVCC_INTVALUE_EX ticks = {0};
    if (MV_CC_GetIntValueEx(handle, "GevTimestampTickFrequency", &ticks) == MV_OK)
        state->frequency = (uint64_t)ticks.nCurValue;
    mkdir("logs", 0777);
    char path[128]; snprintf(path, sizeof(path), "logs/camera-events-cam-%d.jsonl", camera_id);
    state->file = fopen(path, "a");
    if (!state->file) { free(state); return NULL; }
    if (pthread_mutex_init(&state->mutex, NULL)) { fclose(state->file); free(state); return NULL; }
    if (pthread_create(&state->thread, NULL, cw_hik_event_writer, state)) {
        pthread_mutex_destroy(&state->mutex); fclose(state->file); free(state); return NULL;
    }
    fprintf(state->file, "{\"type\":\"session_start\",\"camera_id\":%d,\"host_epoch_ns\":%" PRIu64 "}\n",
            camera_id, cw_event_clock(CLOCK_REALTIME));
    for (int kind = 0; kind < 2; ++kind) {
        const char *name = cw_hik_event_names[kind];
        result = MV_CC_SetEnumValueByString(handle, "EventSelector", name);
        MVCC_ENUMVALUE old = {0};
        if (result == MV_OK) result = MV_CC_GetEnumValue(handle, "EventNotification", &old);
        if (result == MV_OK) {
            state->old_notification[kind] = old.nCurValue;
            result = MV_CC_RegisterEventCallBackEx(handle, name, cw_hik_event_callback, state);
            if (result == MV_OK) {
                state->registered[kind] = 1;
                state->enabled[kind] = 1; /* Restore even if enabling reports an error. */
                result = MV_CC_SetEnumValueByString(handle, "EventNotification", "On");
            }
        }
        fprintf(stderr, "camera_events cam=%d event=%s subscribe_result=0x%x log=%s\n", camera_id, name, result, path);
        fprintf(state->file, "{\"type\":\"subscription\",\"camera_id\":%d,\"event\":\"%s\",\"result\":\"0x%x\"}\n",
                camera_id, name, result);
    }
    MV_CC_SetEnumValue(handle, "EventSelector", state->old_selector);
    fflush(state->file);
    return state;
}
/* Before CloseDevice: stop accepting callbacks and restore notification settings. */
static void cw_hik_events_restore(void *handle, cw_hik_events *state) {
    if (!state) return;
    atomic_store(&state->stopping, 1);
    for (int i = 0; i < 2; ++i) if (state->enabled[i]) {
        if (MV_CC_SetEnumValueByString(handle, "EventSelector", cw_hik_event_names[i]) == MV_OK)
            MV_CC_SetEnumValue(handle, "EventNotification", state->old_notification[i]);
    }
    if (state->selector_saved) MV_CC_SetEnumValue(handle, "EventSelector", state->old_selector);
}
/* Only after CloseDevice has stopped native callbacks. */
static void cw_hik_events_destroy(cw_hik_events *state) {
    if (!state) return;
    atomic_store(&state->stopping, 1);
    pthread_join(state->thread, NULL);
    fprintf(state->file, "{\"type\":\"session_end\",\"dropped_total\":%lu}\n", atomic_load(&state->dropped));
    fclose(state->file); pthread_mutex_destroy(&state->mutex); free(state);
}
#endif
