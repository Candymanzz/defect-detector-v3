#define JSMN_STATIC
#include "jsmn.h"
#include "python_embed.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <limits.h>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <direct.h>
#include <io.h>
#else
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif
#ifdef HAVE_ARAVIS
#include <arv.h>
#endif
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
#include <MvCameraControl.h>
#endif

#define TOK_POOL 512
#define MAGIC0 'I'
#define MAGIC1 'M'
#define MAGIC2 'L'
#define MAGIC3 'B'
#define VERSION 1

#define MSG_COMMAND 1
#define MSG_RESPONSE 2
#define MSG_ERROR 3
#define RING_SLOTS 4
#define METRICS_LOG_EVERY 1000
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
#define HIK_SHARED_MAP_NAME "Global\\iml_hik_shared_frame_v1"
#define HIK_PRIMARY_MUTEX_NAME "Global\\iml_hik_primary_lock_v1"
typedef struct {
    volatile LONG seq;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t frame_bytes;
    uint64_t frame_id;
} hik_shared_header_t;
#endif

typedef struct {
    int camera_id;
    const char *detector;
    const char *ip;
    int width;
    int height;
    int stride;
    size_t frame_bytes;
    int ring_slots;
    char shm_name[64];
#ifndef _WIN32
    int shm_fd;
#else
    HANDLE shm_win_file;
    HANDLE shm_win_map;
#endif
    size_t shm_bytes;
    uint8_t *shm_base;
    uint8_t *pattern_frame;
    uint64_t next_frame_id;
    uint64_t capture_total;
    uint64_t capture_dropped;
    uint64_t latency_ns_min;
    uint64_t latency_ns_max;
    uint64_t latency_ns_sum;
    uint64_t last_frame_id;
    int last_slot_index;
    FILE *metrics_log_file;
    int python_embed_enabled;
    int python_embed_ready;
    char python_module[128];
    char python_function[64];
    char python_path[256];
    char capture_source[32];
    int frame_timeout_ms;
    uint64_t started_ns;
    int capture_backend_ready;
    char capture_backend_info[128];
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
    void *hik_handle;
    unsigned char *hik_raw_frame;
    unsigned int hik_raw_capacity;
    HANDLE hik_shared_map;
    HANDLE hik_primary_mutex;
    uint8_t *hik_shared_view;
    int hik_is_primary;
#endif
#ifdef HAVE_ARAVIS
    ArvCamera *arv_camera;
    ArvStream *arv_stream;
#endif
} worker_state_t;

static int jsoneq(const char *json, const jsmntok_t *tok, const char *s) {
    if (tok->type != JSMN_STRING) return -1;
    size_t slen = strlen(s);
    size_t tlen = (size_t)(tok->end - tok->start);
    if (slen != tlen) return -1;
    return strncmp(json + tok->start, s, tlen) == 0 ? 0 : -1;
}

static int read_file(const char *path, char **out_buf, long *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return -1;
    }
    long sz = ftell(f);
    if (sz < 0) {
        fclose(f);
        return -1;
    }
    if (fseek(f, 0, SEEK_SET) != 0) {
        fclose(f);
        return -1;
    }
    char *buf = (char *)malloc((size_t)sz + 1);
    if (!buf) {
        fclose(f);
        return -1;
    }
    size_t rd = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    if (rd != (size_t)sz) {
        free(buf);
        return -1;
    }
    buf[sz] = '\0';
    *out_buf = buf;
    *out_len = sz;
    return 0;
}

static int json_find_string(const char *js, int jslen, const char *key, char *out, size_t out_len) {
    jsmn_parser p;
    jsmntok_t tok[TOK_POOL];
    jsmn_init(&p);
    int r = jsmn_parse(&p, js, (size_t)jslen, tok, TOK_POOL);
    if (r < 1 || tok[0].type != JSMN_OBJECT) return -1;
    for (int i = 1; i < r - 1; i++) {
        if (jsoneq(js, &tok[i], key) == 0) {
            jsmntok_t v = tok[i + 1];
            int n = v.end - v.start;
            if (n < 0) return -1;
            size_t nn = (size_t)n >= out_len ? out_len - 1 : (size_t)n;
            memcpy(out, js + v.start, nn);
            out[nn] = '\0';
            return 0;
        }
    }
    return -1;
}

static int json_find_int(const char *js, int jslen, const char *key, int *out) {
    jsmn_parser p;
    jsmntok_t tok[TOK_POOL];
    jsmn_init(&p);
    int r = jsmn_parse(&p, js, (size_t)jslen, tok, TOK_POOL);
    if (r < 1 || tok[0].type != JSMN_OBJECT) return -1;
    for (int i = 1; i < r - 1; i++) {
        if (jsoneq(js, &tok[i], key) == 0) {
            jsmntok_t v = tok[i + 1];
            int n = v.end - v.start;
            if (n <= 0 || n >= 31) return -1;
            char buf[32];
            memcpy(buf, js + v.start, (size_t)n);
            buf[n] = '\0';
            *out = atoi(buf);
            return 0;
        }
    }
    return -1;
}

static void json_escape(const char *src, char *dst, size_t dst_len) {
    if (!dst || dst_len == 0) return;
    size_t o = 0;
    for (size_t i = 0; src && src[i] != '\0' && o + 2 < dst_len; i++) {
        unsigned char c = (unsigned char)src[i];
        if (c == '"' || c == '\\') {
            dst[o++] = '\\';
            dst[o++] = (char)c;
        } else if (c < 0x20) {
            dst[o++] = ' ';
        } else {
            dst[o++] = (char)c;
        }
    }
    dst[o] = '\0';
}

static uint64_t now_ns(void) {
#ifdef _WIN32
    static LARGE_INTEGER freq;
    if (freq.QuadPart == 0) {
        QueryPerformanceFrequency(&freq);
    }
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return (uint64_t)((double)counter.QuadPart * 1e9 / (double)freq.QuadPart);
#else
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
#endif
}

static int read_exact(FILE *in, uint8_t *buf, size_t n) {
    size_t off = 0;
    while (off < n) {
        size_t rd = fread(buf + off, 1, n - off, in);
        if (rd == 0) return -1;
        off += rd;
    }
    return 0;
}

static void write_u32_be(uint8_t *b, uint32_t v) {
    b[0] = (uint8_t)((v >> 24) & 0xFF);
    b[1] = (uint8_t)((v >> 16) & 0xFF);
    b[2] = (uint8_t)((v >> 8) & 0xFF);
    b[3] = (uint8_t)(v & 0xFF);
}

static uint32_t read_u32_be(const uint8_t *b) {
    return ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) | ((uint32_t)b[2] << 8) | (uint32_t)b[3];
}

static int write_message(FILE *out_stream, uint8_t msg_type, const char *header_json) {
    uint8_t prefix[16];
    size_t hlen = strlen(header_json);
    prefix[0] = MAGIC0;
    prefix[1] = MAGIC1;
    prefix[2] = MAGIC2;
    prefix[3] = MAGIC3;
    prefix[4] = VERSION;
    prefix[5] = msg_type;
    prefix[6] = 0;
    prefix[7] = 0;
    write_u32_be(prefix + 8, (uint32_t)hlen);
    write_u32_be(prefix + 12, 0);
    if (fwrite(prefix, 1, sizeof(prefix), out_stream) != sizeof(prefix)) return -1;
    if (fwrite(header_json, 1, hlen, out_stream) != hlen) return -1;
    fflush(out_stream);
    return 0;
}

