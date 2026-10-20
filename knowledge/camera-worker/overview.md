# camera-worker (`camera-worker/`)

Back to [global_architecture.md](../global_architecture.md) · [API](./api_contracts.md) · [Why](./architecture_why.md)
`W:` = `camera-worker/src/`.

## 1. Purpose
The orchestrator owns one native state per camera inside its JVM through **JNI**. The existing C code opens MVS/Aravis cameras, converts frames to **BGR8**, and writes the same eight-slot **shared-memory ring**. Capture, settings and streaming use direct JNI commands; no camera subprocess is launched. The standalone `camera_worker` executable remains a diagnostic tool. See [CAMERA-JNI](../../orchestrator-java/CAMERA-JNI.md) for build, lifecycle and tests.

Updated 2026-10-20. Historical line references in the code map below predate this extraction; locate functions by name.

## 2. Stack & build
C11, single translation unit `W:main.c` (2 901 lines) + `W:cw_config.c` (parsers for trigger mode / pixel format / big-endian helpers) + `include/cw_config.h` + vendored `deps/jsmn.h` (`JSMN_STATIC`, token pool `TOK_POOL 2048`).
CMake (`camera-worker/CMakeLists.txt`): optional **Aravis** backend (`HAVE_ARAVIS` via pkg-config `arv-0.8`), **MVS** backend (`HAVE_HIK_MVS`, found from `MVS_ROOT`, default `/opt/MVS`; Linux build links `libMvCameraControl.so`). The JNI target adds `W:camera_jni.c` and `include/cw_runtime.h`. `rebuild-and-run.sh` atomically installs `camera-worker/build/libcamera_jni.so`; Windows builds `camera_jni.dll`. JDK headers are required unless `CAMERA_BUILD_JNI=OFF`.
Tests: `camera-worker/tests/test_cw_config.c`, `test_capture_window.c` (includes `main.c` with fake `MV_CC_ClearImageBuffer/GetOneFrameTimeout`, verifies window cancellation semantics: window closed ⇒ 0 SDK calls; gate flipped to 0 during wait ⇒ stops).

## 3. Code map (`W:main.c`)
| Lines | Function / struct | Purpose |
|---|---|---|
| 47-51 | `RING_SLOTS 8`, `STREAM_FPS_{DEFAULT 20, MIN 1, MAX 30}` | constants |
| 52-131 | `worker_state_t` | everything per camera: SHM fd/base/bytes, frame counters, latency stats, trigger mode & activation, GigE tuning (`gige_*`), binning, pixel format, stream thread + lock, capture window path/token, MVS handle & raw buffer |
| 180-260 | `json_find_{string,bool,int,float}` | flat key search on the *header* JSON (jsmn) — not a general parser (first match of the key anywhere) |
| 277-360 | `load_frame_config` / `worker_camera_config_t` | reads `frame{width,height,format,binning_*}` |
| 372-467 | `load_camera_config` | finds `cameras[id==camera_id]` → `ip`, `capture_trigger_mode`, `exposure_us`, `gige_transfer_slot` |
| 469-580 | `load_runtime_wb_settings` | white-balance ratios from `config/data/camera_runtime_settings.json` next to `config.json` |
| 614-645 | `read_exact`, `write_message` (IMLB frame writer, 16‑byte prefix), `extract_op` | |
| 653-700 | `init_metrics_log`, `log_metrics` | `logs/camera-worker-metrics-cam-<id>.jsonl` every 1000 captures |
| 725-880 | `hik_apply_*` | exposure, gain, gamma, black level, balance ratio (per channel selectors) |
| 882-1085 | `format_settings_json`, `apply_settings_from_header` | `get_settings` / `set_settings` (keys below) |
| 1086-1134 | `hik_configure_trigger_mode`, `hik_flush_image_buffer`, `hik_fire_software_trigger` | trigger mode → `TriggerMode/TriggerSource/LineSelector/LineMode Input/LineInverter/TriggerActivation`; flush = `ClearImageBuffer` **only**; `TriggerSoftware` |
| 1136-1226 | `hik_copy_*`, `hik_copy_frame_to_bgr` | RGB→BGR, BGR copy, Mono8→BGR with stride handling |
| 1228-1450 | GigE shaping: `hik_effective_frame_transfer_delay_step`, `hik_frame_transfer_slot_index`, `hik_effective_inter_packet_delay`, `hik_apply_gige_frame_transfer_delay`, `hik_apply_gige_inter_packet_delay`, `hik_disable_auto_white_balance` | GevSCPD/GevSCFTD logic (§5) |
| 1452-1632 | `hik_verify_frame_size`, `hik_try_set_binning_factor`, `hik_apply_roi_center_crop`, `hik_apply_frame_geometry` | binning 2×2 (mode `Sum`), fallback center-crop ROI 1224×1024 when SDK returns 0x80000109 |
| 1633-1673 | `hik_apply_pixel_format` | `BGR8Packed` default → fallback `RGB8Packed` (then converted to BGR in `hik_copy_frame_to_bgr`); `Mono8` only if configured (on failure falls back to colour); result in `hik_pixel_format_active` |
| 1675-1827 | `init_hik_mvs` | `MV_CC_Initialize` → enum GigE+USB → open the camera whose `nCurrentIp == config.ip` (matching IP only, 4 open attempts with 250 ms pauses, `MV_ACCESS_Exclusive`) → `GevSCPSPacketSize = OptimalPacketSize` → geometry → pixel format → AWB off → exposure → WB → trigger mode → `PayloadSize` → GigE tuning → `StartGrabbing` → frame-transfer delay |
| 1845-1915 | Aravis init/shutdown | mono8 → BGR for compatibility |
| 1918-1940 | `fire_software_trigger_only` | requires `source==hik` and `TRIGGER_MODE_SOFTWARE`; flush + trigger; stores `pending_trigger_fired_ns` |
| 1942-1951 | `capture_window_open` | reads token file; `"0"`/missing ⇒ closed |
| 1953-2078 | `capture_from_source` | the actual grab (§4) |
| 2113-2146 | `capture_frame_to_shm` | slot = `(frame_id-1) % ring_slots`; metrics; `sync_diag channel=worker event=capture_ok` log |
| 2148-2170 | `format_capture_json` | capture response header |
| 2172-2268 | stream thread | `start_stream_internal` switches to `TRIGGER_MODE_CONTINUOUS`, grabs at `fps` into the same ring; `stop_stream_internal` restores trigger mode |
| 2270-2486 | `init_worker_state` | defaults (1224×1024, stride=w·3, 8 slots), `shm_open("/iml_cam_<id>_frame", O_CREAT|O_RDWR, 0666)`, `ftruncate`, `mmap` |
| 2536-2762 | `run_binary_loop_io` | IMLB command loop (`health, capture, start_stream, stop_stream, stream_poll, get_settings, set_settings, set_reference(err), inject_*, stop`) |
| 2764-2833 | `run_binary_loop` (stdout isolation: on Linux+MVS `dup(STDOUT)` for protocol and `dup2(STDERR→STDOUT)` because MVS prints diagnostics to stdout), `run_named_pipe_loop` (Linux UDS; not implemented on Windows build) |

