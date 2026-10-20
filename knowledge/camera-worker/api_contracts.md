# camera-worker — API contracts

Back to [overview](./overview.md) · `W:` = `camera-worker/src/`

## 0. Current JNI transport (2026-10-20)

The working pipeline uses `NativeCameraClient` → `NativeCameraBindings` → `cw_camera_*`. JSON command metadata is passed directly as UTF-8 bytes; JNI returns a response-type byte followed by JSON. There are no IMLB prefixes, pipes or image payloads on this path. The operations and SHM layout below are retained. `inject_*` returns `unknown_op` in JNI; `stop` marks the client stopped and resources are released by `close`. Frame waits poll at 20 ms and can be cancelled before releasing the native state.

The IMLB framing and fault hooks below describe the retained standalone diagnostic executable and the external analysis services. Build and lifecycle: [CAMERA-JNI](../../orchestrator-java/CAMERA-JNI.md).

## 1. IMLB framing
<a id="imlb-framing"></a>
Shared by camera-worker (`W:main.c:624-641 write_message`, reader at `:2536+`), geometry, positioning (`BinaryProtocol.java` — `G:protocol/BinaryProtocol.java:25-72`) and the orchestrator (`orchestrator/protocol/BinaryProtocol`). **Big-endian.**

```
off  size  field
0    4     magic "IMLB"
4    1     version = 1
5    1     type: 1=COMMAND, 2=RESPONSE, 3=ERROR
6    2     reserved = 0
8    4     header length H (u32)
12   4     payload length P (u32)
16   H     header: UTF-8 JSON object ("op" required in commands)
16+H P     payload bytes (always 0 in current traffic; frames travel via SHM)
```
Worker-specific rules: header buffer is bounded (`header_len >= sizeof(header_buf)` ⇒ ERROR `header_too_large` after draining the payload); payload of a command is read and **discarded** (`:2570-2582`); non-COMMAND type ⇒ ERROR `unexpected_msg_type`; no `op` ⇒ `missing_op`; unknown op ⇒ `unknown_op`.
Transports: stdio of the child process (default on Linux, flag `--binary-stdio`) or Unix domain socket/pipe `--named-pipe <path>` (`worker_named_pipe_template_linux: /tmp/iml-camera-%d.pipe`). On Linux+MVS the protocol uses a **dup'ed copy of stdout** and stdout is redirected to stderr, since MVS may print to stdout (`run_binary_loop:2764-2781`).

## 2. Operations

| `op` | Request header fields | Success response | Errors |
|---|---|---|---|
| `health` | – | `{status:"ok", service:"camera-worker", camera_id, detector, ip, shm_name, ring_slots, capture_total, capture_dropped, latency_ns_min/avg/max, last_frame_id, last_slot_index, capture_source, capture_backend_ready, capture_backend_info, frame_timeout_ms, fps, detection:"analisSurface"}` | – |
| `capture` | `sync` (bool), `trigger_only` (bool), `wait_frame` (bool), `capture_window_path` (string), `capture_window_token` (string) | frame: `{camera_id, frame_id, slot_index, width, height, stride, format:"BGR8", capture_pixel_format, timestamp_ns, shm_name:"/iml_cam_<id>_frame", shm_offset, frame_bytes, ring_slots, streaming, capture_started_ns, capture_latency_ns}`; trigger-only: `{status:"triggered", camera_id, trigger_fired_ns}` | `capture_rejected{reason:streaming_active_use_sync_inspection_after_stream_stop}`, `capture_failed{reason, source}` with reasons `hik timeout/error: 0x…`, `capture_window_closed`, `hik frame incomplete: lost_packets=N`, `hik frame dims mismatch WxH`, `trigger_only requires software trigger mode`, `hik software trigger failed: 0x…` |
| `start_stream` | `fps` (1‥30, default 20) | `{status:"streaming", camera_id, fps}` | `start_stream_failed{reason: stream_already_active\|…}` |
| `stream_poll` | – | same as capture frame JSON (last streamed frame) | `stream_not_active`, `no_stream_frame_yet` |
| `stop_stream` | – | `{status:"stream_stopped", camera_id}` | – |
| `get_settings` | – | `{status:"ok", camera_id, exposure_us, gain_db, gamma, black_level, balance_ratio_red, balance_ratio_blue, capture_trigger_mode, effective_trigger_mode, pixel_format, width, height, frame_timeout_ms, streaming, mvs_available}` | – |
| `set_settings` | any of `exposure_us, gain_db, gamma, black_level, balance_ratio_red, balance_ratio_blue, frame_timeout_ms, capture_trigger_mode` | same JSON as `get_settings` | `set_settings_failed{reason}` (e.g. `exposure_us_must_be_positive`, `capture_trigger_mode_locked_during_stream`) |
| `set_reference` | – | – | always `set_reference_not_supported{hint:"use analisSurface set_reference_shm"}` |
| `stop` | – | `{status:"stopped"}` then the loop exits (metrics flushed) | – |
| `inject_exit` | – | process `_exit(42)` | test hook |
| `inject_timeout_ms` | `timeout_ms` | sleeps then `{status:"timeout_injected"}` | test hook |
| `inject_broken_response` | – | raw bytes `BROKEN_RESPONSE` (invalid frame) | test hook |

## 3. Behavioural contract the orchestrator relies on
1. **Single in-flight command** per worker (the loop is sequential; stream thread shares a lock `stream_lock_enter/leave`).
2. `frame_id` starts at 1 and increments only on success; `slot_index = (frame_id-1) % 8`; the frame stays valid only until 8 further captures — consumers must pin/copy (orchestrator: `LineFramePinService`).
3. `wait_frame` drops everything buffered before the call (`ClearImageBuffer`) and, if a window path+token is given, aborts when the token file no longer equals the token (`DirectionCaptureWindow.close()` writes `0`).
4. `timestamp_ns` is `CLOCK_REALTIME` ns at **after** copy; `capture_started_ns` at call start; `capture_latency_ns` their difference — used for `capture_frame_to_inspection_end_ms` in orchestrator logs.
5. Trigger mode switching: `capture_trigger_mode` set via `set_settings` re-runs `hik_configure_trigger_mode`; the orchestrator excludes it from persisted-settings replay when `hardware_line_trigger` is true (keeps `line0` from `config.json`).

## 4. Configuration read from `config/config.json` (not YAML)
Top: `version`, `detector`, `capture_source` (`hik|aravis|fake`), `frame_timeout_ms`, `frame{width,height,format,binning_horizontal,binning_vertical,binning_mode}`, `gige_inter_packet_delay`, `gige_frame_transfer_delay_step`, `gige_ftd_cameras_per_link`, `gige_switch_buffer_kb`, `line0_trigger_activation`, `line0_line_inverter`; `cameras[]: {id, ip, capture_trigger_mode, exposure_us, gige_transfer_slot, …}`. Template: `config.exemple/config.json`.