static int extract_op(const char *header_json, char *op, size_t op_len) {
    return json_find_string(header_json, (int)strlen(header_json), "op", op, op_len);
}

static void build_default_python_path(char *out, size_t out_len) {
    if (!out || out_len == 0) return;
#ifdef _WIN32
    char cwd[MAX_PATH];
    if (GetCurrentDirectoryA(sizeof(cwd), cwd) == 0) {
        snprintf(out, out_len, "python-detectors\\src");
        return;
    }
    snprintf(out, out_len, "python-detectors\\src;%s\\python-detectors\\.venv\\Lib\\site-packages", cwd);
#else
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) {
        snprintf(out, out_len, "python-detectors/src");
        return;
    }
    snprintf(out, out_len, "python-detectors/src;%s/python-detectors/.venv/lib/python3.12/site-packages", cwd);
#endif
}

static void update_latency(worker_state_t *st, uint64_t latency_ns) {
    if (st->capture_total == 0 || latency_ns < st->latency_ns_min) st->latency_ns_min = latency_ns;
    if (latency_ns > st->latency_ns_max) st->latency_ns_max = latency_ns;
    st->latency_ns_sum += latency_ns;
}

static int init_metrics_log(worker_state_t *st) {
#ifdef _WIN32
    if (_mkdir("logs") != 0 && errno != EEXIST) {
        fprintf(stderr, "mkdir logs failed: %s\n", strerror(errno));
        return -1;
    }
#else
    if (mkdir("logs", 0777) != 0 && errno != EEXIST) {
        fprintf(stderr, "mkdir logs failed: %s\n", strerror(errno));
        return -1;
    }
#endif
    char log_path[128];
    snprintf(log_path, sizeof(log_path), "logs/camera-worker-metrics-cam-%d.jsonl", st->camera_id);
    st->metrics_log_file = fopen(log_path, "a");
    if (!st->metrics_log_file) {
        fprintf(stderr, "open metrics log failed: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static void log_metrics(const worker_state_t *st) {
    uint64_t avg = st->capture_total > 0 ? st->latency_ns_sum / st->capture_total : 0;
    uint64_t ts = now_ns();
    fprintf(stderr, "metrics_json camera=%d total=%" PRIu64 " dropped=%" PRIu64 " avg_ns=%" PRIu64 "\n",
            st->camera_id, st->capture_total, st->capture_dropped, avg);
    if (st->metrics_log_file) {
        fprintf(st->metrics_log_file,
                "{\"ts_ns\":%" PRIu64 ",\"camera_id\":%d,\"capture_total\":%" PRIu64
                ",\"capture_dropped\":%" PRIu64 ",\"latency_ns_min\":%" PRIu64
                ",\"latency_ns_avg\":%" PRIu64 ",\"latency_ns_max\":%" PRIu64
                ",\"last_frame_id\":%" PRIu64 ",\"last_slot_index\":%d,\"ring_slots\":%d,\"frame_bytes\":%zu}\n",
                ts,
                st->camera_id,
                st->capture_total,
                st->capture_dropped,
                st->latency_ns_min,
                avg,
                st->latency_ns_max,
                st->last_frame_id,
                st->last_slot_index,
                st->ring_slots,
                st->frame_bytes);
        fflush(st->metrics_log_file);
    }
}

static int build_pattern_frame(worker_state_t *st) {
    st->pattern_frame = (uint8_t *)malloc(st->frame_bytes);
    if (!st->pattern_frame) return -1;
    for (int y = 0; y < st->height; y++) {
        for (int x = 0; x < st->width; x++) {
            size_t i = (size_t)(y * st->width + x) * 3;
            st->pattern_frame[i + 0] = (uint8_t)((x + st->camera_id * 17) & 0xFF);
            st->pattern_frame[i + 1] = (uint8_t)(y & 0xFF);
            st->pattern_frame[i + 2] = (uint8_t)(((x + y) / 2 + st->camera_id * 3) & 0xFF);
        }
    }
    return 0;
}

#if defined(_WIN32) && defined(HAVE_HIK_MVS)
static int parse_ipv4(const char *ip, unsigned int *out) {
    if (!ip || !out) return -1;
    unsigned int a = 0, b = 0, c = 0, d = 0;
    if (sscanf(ip, "%u.%u.%u.%u", &a, &b, &c, &d) != 4) return -1;
    if (a > 255 || b > 255 || c > 255 || d > 255) return -1;
    *out = (a << 24) | (b << 16) | (c << 8) | d;
    return 0;
}

static int init_hik_shared(worker_state_t *st, char *err, size_t err_len) {
    size_t total = sizeof(hik_shared_header_t) + st->frame_bytes;
    DWORD lo = (DWORD)(total & 0xFFFFFFFFu);
    DWORD hi = (DWORD)((total >> 32u) & 0xFFFFFFFFu);
    st->hik_shared_map = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, hi, lo, HIK_SHARED_MAP_NAME);
    if (!st->hik_shared_map) {
        snprintf(err, err_len, "CreateFileMapping failed: %lu", GetLastError());
        return -1;
    }
    st->hik_shared_view = (uint8_t *)MapViewOfFile(st->hik_shared_map, FILE_MAP_ALL_ACCESS, 0, 0, total);
    if (!st->hik_shared_view) {
        snprintf(err, err_len, "MapViewOfFile failed: %lu", GetLastError());
        CloseHandle(st->hik_shared_map);
        st->hik_shared_map = NULL;
        return -1;
    }

    st->hik_primary_mutex = CreateMutexA(NULL, FALSE, HIK_PRIMARY_MUTEX_NAME);
    if (!st->hik_primary_mutex) {
        snprintf(err, err_len, "CreateMutex failed: %lu", GetLastError());
        UnmapViewOfFile(st->hik_shared_view);
        st->hik_shared_view = NULL;
        CloseHandle(st->hik_shared_map);
        st->hik_shared_map = NULL;
        return -1;
    }
    DWORD wait = WaitForSingleObject(st->hik_primary_mutex, 0);
    st->hik_is_primary = (wait == WAIT_OBJECT_0);
    return 0;
}

static void publish_hik_frame(worker_state_t *st, const uint8_t *frame, uint64_t frame_id) {
    if (!st->hik_shared_view || !frame) return;
    hik_shared_header_t *h = (hik_shared_header_t *)st->hik_shared_view;
    uint8_t *dst = st->hik_shared_view + sizeof(hik_shared_header_t);
    InterlockedIncrement(&h->seq);
    h->width = (uint32_t)st->width;
    h->height = (uint32_t)st->height;
    h->stride = (uint32_t)st->stride;
    h->frame_bytes = (uint32_t)st->frame_bytes;
    h->frame_id = frame_id;
    memcpy(dst, frame, st->frame_bytes);
    MemoryBarrier();
    InterlockedIncrement(&h->seq);
}

static int read_hik_frame_clone(worker_state_t *st, uint8_t *frame, uint64_t frame_id, char *err, size_t err_len) {
    (void)frame_id;
    if (!st->hik_shared_view) {
        snprintf(err, err_len, "hik clone map not available");
        return -1;
    }
    hik_shared_header_t *h = (hik_shared_header_t *)st->hik_shared_view;
    uint8_t *src = st->hik_shared_view + sizeof(hik_shared_header_t);
    uint64_t deadline = now_ns() + (uint64_t)(st->frame_timeout_ms <= 0 ? 1000 : st->frame_timeout_ms) * 1000000ull;
    for (;;) {
        LONG s1 = InterlockedCompareExchange((volatile LONG *)&h->seq, 0, 0);
        if ((s1 & 1) == 0 && s1 != 0) {
            if ((int)h->frame_bytes == (int)st->frame_bytes && (int)h->width == st->width && (int)h->height == st->height &&
                (int)h->stride == st->stride) {
                memcpy(frame, src, st->frame_bytes);
                MemoryBarrier();
                LONG s2 = InterlockedCompareExchange((volatile LONG *)&h->seq, 0, 0);
                if (s1 == s2 && (s2 & 1) == 0) {
                    return 0;
                }
            } else {
                snprintf(err, err_len, "hik clone dims mismatch");
                return -1;
            }
        }
        if (now_ns() > deadline) {
            snprintf(err, err_len, "hik clone timeout waiting primary");
            return -1;
        }
        Sleep(1);
    }
}

/** Автобаланс белого выключает «плывущий» цвет между кадрами (GenICam BalanceWhiteAuto). */
static void hik_disable_auto_white_balance(void *handle) {
    int r = MV_CC_SetEnumValueByString(handle, "BalanceWhiteAuto", "Off");
    if (r != MV_OK) {
        /* У части камер Off = 0 (Continuous=2, Once=1). */
        r = MV_CC_SetEnumValue(handle, "BalanceWhiteAuto", 0);
    }
    if (r != MV_OK)
        fprintf(stderr, "hik: BalanceWhiteAuto=Off не применён (0x%x), модель может не поддерживать узел\n", r);
    else
        fprintf(stderr, "hik: BalanceWhiteAuto=Off\n");
}

static int init_hik_mvs(worker_state_t *st, char *err, size_t err_len) {
    if (init_hik_shared(st, err, err_len) != 0) {
        return -1;
    }
    if (!st->hik_is_primary) {
        st->capture_backend_ready = 1;
        snprintf(st->capture_backend_info, sizeof(st->capture_backend_info), "hik_clone");
        return 0;
    }

    int nRet = MV_CC_Initialize();
    if (nRet != MV_OK) {
        snprintf(err, err_len, "MV_CC_Initialize failed: 0x%x", nRet);
        return -1;
    }

    MV_CC_DEVICE_INFO_LIST devList;
    memset(&devList, 0, sizeof(devList));
    nRet = MV_CC_EnumDevices((unsigned int)(MV_GIGE_DEVICE | MV_USB_DEVICE), &devList);
    if (nRet != MV_OK || devList.nDeviceNum == 0) {
        snprintf(err, err_len, "MV_CC_EnumDevices failed or empty: ret=0x%x count=%u", nRet, devList.nDeviceNum);
        MV_CC_Finalize();
        return -1;
    }

    unsigned int wantedIp = 0;
    int haveWantedIp = parse_ipv4(st->ip, &wantedIp) == 0;
    unsigned int selected = 0;
    int opened = 0;
    int preferredFirst = haveWantedIp ? 1 : 0;
    for (int pass = 0; pass < 2 && !opened; pass++) {
        for (unsigned int i = 0; i < devList.nDeviceNum && !opened; i++) {
            MV_CC_DEVICE_INFO *info = devList.pDeviceInfo[i];
            if (!info) continue;
            int isPreferred = 0;
            if (haveWantedIp && info->nTLayerType == MV_GIGE_DEVICE && info->SpecialInfo.stGigEInfo.nCurrentIp == wantedIp) {
                isPreferred = 1;
            }
            if ((pass == 0 && preferredFirst && !isPreferred) || (pass == 1 && preferredFirst && isPreferred)) {
                continue;
            }
            nRet = MV_CC_CreateHandle(&st->hik_handle, info);
            if (nRet != MV_OK || !st->hik_handle) {
                st->hik_handle = NULL;
                continue;
            }
            int openRet = -1;
            for (int attempt = 0; attempt < 4; attempt++) {
                openRet = MV_CC_OpenDevice(st->hik_handle, MV_ACCESS_Exclusive, 0);
                if (openRet == MV_OK) {
                    selected = i;
                    opened = 1;
                    nRet = openRet;
                    break;
                }
                Sleep(250);
            }
            if (!opened) {
                MV_CC_DestroyHandle(st->hik_handle);
                st->hik_handle = NULL;
            }
        }
    }
    if (!opened) {
        snprintf(err, err_len, "MV_CC_OpenDevice failed: 0x%x", nRet);
        MV_CC_Finalize();
        return -1;
    }

    if (devList.pDeviceInfo[selected]->nTLayerType == MV_GIGE_DEVICE) {
        int packetSize = MV_CC_GetOptimalPacketSize(st->hik_handle);
        if (packetSize > 0) {
            (void)MV_CC_SetIntValue(st->hik_handle, "GevSCPSPacketSize", (unsigned int)packetSize);
        }
    }
    (void)MV_CC_SetEnumValue(st->hik_handle, "TriggerMode", 0);
    (void)MV_CC_SetEnumValueByString(st->hik_handle, "PixelFormat", "RGB8Packed");
    hik_disable_auto_white_balance(st->hik_handle);

    MVCC_INTVALUE payload;
    memset(&payload, 0, sizeof(payload));
    nRet = MV_CC_GetIntValue(st->hik_handle, "PayloadSize", &payload);
    if (nRet != MV_OK || payload.nCurValue == 0) {
        snprintf(err, err_len, "MV_CC_GetIntValue(PayloadSize) failed: 0x%x", nRet);
        MV_CC_CloseDevice(st->hik_handle);
        MV_CC_DestroyHandle(st->hik_handle);
        st->hik_handle = NULL;
        MV_CC_Finalize();
        return -1;
    }
    st->hik_raw_capacity = payload.nCurValue;
    st->hik_raw_frame = (unsigned char *)malloc(st->hik_raw_capacity);
    if (!st->hik_raw_frame) {
        snprintf(err, err_len, "malloc hik frame failed: %u", st->hik_raw_capacity);
        MV_CC_CloseDevice(st->hik_handle);
        MV_CC_DestroyHandle(st->hik_handle);
        st->hik_handle = NULL;
        MV_CC_Finalize();
        return -1;
    }

    nRet = MV_CC_StartGrabbing(st->hik_handle);
    if (nRet != MV_OK) {
        snprintf(err, err_len, "MV_CC_StartGrabbing failed: 0x%x", nRet);
        free(st->hik_raw_frame);
        st->hik_raw_frame = NULL;
        st->hik_raw_capacity = 0;
        MV_CC_CloseDevice(st->hik_handle);
        MV_CC_DestroyHandle(st->hik_handle);
        st->hik_handle = NULL;
        MV_CC_Finalize();
        return -1;
    }

    st->capture_backend_ready = 1;
    snprintf(st->capture_backend_info, sizeof(st->capture_backend_info), "hik_mvs_primary");
    return 0;
}

static void shutdown_hik_mvs(worker_state_t *st) {
    if (st->hik_handle) {
        (void)MV_CC_StopGrabbing(st->hik_handle);
        (void)MV_CC_CloseDevice(st->hik_handle);
        (void)MV_CC_DestroyHandle(st->hik_handle);
        st->hik_handle = NULL;
    }
    if (st->hik_raw_frame) {
        free(st->hik_raw_frame);
        st->hik_raw_frame = NULL;
    }
    st->hik_raw_capacity = 0;
    if (st->hik_is_primary) {
        (void)MV_CC_Finalize();
    }
    if (st->hik_primary_mutex) {
        if (st->hik_is_primary) {
            ReleaseMutex(st->hik_primary_mutex);
        }
        CloseHandle(st->hik_primary_mutex);
        st->hik_primary_mutex = NULL;
    }
    if (st->hik_shared_view) {
        UnmapViewOfFile(st->hik_shared_view);
        st->hik_shared_view = NULL;
    }
    if (st->hik_shared_map) {
        CloseHandle(st->hik_shared_map);
        st->hik_shared_map = NULL;
    }
    st->hik_is_primary = 0;
}
#endif /* _WIN32 && HAVE_HIK_MVS */

#ifdef HAVE_ARAVIS
static int init_aravis(worker_state_t *st, char *err, size_t err_len) {
    arv_update_device_list();
    GError *error = NULL;
    st->arv_camera = arv_camera_new(NULL, &error);
    if (!st->arv_camera) {
        snprintf(err, err_len, "aravis camera open failed: %s", error ? error->message : "no camera");
        if (error) g_clear_error(&error);
        return -1;
    }

    ArvDevice *device = arv_camera_get_device(st->arv_camera);
    if (device) {
        arv_device_set_integer_feature_value(device, "GevSCPSPacketSize", 1500, NULL);
    }
    arv_camera_set_pixel_format(st->arv_camera, ARV_PIXEL_FORMAT_MONO_8, &error);
    if (error) {
        g_clear_error(&error);
    }

    st->arv_stream = arv_camera_create_stream(st->arv_camera, NULL, NULL, &error);
    if (!st->arv_stream) {
        snprintf(err, err_len, "aravis stream failed: %s", error ? error->message : "create stream");
        if (error) g_clear_error(&error);
        g_object_unref(st->arv_camera);
        st->arv_camera = NULL;
        return -1;
    }

    int payload = arv_camera_get_payload(st->arv_camera, &error);
    if (payload <= 0 || error) {
        snprintf(err, err_len, "aravis payload failed: %s", error ? error->message : "invalid payload");
        if (error) g_clear_error(&error);
        g_object_unref(st->arv_stream);
        g_object_unref(st->arv_camera);
        st->arv_stream = NULL;
        st->arv_camera = NULL;
        return -1;
    }
    for (int i = 0; i < 15; i++) {
        arv_stream_push_buffer(st->arv_stream, arv_buffer_new(payload, NULL));
    }

    arv_camera_start_acquisition(st->arv_camera, &error);
    if (error) {
        snprintf(err, err_len, "aravis start acquisition failed: %s", error->message);
        g_clear_error(&error);
        g_object_unref(st->arv_stream);
        g_object_unref(st->arv_camera);
        st->arv_stream = NULL;
        st->arv_camera = NULL;
        return -1;
    }
    const char *model = arv_camera_get_model_name(st->arv_camera, NULL);
    st->capture_backend_ready = 1;
    snprintf(st->capture_backend_info, sizeof(st->capture_backend_info), "aravis:%s", model ? model : "unknown");
    return 0;
}

static void shutdown_aravis(worker_state_t *st) {
    if (st->arv_camera) {
        arv_camera_stop_acquisition(st->arv_camera, NULL);
    }
    if (st->arv_stream) {
        g_object_unref(st->arv_stream);
        st->arv_stream = NULL;
    }
    if (st->arv_camera) {
        g_object_unref(st->arv_camera);
        st->arv_camera = NULL;
    }
}
#endif

static int capture_from_source(worker_state_t *st, uint8_t *frame, uint64_t frame_id, char *err, size_t err_len) {
    if (strcmp(st->capture_source, "hik") == 0) {
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
        if (!st->hik_is_primary) {
            return read_hik_frame_clone(st, frame, frame_id, err, err_len);
        }
        MV_FRAME_OUT_INFO_EX info;
        memset(&info, 0, sizeof(info));
        int nRet = MV_CC_GetOneFrameTimeout(st->hik_handle, st->hik_raw_frame, st->hik_raw_capacity, &info,
                                            (unsigned int)st->frame_timeout_ms);
        if (nRet != MV_OK) {
            snprintf(err, err_len, "hik timeout/error: 0x%x", nRet);
            return -1;
        }
        if ((int)info.nWidth != st->width || (int)info.nHeight != st->height) {
            snprintf(err, err_len, "hik frame dims mismatch %ux%u", info.nWidth, info.nHeight);
            return -1;
        }
        if (info.enPixelType == PixelType_Gvsp_Mono8) {
            for (int y = 0; y < st->height; y++) {
                for (int x = 0; x < st->width; x++) {
                    uint8_t v = st->hik_raw_frame[(size_t)y * (size_t)st->width + (size_t)x];
                    size_t i = (size_t)(y * st->width + x) * 3;
                    frame[i + 0] = v;
                    frame[i + 1] = v;
                    frame[i + 2] = v;
                }
            }
            publish_hik_frame(st, frame, frame_id);
            return 0;
        }
        if (info.enPixelType == PixelType_Gvsp_RGB8_Packed) {
            for (int y = 0; y < st->height; y++) {
                for (int x = 0; x < st->width; x++) {
                    size_t src = ((size_t)y * (size_t)st->width + (size_t)x) * 3;
                    size_t dst = src;
                    frame[dst + 0] = st->hik_raw_frame[src + 2];
                    frame[dst + 1] = st->hik_raw_frame[src + 1];
                    frame[dst + 2] = st->hik_raw_frame[src + 0];
                }
            }
            publish_hik_frame(st, frame, frame_id);
            return 0;
        }
        snprintf(err, err_len, "hik unsupported pixel type: 0x%x", info.enPixelType);
        return -1;
#else
        snprintf(err, err_len, "hik source requested but camera-worker built without MVS SDK (Windows)");
        return -1;
#endif
    }
    if (strcmp(st->capture_source, "aravis") == 0) {
#ifdef HAVE_ARAVIS
        uint64_t timeout_us = (uint64_t)(st->frame_timeout_ms <= 0 ? 1000 : st->frame_timeout_ms) * 1000ull;
        ArvBuffer *buffer = arv_stream_timeout_pop_buffer(st->arv_stream, timeout_us);
        if (!buffer) {
            snprintf(err, err_len, "aravis timeout");
            return -1;
        }
        if (arv_buffer_get_status(buffer) != ARV_BUFFER_STATUS_SUCCESS) {
            arv_stream_push_buffer(st->arv_stream, buffer);
            snprintf(err, err_len, "aravis frame status error");
            return -1;
        }
        size_t size = 0;
        const uint8_t *data = (const uint8_t *)arv_buffer_get_data(buffer, &size);
        int width = arv_buffer_get_image_width(buffer);
        int height = arv_buffer_get_image_height(buffer);
        if (width <= 0 || height <= 0 || !data) {
            arv_stream_push_buffer(st->arv_stream, buffer);
            snprintf(err, err_len, "aravis invalid frame");
            return -1;
        }
        size_t needed = (size_t)st->stride * (size_t)st->height;
        if (st->width != width || st->height != height || size < (size_t)width * (size_t)height) {
            arv_stream_push_buffer(st->arv_stream, buffer);
            snprintf(err, err_len, "aravis frame dims mismatch %dx%d", width, height);
            return -1;
        }
        // Convert mono8 -> BGR8 for current pipeline compatibility.
        for (int y = 0; y < st->height; y++) {
            for (int x = 0; x < st->width; x++) {
                uint8_t v = data[(size_t)y * (size_t)width + (size_t)x];
                size_t i = (size_t)(y * st->width + x) * 3;
                frame[i + 0] = v;
                frame[i + 1] = v;
                frame[i + 2] = v;
            }
        }
        (void)needed;
        arv_stream_push_buffer(st->arv_stream, buffer);
        return 0;
#else
        snprintf(err, err_len, "aravis source requested but camera-worker built without aravis");
        return -1;
#endif
    }
    memcpy(frame, st->pattern_frame, st->frame_bytes);
    frame[1] = (uint8_t)(frame_id & 0xFF);
    frame[4] = (uint8_t)((frame_id >> 8) & 0xFF);
    return 0;
}

static int init_worker_state(worker_state_t *st, int camera_id, const char *detector, const char *ip,
                             const char *python_module, const char *python_function, const char *python_path,
                             const char *capture_source, int frame_timeout_ms) {
    memset(st, 0, sizeof(*st));
    st->camera_id = camera_id;
    st->detector = detector;
    st->ip = ip;
    st->width = 2448;
    st->height = 2048;
    st->stride = st->width * 3;
    st->frame_bytes = (size_t)st->stride * (size_t)st->height;
    st->ring_slots = RING_SLOTS;
    st->next_frame_id = 1;
    st->last_slot_index = -1;
    st->shm_bytes = st->frame_bytes * (size_t)st->ring_slots;
#ifndef _WIN32
    st->shm_fd = -1;
#else
    st->shm_win_file = INVALID_HANDLE_VALUE;
    st->shm_win_map = NULL;
#endif
    st->python_embed_enabled = 1;
    st->frame_timeout_ms = frame_timeout_ms > 0 ? frame_timeout_ms : 1000;
    st->started_ns = now_ns();
    st->capture_backend_ready = 0;
    snprintf(st->capture_backend_info, sizeof(st->capture_backend_info), "not_initialized");
    snprintf(st->capture_source, sizeof(st->capture_source), "%s",
             (capture_source && capture_source[0] != '\0') ? capture_source : "fake");
    snprintf(st->python_module, sizeof(st->python_module), "%s",
             (python_module && python_module[0] != '\0') ? python_module : "embedded_detector");
    snprintf(st->python_function, sizeof(st->python_function), "%s",
             (python_function && python_function[0] != '\0') ? python_function : "detect");
    if (python_path && python_path[0] != '\0') {
        snprintf(st->python_path, sizeof(st->python_path), "%s", python_path);
    } else {
        build_default_python_path(st->python_path, sizeof(st->python_path));
    }

    snprintf(st->shm_name, sizeof(st->shm_name), "/iml_cam_%d_frame", camera_id);
#ifndef _WIN32
    st->shm_fd = shm_open(st->shm_name, O_CREAT | O_RDWR, 0666);
    if (st->shm_fd < 0) {
        fprintf(stderr, "shm_open failed: %s\n", strerror(errno));
        return 1;
    }
    if (ftruncate(st->shm_fd, (off_t)st->shm_bytes) != 0) {
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errno));
        close(st->shm_fd);
        shm_unlink(st->shm_name);
        return 1;
    }
    st->shm_base = (uint8_t *)mmap(NULL, st->shm_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, st->shm_fd, 0);
    if (st->shm_base == MAP_FAILED) {
        fprintf(stderr, "mmap failed: %s\n", strerror(errno));
        close(st->shm_fd);
        shm_unlink(st->shm_name);
        return 1;
    }