## 4. Capture semantics (`capture_from_source`, `W:main.c:1953-2078`)
| Request | Path |
|---|---|
| `sync:true` (or trigger mode `software`) and not wait | flush buffer → `TriggerSoftware` → `GetOneFrameTimeout(frame_timeout_ms)` |
| `wait_frame:true` (hardware Line0) | if a capture window is configured and **closed** ⇒ error `capture_window_closed` (0 SDK calls); else `MV_CC_ClearImageBuffer` (drop frames produced outside the DI2 window); then loop `GetOneFrameTimeout(20 ms)` until a frame or `frame_timeout_ms` deadline, re-checking the window before/after every poll (cancels when DI2 falls) |
| `trigger_only:true` | `fire_software_trigger_only` → response `{"status":"triggered","trigger_fired_ns"}` (frame retrieved later with `wait_frame`) |
Validation of each frame: dimensions must equal configured; `nLostPacket > 0` ⇒ error `hik frame incomplete: lost_packets=N`; `nFrameLen` must cover W·H·3 (W·H for Mono8); unsupported pixel type ⇒ error. A failed grab increments `capture_dropped` and the frame id is **not** consumed.
Error texts used upstream for diagnostics: `hik timeout/error: 0x80000007` (timeout — typical when Line0 wiring is missing), `capture_window_closed`, `capture_failed`.

## 5. GigE traffic shaping (config keys in `config.json`)
Topology: 5 switches × 2 cameras; ids paired by `gige_transfer_slot` (0/1 per camera in `10-cameras.yaml`). Goal: exposure is simultaneous (hardware trigger) but **transfer** is staggered so two cameras don't overflow the 448 KB egress buffer of their switch.
- `GevSCPD` (inter-packet delay): `gige_inter_packet_delay 10000` (≈8 ns ticks); forced ≥120000/80000 if `gige_switch_buffer_kb ≤ 96`; warns if <4000 with a larger buffer.
- `GevSCFTD` (frame transfer delay): step = max(configured, `payload·5/4` ticks, ≥1.5 M; ≥12 M for small buffers); `slot index = gige_transfer_slot` (falls back to `camera_id % cameras_per_link`); small-buffer multiplier 2 for pairs. Result: slot‑0 cameras (0,2,4,6,8 style) transfer first, slot‑1 cameras after one frame time.
- `GevSCPSPacketSize` = `MV_CC_GetOptimalPacketSize` (jumbo frames when the NIC allows).

## 6. Runtime settings keys (`get_settings` / `set_settings`)
`exposure_us, gain_db, gamma, black_level, balance_ratio_red, balance_ratio_blue, frame_timeout_ms, capture_trigger_mode (rejected during stream: capture_trigger_mode_locked_during_stream)`; validation: exposure>0 etc. (`W:main.c:943-1085`). `get_settings` reads live values from the camera when MVS is available.

## 7. Shared memory layout
`/dev/shm/iml_cam_<id>_frame` size = `ring_slots(8) × frame_bytes`, `frame_bytes = stride·height = 1224·3·1024 = 3 760 128` → 30 MB per camera. Slot `k` at byte offset `k·frame_bytes`. A reader must copy/pin quickly: the 9th capture overwrites slot 0. Windows: `%LOCALAPPDATA%\iml_shm\iml_cam_<id>_frame` with `FlushViewOfFile`.