#else
    char local_app[MAX_PATH];
    DWORD n = GetEnvironmentVariableA("LOCALAPPDATA", local_app, sizeof(local_app));
    if (n == 0 || n >= sizeof(local_app)) {
        n = GetEnvironmentVariableA("TEMP", local_app, sizeof(local_app));
    }
    if (n == 0 || n >= sizeof(local_app)) {
        fprintf(stderr, "LOCALAPPDATA/TEMP not set\n");
        return 1;
    }
    char shm_dir[MAX_PATH];
    snprintf(shm_dir, sizeof(shm_dir), "%s\\iml_shm", local_app);
    if (!CreateDirectoryA(shm_dir, NULL)) {
        DWORD err = GetLastError();
        if (err != ERROR_ALREADY_EXISTS) {
            fprintf(stderr, "CreateDirectoryA failed: %lu\n", (unsigned long)err);
            return 1;
        }
    }
    char shm_path[MAX_PATH];
    snprintf(shm_path, sizeof(shm_path), "%s\\iml_cam_%d_frame", shm_dir, camera_id);
    st->shm_win_file = CreateFileA(
            shm_path,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL,
            CREATE_ALWAYS,
            FILE_ATTRIBUTE_NORMAL,
            NULL);
    if (st->shm_win_file == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "CreateFileA shm failed: %lu\n", (unsigned long)GetLastError());
        return 1;
    }
    st->shm_win_map = CreateFileMappingA(st->shm_win_file, NULL, PAGE_READWRITE,
                                          (DWORD)(st->shm_bytes >> 32), (DWORD)(st->shm_bytes & 0xffffffffu), NULL);
    if (!st->shm_win_map) {
        fprintf(stderr, "CreateFileMappingA failed: %lu\n", (unsigned long)GetLastError());
        CloseHandle(st->shm_win_file);
        st->shm_win_file = INVALID_HANDLE_VALUE;
        return 1;
    }
    st->shm_base = (uint8_t *)MapViewOfFile(st->shm_win_map, FILE_MAP_ALL_ACCESS, 0, 0, st->shm_bytes);
    if (!st->shm_base) {
        fprintf(stderr, "MapViewOfFile failed: %lu\n", (unsigned long)GetLastError());
        CloseHandle(st->shm_win_map);
        CloseHandle(st->shm_win_file);
        st->shm_win_map = NULL;
        st->shm_win_file = INVALID_HANDLE_VALUE;
        return 1;
    }
#endif
    if (build_pattern_frame(st) != 0) {
        fprintf(stderr, "pattern allocation failed\n");
#ifndef _WIN32
        munmap(st->shm_base, st->shm_bytes);
        close(st->shm_fd);
        shm_unlink(st->shm_name);
        st->shm_base = NULL;
        st->shm_fd = -1;
#else
        UnmapViewOfFile(st->shm_base);
        CloseHandle(st->shm_win_map);
        CloseHandle(st->shm_win_file);
        st->shm_base = NULL;
        st->shm_win_map = NULL;
        st->shm_win_file = INVALID_HANDLE_VALUE;
#endif
        return 1;
    }
    if (strcmp(st->capture_source, "fake") == 0) {
        st->capture_backend_ready = 1;
        snprintf(st->capture_backend_info, sizeof(st->capture_backend_info), "fake_pattern");
    }
#ifdef HAVE_ARAVIS
    if (strcmp(st->capture_source, "aravis") == 0) {
        char src_err[256] = {0};
        if (init_aravis(st, src_err, sizeof(src_err)) != 0) {
            fprintf(stderr, "capture source init failed: %s\n", src_err);
            return 1;
        }
    }
#endif
    if (strcmp(st->capture_source, "aravis") == 0) {
#ifndef HAVE_ARAVIS
        fprintf(stderr, "capture source init failed: aravis backend not compiled\n");
        return 1;
#endif
    }
    if (strcmp(st->capture_source, "hik") == 0) {
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
        char src_err[256] = {0};
        if (init_hik_mvs(st, src_err, sizeof(src_err)) != 0) {
            fprintf(stderr, "capture source init failed: %s\n", src_err);
            return 1;
        }
#else
        fprintf(stderr, "capture source init failed: hik backend needs Windows + MVS SDK (see camera-worker/CMakeLists.txt)\n");
        return 1;
#endif
    }
    if (init_metrics_log(st) != 0) {
        free(st->pattern_frame);
#ifndef _WIN32
        munmap(st->shm_base, st->shm_bytes);
        close(st->shm_fd);
        shm_unlink(st->shm_name);
        st->pattern_frame = NULL;
        st->shm_base = NULL;
        st->shm_fd = -1;
#else
        UnmapViewOfFile(st->shm_base);
        CloseHandle(st->shm_win_map);
        CloseHandle(st->shm_win_file);
        st->pattern_frame = NULL;
        st->shm_base = NULL;
        st->shm_win_map = NULL;
        st->shm_win_file = INVALID_HANDLE_VALUE;
#endif
        return 1;
    }
    if (st->python_embed_enabled) {
        char py_err[256] = {0};
        if (py_embed_init(st->python_module, st->python_function, st->python_path, st->detector, py_err, sizeof(py_err)) != 0) {
            fprintf(stderr, "python embed init failed: %s\n", py_err[0] ? py_err : "unknown");
            if (st->metrics_log_file) {
                fclose(st->metrics_log_file);
                st->metrics_log_file = NULL;
            }
            if (st->pattern_frame) {
                free(st->pattern_frame);
                st->pattern_frame = NULL;
            }
#ifndef _WIN32
            if (st->shm_base && st->shm_base != MAP_FAILED) {
                munmap(st->shm_base, st->shm_bytes);
                st->shm_base = NULL;
            }
            if (st->shm_fd >= 0) {
                close(st->shm_fd);
                st->shm_fd = -1;
            }
            if (st->shm_name[0] != '\0') {
                shm_unlink(st->shm_name);
            }
#else
            if (st->shm_base) {
                UnmapViewOfFile(st->shm_base);
                st->shm_base = NULL;
            }
            if (st->shm_win_map) {
                CloseHandle(st->shm_win_map);
                st->shm_win_map = NULL;
            }
            if (st->shm_win_file != INVALID_HANDLE_VALUE) {
                CloseHandle(st->shm_win_file);
                st->shm_win_file = INVALID_HANDLE_VALUE;
            }
#endif
            return 1;
        }
        st->python_embed_ready = 1;
    }
    return 0;
}

static void destroy_worker_state(worker_state_t *st) {
#ifndef _WIN32
    if (st->shm_base && st->shm_base != MAP_FAILED) {
        munmap(st->shm_base, st->shm_bytes);
        st->shm_base = NULL;
    }
#else
    if (st->shm_base) {
        UnmapViewOfFile(st->shm_base);
        st->shm_base = NULL;
    }
#endif
    if (st->pattern_frame) {
        free(st->pattern_frame);
        st->pattern_frame = NULL;
    }
    if (st->metrics_log_file) {
        fclose(st->metrics_log_file);
        st->metrics_log_file = NULL;
    }
#ifndef _WIN32
    if (st->shm_fd >= 0) {
        close(st->shm_fd);
        st->shm_fd = -1;
    }
    if (st->shm_name[0] != '\0') {
        shm_unlink(st->shm_name);
    }
#else
    if (st->shm_win_map) {
        CloseHandle(st->shm_win_map);
        st->shm_win_map = NULL;
    }
    if (st->shm_win_file != INVALID_HANDLE_VALUE) {
        CloseHandle(st->shm_win_file);
        st->shm_win_file = INVALID_HANDLE_VALUE;
    }
#endif
    if (st->python_embed_ready) {
        py_embed_shutdown();
        st->python_embed_ready = 0;
    }
#ifdef HAVE_ARAVIS
    shutdown_aravis(st);
#endif
#if defined(_WIN32) && defined(HAVE_HIK_MVS)
    shutdown_hik_mvs(st);
#endif
}

static int run_binary_loop_io(FILE *in_stream, FILE *out_stream, int camera_id, const char *detector, const char *ip,
                              const char *python_module, const char *python_function, const char *python_path,
                              const char *capture_source, int frame_timeout_ms) {
    worker_state_t st;
    if (init_worker_state(&st, camera_id, detector, ip, python_module, python_function, python_path,
                          capture_source, frame_timeout_ms) != 0) return 1;

    char header_buf[1024];

    for (;;) {
        uint8_t prefix[16];
        if (read_exact(in_stream, prefix, sizeof(prefix)) != 0) {
            break;
        }
        if (prefix[0] != MAGIC0 || prefix[1] != MAGIC1 || prefix[2] != MAGIC2 || prefix[3] != MAGIC3) {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"bad_magic\"}");
            continue;
        }
        if (prefix[4] != VERSION) {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"bad_version\"}");
            continue;
        }

        uint8_t msg_type = prefix[5];
        uint32_t header_len = read_u32_be(prefix + 8);
        uint32_t payload_len = read_u32_be(prefix + 12);

        if (header_len >= sizeof(header_buf)) {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"header_too_large\"}");
            if (payload_len > 0) {
                uint8_t tmp[256];
                uint32_t left = payload_len;
                while (left > 0) {
                    uint32_t chunk = left > sizeof(tmp) ? (uint32_t)sizeof(tmp) : left;
                    if (read_exact(in_stream, tmp, chunk) != 0) break;
                    left -= chunk;
                }
            }
            continue;
        }
        if (read_exact(in_stream, (uint8_t *)header_buf, header_len) != 0) {
            break;
        }
        header_buf[header_len] = '\0';

        if (payload_len > 0) {
            uint8_t tmp[256];
            uint32_t left = payload_len;
            while (left > 0) {
                uint32_t chunk = left > sizeof(tmp) ? (uint32_t)sizeof(tmp) : left;
                    if (read_exact(in_stream, tmp, chunk) != 0) {
                    left = UINT32_MAX;
                    break;
                }
                left -= chunk;
            }
            if (left != 0) {
                break;
            }
        }

        if (msg_type != MSG_COMMAND) {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"unexpected_msg_type\"}");
            continue;
        }

        char op[64] = {0};
        if (extract_op(header_buf, op, sizeof(op)) != 0) {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"missing_op\"}");
            continue;
        }

        if (strcmp(op, "health") == 0) {
            uint64_t avg_latency = st.capture_total > 0 ? st.latency_ns_sum / st.capture_total : 0;
            double elapsed = (double)(now_ns() - st.started_ns) / 1e9;
            double fps = elapsed > 0.0 ? (double)st.capture_total / elapsed : 0.0;
            char out[1536];
            snprintf(out, sizeof(out),
                     "{\"status\":\"ok\",\"service\":\"camera-worker\",\"camera_id\":%d,\"detector\":\"%s\",\"ip\":\"%s\","
                     "\"shm_name\":\"%s\",\"ring_slots\":%d,\"capture_total\":%llu,\"capture_dropped\":%llu,"
                     "\"latency_ns_min\":%llu,\"latency_ns_avg\":%llu,\"latency_ns_max\":%llu,"
                     "\"last_frame_id\":%llu,\"last_slot_index\":%d,"
                     "\"capture_source\":\"%s\",\"capture_backend_ready\":%s,\"capture_backend_info\":\"%s\","
                     "\"frame_timeout_ms\":%d,\"fps\":%.3f,"
                     "\"python_embed\":{\"enabled\":%s,\"ready\":%s,\"module\":\"%s\",\"function\":\"%s\",\"version\":\"%s\"}}",
                     st.camera_id,
                     st.detector,
                     st.ip,
                     st.shm_name,
                     st.ring_slots,
                     (unsigned long long)st.capture_total,
                     (unsigned long long)st.capture_dropped,
                     (unsigned long long)st.latency_ns_min,
                     (unsigned long long)avg_latency,
                     (unsigned long long)st.latency_ns_max,
                     (unsigned long long)st.last_frame_id,
                     st.last_slot_index,
                     st.capture_source,
                     st.capture_backend_ready ? "true" : "false",
                     st.capture_backend_info,
                     st.frame_timeout_ms,
                     fps,
                     st.python_embed_enabled ? "true" : "false",
                     st.python_embed_ready ? "true" : "false",
                     st.python_module,
                     st.python_function,
                     st.python_embed_ready ? py_embed_python_version() : "n/a");
            write_message(out_stream, MSG_RESPONSE, out);
        } else if (strcmp(op, "capture") == 0) {
            uint64_t capture_started_ns = now_ns();
            uint64_t frame_id = st.next_frame_id;
            int slot_index = (int)((frame_id - 1) % (uint64_t)st.ring_slots);
            size_t slot_offset = (size_t)slot_index * st.frame_bytes;
            uint8_t *frame = st.shm_base + slot_offset;
            char cap_err[256] = {0};
            if (capture_from_source(&st, frame, frame_id, cap_err, sizeof(cap_err)) != 0) {
                st.capture_dropped++;
                char escaped[320];
                json_escape(cap_err[0] ? cap_err : "capture_failed", escaped, sizeof(escaped));
                char out_err[512];
                snprintf(out_err, sizeof(out_err), "{\"error\":\"capture_failed\",\"reason\":\"%s\",\"source\":\"%s\"}",
                         escaped, st.capture_source);
                write_message(out_stream, MSG_ERROR, out_err);
                continue;
            }
#ifdef _WIN32
            FlushViewOfFile(frame, st.frame_bytes);
#endif
            uint64_t timestamp_ns = now_ns();
            update_latency(&st, timestamp_ns - capture_started_ns);
            st.capture_total++;
            st.last_frame_id = frame_id;
            st.last_slot_index = slot_index;
            st.next_frame_id++;
            if ((st.capture_total % METRICS_LOG_EVERY) == 0) {
                log_metrics(&st);
            }

            py_detect_result_t py_res;
            memset(&py_res, 0, sizeof(py_res));
            py_res.ok = 0;
            snprintf(py_res.status, sizeof(py_res.status), "ERROR");
            snprintf(py_res.message, sizeof(py_res.message), "python_embed_not_ready");
            char py_err[256] = {0};
            if (st.python_embed_ready) {
                if (py_embed_detect(frame, st.width, st.height, st.stride, frame_id, &py_res, py_err, sizeof(py_err)) != 0) {
                    py_res.ok = 0;
                    snprintf(py_res.status, sizeof(py_res.status), "ERROR");
                    snprintf(py_res.message, sizeof(py_res.message), "%s", py_err[0] ? py_err : "python_detect_failed");
                    py_res.anomaly_score = 0.0;
                }
            }
            char escaped_msg[512];
            json_escape(py_res.message, escaped_msg, sizeof(escaped_msg));
            char out[1200];
            snprintf(out, sizeof(out),
                     "{\"camera_id\":%d,\"frame_id\":%llu,\"slot_index\":%d,\"width\":%d,\"height\":%d,\"stride\":%d,\"format\":\"BGR8\",\"timestamp_ns\":%llu,\"shm_name\":\"%s\",\"shm_offset\":%zu,\"frame_bytes\":%zu,\"ring_slots\":%d,"
                     "\"detector_result\":{\"ok\":%s,\"status\":\"%s\",\"anomaly_score\":%.6f,\"message\":\"%s\"}}",
                     st.camera_id,
                     (unsigned long long)frame_id,
                     slot_index,
                     st.width,
                     st.height,
                     st.stride,
                     (unsigned long long)timestamp_ns,
                     st.shm_name,
                     slot_offset,
                     st.frame_bytes,
                     st.ring_slots,
                     py_res.ok ? "true" : "false",
                     py_res.status,
                     py_res.anomaly_score,
                     escaped_msg);
            write_message(out_stream, MSG_RESPONSE, out);
        } else if (strcmp(op, "set_reference") == 0) {
            uint64_t frame_id = st.last_frame_id;
            if (frame_id == 0) {
                write_message(out_stream, MSG_ERROR, "{\"error\":\"no_frame_available_for_reference\"}");
                continue;
            }
            int slot_index = st.last_slot_index >= 0 ? st.last_slot_index : 0;
            size_t slot_offset = (size_t)slot_index * st.frame_bytes;
            uint8_t *frame = st.shm_base + slot_offset;
            char py_err[256] = {0};
            if (!st.python_embed_ready ||
                py_embed_set_reference(frame, st.width, st.height, st.stride, "default", py_err, sizeof(py_err)) != 0) {
                char escaped[320];
                json_escape(py_err[0] ? py_err : "python_set_reference_failed", escaped, sizeof(escaped));
                char out[448];
                snprintf(out, sizeof(out), "{\"error\":\"%s\"}", escaped);
                write_message(out_stream, MSG_ERROR, out);
                continue;
            }
            write_message(out_stream, MSG_RESPONSE, "{\"status\":\"ok\",\"product_type\":\"default\"}");
        } else if (strcmp(op, "inject_exit") == 0) {
            fflush(out_stream);
            destroy_worker_state(&st);
            _exit(42);
        } else if (strcmp(op, "inject_timeout_ms") == 0) {
            int ms = 1000;
            int parsed = 0;
            if (json_find_int(header_buf, (int)strlen(header_buf), "timeout_ms", &parsed) == 0 && parsed > 0) {
                ms = parsed;
            }
#ifdef _WIN32
            Sleep((DWORD)ms);
#else
            usleep((useconds_t)ms * 1000u);
#endif
            write_message(out_stream, MSG_RESPONSE, "{\"status\":\"timeout_injected\"}");
        } else if (strcmp(op, "inject_broken_response") == 0) {
            const char *broken = "BROKEN_RESPONSE";
            fwrite(broken, 1, strlen(broken), out_stream);
            fflush(out_stream);
        } else if (strcmp(op, "stop") == 0) {
            log_metrics(&st);
            write_message(out_stream, MSG_RESPONSE, "{\"status\":\"stopped\"}");
            break;
        } else {
            write_message(out_stream, MSG_ERROR, "{\"error\":\"unknown_op\"}");
        }
    }

    destroy_worker_state(&st);
    return 0;
}

static int run_binary_loop(int camera_id, const char *detector, const char *ip, const char *python_module,
                           const char *python_function, const char *python_path, const char *capture_source,
                           int frame_timeout_ms) {
    return run_binary_loop_io(stdin, stdout, camera_id, detector, ip, python_module, python_function, python_path,
                              capture_source, frame_timeout_ms);
}

static int run_named_pipe_loop(const char *pipe_base_path, int camera_id, const char *detector, const char *ip,
                               const char *python_module, const char *python_function, const char *python_path,
                               const char *capture_source, int frame_timeout_ms) {
    if (!pipe_base_path || pipe_base_path[0] == '\0') {
        fprintf(stderr, "named pipe path is required\n");
        return 1;
    }
#ifdef _WIN32
    fprintf(stderr, "named pipe mode is not implemented in camera-worker on this platform build\n");
    (void)camera_id;
    (void)detector;
    (void)ip;
    (void)python_module;
    (void)python_function;
    (void)python_path;
    (void)capture_source;
    (void)frame_timeout_ms;
    return 1;
#else
    char cmd_pipe[512];
    char resp_pipe[512];
    snprintf(cmd_pipe, sizeof(cmd_pipe), "%s.cmd", pipe_base_path);
    snprintf(resp_pipe, sizeof(resp_pipe), "%s.resp", pipe_base_path);
    if (mkfifo(cmd_pipe, 0666) != 0 && errno != EEXIST) {
        fprintf(stderr, "mkfifo failed for %s: %s\n", cmd_pipe, strerror(errno));
        return 1;
    }
    if (mkfifo(resp_pipe, 0666) != 0 && errno != EEXIST) {
        fprintf(stderr, "mkfifo failed for %s: %s\n", resp_pipe, strerror(errno));
        unlink(cmd_pipe);
        return 1;
    }
    FILE *in_stream = fopen(cmd_pipe, "rb");
    if (!in_stream) {
        fprintf(stderr, "fopen named cmd pipe failed for %s: %s\n", cmd_pipe, strerror(errno));
        unlink(cmd_pipe);
        unlink(resp_pipe);
        return 1;
    }
    FILE *out_stream = fopen(resp_pipe, "wb");
    if (!out_stream) {
        fprintf(stderr, "fopen named resp pipe failed for %s: %s\n", resp_pipe, strerror(errno));
        fclose(in_stream);
        unlink(cmd_pipe);
        unlink(resp_pipe);
        return 1;
    }
    int rc = run_binary_loop_io(in_stream, out_stream, camera_id, detector, ip, python_module, python_function,
                                python_path, capture_source, frame_timeout_ms);
    fclose(in_stream);
    fclose(out_stream);
    unlink(cmd_pipe);
    unlink(resp_pipe);
    return rc;
#endif
}

int main(int argc, char **argv) {
    const char *path = (argc > 1) ? argv[1] : "config/config.json";
    int camera_id = (argc > 2) ? atoi(argv[2]) : 0;
    int binary_mode = (argc > 3 && strcmp(argv[3], "--binary-stdio") == 0);
    int named_pipe_mode = (argc > 3 && strcmp(argv[3], "--named-pipe") == 0);
    const char *named_pipe_path = (named_pipe_mode && argc > 4) ? argv[4] : "";

    char *js = NULL;
    long jslen = 0;
    if (read_file(path, &js, &jslen) != 0) return 1;

    char version[32] = "1";
    char detector[64] = "v1";
    char ip[64] = "127.0.0.1";
    char python_module[128] = "embedded_detector";
    char python_function[64] = "detect";
    char python_path[256] = "";
    char capture_source[32] = "fake";
    int frame_timeout_ms = 1000;
    (void)json_find_string(js, (int)jslen, "version", version, sizeof(version));
    (void)json_find_string(js, (int)jslen, "detector", detector, sizeof(detector));
    (void)json_find_string(js, (int)jslen, "ip", ip, sizeof(ip));
    (void)json_find_string(js, (int)jslen, "python_embed_module", python_module, sizeof(python_module));
    (void)json_find_string(js, (int)jslen, "python_embed_function", python_function, sizeof(python_function));
    (void)json_find_string(js, (int)jslen, "python_embed_path", python_path, sizeof(python_path));
    (void)json_find_string(js, (int)jslen, "capture_source", capture_source, sizeof(capture_source));
    (void)json_find_int(js, (int)jslen, "frame_timeout_ms", &frame_timeout_ms);

    fprintf(stderr, "worker start config version=%s path=%s camera=%d mode=%s\n", version, path, camera_id,
            binary_mode ? "binary-stdio" : (named_pipe_mode ? "named-pipe" : "stdout"));
    fprintf(stderr, "python embed module=%s function=%s path=%s\n", python_module, python_function, python_path);
    fprintf(stderr, "capture source=%s frame_timeout_ms=%d\n", capture_source, frame_timeout_ms);

    free(js);

    if (binary_mode) {
        return run_binary_loop(camera_id, detector, ip, python_module, python_function, python_path,
                               capture_source, frame_timeout_ms);
    }
    if (named_pipe_mode) {
        return run_named_pipe_loop(named_pipe_path, camera_id, detector, ip, python_module, python_function, python_path,
                                   capture_source, frame_timeout_ms);
    }

    printf("Воркер: старт с конфигом версии %s (%s), камера %d\n", version, path, camera_id);
    printf("  detector=%s ip=%s\n", detector, ip);
    return 0;
}
