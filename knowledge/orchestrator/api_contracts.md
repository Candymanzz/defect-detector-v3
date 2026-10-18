# Orchestrator — API contracts

Back to [overview](./overview.md) · [global architecture](../global_architecture.md) · Shorthand: `O:` = `orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/`.

Endpoints: HTTP `127.0.0.1:8099` (`ui_http`), WS `ws://127.0.0.1:8765/` (`client_ws`, **one UI session**; `replace_existing_session:true`; RFC6455 ping 20 s, idle timeout 90 s).
Routing table of HTTP: `O:http/HttpFrontController.java:buildRouter` (lines ~56-140); CORS preflight for `/api/*` allows `GET, POST, PUT, PATCH, DELETE, OPTIONS`.

## 1. HTTP — complete route table

| Method | Path | Handler | Notes |
|---|---|---|---|
| GET | `/health` | `HealthHttpController` | plain text; used by `StackSupervisorMain` and UI (`useBackendStatus`, 3 s timeout) |
| POST | `/diagnostics/frontend` | `FrontendDiagnostics` | ≤20 records/request, no bodies of images |
| GET | `/api/cameras` | `CameraPreviewHttpController.listCameras` | configured ids + analysis profile + paths |
| GET | `/api/camera/{id}/latest.json` · `/current.jpg` · `/heatmap.u8` | `…handleCameraPath` | last inspection snapshot; heatmap = raw gray‑u8 (width/height in latest.json) |
| GET | `/api/camera/{id}/stream.mjpeg` | `CameraMjpegHttpController` | `multipart/x-mixed-replace`; only if stream service enabled |
| GET/PUT/PATCH | `/api/camera/{id}/settings` | `CameraSettingsHttpController` → `CameraSettingsService` | runtime camera params (exposure, gain, gamma, black level, WB red/blue, trigger mode…) → worker `set_settings`; persisted |
| GET | `/api/heatmap-artifact/{artifactId}` · `/api/inspection-artifacts/{bundleId}/…` | `CameraPreviewHttpController` | immutable per-inspection artifacts (JPEG current, card preview, heat-map u8) |
| GET | `/api/geometry/cameras` · `/api/geometry/camera/{id}/latest.json` | `GeometryHttpController` | last geometry response per camera (`GeometrySnapshotCache`) |
| GET/PUT/DELETE | `/api/client/frame-archive` | `FrameArchiveHttpController` | `max_frames_per_camera` (default 20, cap 100) |
| GET | `/api/frame-archive/cameras/{id}/history` · `/frames/{frameId}` · artifacts | same | archived frames (JPEG + heat-map + result.json) |
| DELETE | `/api/frame-archive[/cameras/{id}/frames/{frameId}]` | same | |
| GET/PATCH/PUT/DELETE | `/api/client/geometry-runtime[?camera_id=]` | `ClientApiHttpController.handleGeometryRuntime` (`ClientApiHttpController.java:~345`) | body `{runtimeOverrides…}`; GET returns `runtimeOverrides` + `effectiveForNextGeometryInspect`; stored per `analysis_profile` in `config/data/geometry_runtime_settings.json` |
| GET/PUT | `/api/client/line-direction` | `handleLineDirection` | `{direction: forward\|reverse}` — filters DI3 capture against DI2 |
| GET | `/api/client/inspection/status` | | per-camera enabled/in-flight/state |
| POST | `/api/client/inspection/start` · `/stop` | | body `{camera_id}`; soft start/stop (`PerCameraInspectionGate`, resume via `InspectionCycleResumeService`) |
| POST | `/api/client/inspection/start-all` · `/stop-all` | | |
| POST | `/api/client/inspection/clear-reference` | | clears pipeline references (+python context) → `NO_REFERENCE` → `vision_ready=0` |
| POST | `/api/client/inspection/test-pin` ; GET `/test-pin/{id}/frame.jpg` | `TestFramePinStore` | durable JPEG pin of an archive frame for TEST |
| POST | `/api/client/inspection/test-analyze` | `UiTestAnalyzeService` | geometry+python on pinned/archive frame with ephemeral knobs; result delivered over WS with `test_analyze:true`; no PLC |
| GET | `/api/client/mode` · POST `/api/client/mode/test` | `handleProgramMode` | `{session_state, test_mode}`; test mode suspends production triggers |
| GET/POST | `/api/client/plc/status` · `/plc/signals` | | list/write bits (locked while reference set or inspection in flight → 409) |
| GET/PUT | `/api/client/plc/timeouts` | | DM D4400‑D4405, 0‥9999 units × 100 ms (BCD) |
| `*` | `/api/client/learning/**` (+`accept-all-as-normal`) | `handleLearning` | proxy to analisSurface `/learning/**` |
| `*` | `/api/client/<other>` | `ClientApiMount` generic proxy | `/api/client/fp-zones` → `:8000/fp-zones` (`kopcheni_base_url`) |
| `*` | `/api/orchestrator/fp-zones[/…]` · `/learning/…` · `/analysis-settings[/…]` | `Orchestrator*HttpController` | proxy; `/analysis-settings/camera/{id}…` is rewritten to `/analysis-settings/<analysis_profile>…` (`resolveCameraScopedPath`) |
| GET/POST/PUT | `/api/orchestrator/light/brightness` · `/mode` · `/bank` · `/devices` · `/com/devices` · `/network` · `/com` | `LightHttpController` | `/mode` constant\|interval; `/bank {state:on\|off}` |
| `*` | `/api/camera-flash/{bank,pair,single,routes}` | `LightHttpController.handleCameraFlash` | legacy-compatible with LightServer.v3 |
| GET | `/openapi.json`, `/swagger` | `integration/openapi` | |

Status mapping used by `ClientApiHttpController`: 400 invalid body, 409 `IllegalStateException` (state conflict), 502 PLC IO failure, 503 service missing (PLC/ws not ready).

## 2. WebSocket protocol (`O:clientws/**`)

Envelope (both directions): `{ "type": "client.* | server.*", "protocol_version": 1, "message_id": "<id>", "payload": {…} }`.
`WsFrontController` → `WsRouter` → `WsMessageHandler`s in `clientws/handler`. A parse error returns `server.error{code:"parse_error"}`.

### Client → server
| type | payload | handler / effect |
|---|---|---|
| `client.reference_bundle` | `{product_type, joint_view_index, heatmap_width, heatmap_height, views[5], fp_zones[]}`; view = `{frame: ShmFrameRef, interest_roi, interest_polygon_norm[], joint_roi?, joint_roi_polygon_norm?, perspective_line_norm? [near,far]}` | `ReferenceBundleWsHandler` → `ReferenceBundleParser.parseBundleOrThrow` (`:41-58`) validates protocol_version==configured, non-empty views, `joint_view_index` in range, positive heat-map size, unique `camera_id` in views, joint ROI only on the joint view; → `ReferenceBundleLifecycleService.acceptBundle` (`:19-35`): broadcast to all python servers (`AnalisSurfaceClientWsSync.syncClientReferenceBundle`) → `referenceContext.applyBundle` → `PipelineReferenceRegistry.applyClientBundle` → `READY` then `OPERATIONAL` (stays `TEST` if in TEST) ; heat-map space normalised when `inspect_scale≠1` |
| `client.set_active_reference_view` | `{view_index}` | choose which view's reference is shown/active → `server.active_reference_view_ack` |
| `client.fp_zones_update` | `{heatmap_width, heatmap_height, fp_zones[]}` | `FpZonesUpdateWsHandler`: per-camera scoped replace in python (`replace_fp_zones`) |
| `client.light_brightness` | `brightness_percent` / `default_brightness_percent` / `endpoints{id:%}` | `LightBrightnessWsHandler` → `LightBrightnessStore` + hardware (deferred while a DI2 pulse is active: `captureLightingActive`) |
| `client.stream_start` `{camera_id,max_fps}` / `client.stream_stop` `{camera_id}` | | `CameraStreamService` (blocks inspection of that camera) |
| `client.preview_pause` / `preview_resume` / `preview_images_disable` / `preview_images_enable` | `{}` | `LivePreviewGate` and heavy-image toggle |

### Server → client
`server.hello`/`server.state` `{session_state: NO_REFERENCE|READY|OPERATIONAL|TEST, server_ts_ms}` · `server.reference_bundle_ack{ok}` · `server.fp_zones_ack` · `server.active_reference_view_ack{view_index}` ·
`server.light_brightness_ack{brightness_percent, endpoints}` · `server.stream_started{camera_id,max_fps,mjpeg_path}` / `stream_stopped` ·
`server.preview_frame` / `server.preview_batch{line_seq, frames[]}` ·
**`server.inspect_result`** `{camera_id, frame_id, inspection_id, learned_review_id?, test_analyze?, current: ShmFrameRef, http_path, artifact_bundle_id, heatmap{width,height,pixel_format:"gray_u8",artifact_id,http_path}, active_reference_view_index, detector{detector_id,product_type}, overall_pass, action ACCEPT|REJECT|CAPTURE, anomaly_score, python_status, geometry_status, geometry{…compact}, fp_zones[], excluded_normal_zones[], fp_coordinate_space{heatmap_width,heatmap_height}, server_ts_ms}` ·
**`server.inspect_bucket_result`** `{group_id, trigger_sequence, overall_pass, bucket_camera_ids[], frames[{camera_id,frame_id,overall_pass,action,anomaly_score,python_status,geometry_status}], reject_camera_ids[], session_state}` ·
`server.plc_fins_traffic{direction,operation,signal,area,address,value,hex_frame,sid,end_code,ok}` · `server.error{code,message}`.
Error codes (front-end `ServerErrorCode`): `parse_error, no_reference, invalid_payload, invalid_protocol_version, invalid_heatmap_size, invalid_view_index, invalid_product_type, invalid_joint_view_index, invalid_views, invalid_view, invalid_frame, invalid_camera_id, invalid_frame_id, invalid_shm_name, invalid_frame_size, invalid_shm_offset, invalid_stride, invalid_interest_roi, invalid_joint_roi, missing_joint_roi, invalid_fp_*, fp_point_out_of_range, kopcheni_sync_failed, light_disabled, stream_disabled, stream_already_active, stream_start_failed, handler_error, unknown_type`.

Ordering guarantee: **PLC first, UI later** — bucket UI callbacks (`pendingUi`) run only after `fanOut.publishBuckets` (`BucketInspectionAggregator.publishSyncedResults:516-535`).

## 3. Internal contract: `PipelineState` and message headers
`PipelineState(capture, py, geom, captureMs, pythonMs, geometryMs)` (`O:pipeline/PipelineState`). Each field is an IMLB-style `BinaryProtocol.Message(type, Map header, byte[] payload)`.

**Capture header** (from worker + orchestrator additions): `camera_id, frame_id, slot_index, width, height, stride, format:"BGR8", capture_pixel_format, timestamp_ns, shm_name, shm_offset, frame_bytes, ring_slots, streaming, capture_started_ns, capture_latency_ns` + optional `positioning_*`, `pose_aligned`/`HEADER_ALIGNED`, `test_analyze`, `test_frame_file_path`, `analysis_test_settings`, `test_geometry_overrides`, `pin_jpeg_sha256`.

**Geometry request header** (`BinaryInspectHeaders.geometryInspectHeader:23-110`): `op:"inspect_shm"` + capture SHM + reference SHM (`reference_*`) + `mainRoi{x,y,width,height}` (bbox of the polygon if present), `mainRoiPolygonNorm`, `jointRoi`, `jointRoiPolygonNorm`, `jointMode`, `pixelsToMm`, `maxShiftMm`, `maxRotationDeg`, `maxJointDefectMm`, `jointMinWidthMm`, `jointMaxWidthMm`, `maxJointParallelismDeg`, `maxJointTaperMm`, `maxJointRimSkewDeg`, `maxJointGapAsymmetryMm`, `jointSeamSegmentationEnabled` (= has joint ROI), `jointSeamSegmentationSensitivity` (clamped 0..1), `threshold` (= `python_detector.fallback_threshold`), `maxWrinklesScore`, `pose_locked` (when positioning aligned), `client_reference_bundle`. Overrides precedence: YAML `java_geometry` < `profiles.<analysis_profile>` (`applyGeometryProfileOverrides`) < runtime store (`GeometryRuntimeConfig.applyToGeometryHeader`) < `test_geometry_overrides`.

**Python request header** (`pythonInspectHeader:393-428` + `attachReferenceAndRoi:503-521`): `op:"inspect_shm", camera_id, frame_id, product_type, detector_id, include_visuals:false, defer_learning_review (default true), rois?, shm_name/offset/width/height/stride, reference_shm_name/offset/width/height/stride, roi_polygon_norm, perspective_line_norm` + from the executor: `defer_heatmap` or `heatmap_u8_output_path`+`heatmap_max_width`, `analysis_profile`, `analysis_test_settings`, `inspect_scale (<0.999)`, runtime knobs (`GeometryRuntimeConfig.applyToPythonHeader`). The `threshold` is *intentionally absent* so Python uses the profile's `default_threshold` (comment at `BinaryInspectHeaders.java:408-411`).
Test-frame variant (`op:"inspect_test_frame"`): `file_path`, `cache_key`, `image_url?`, `simple`/`detailed`/`pro` knobs, `skip_learning_review:true`.

**Python response header** after `inspectJsonToStdioHeader`: `status` (`ГОДЕН|БРАК`), `ok`, `anomaly_score`, `threshold`, `product_type`, `detector_id`, `raw_anomaly_score`, `rechecked_zones_count`, `recheck_adjustment`, `rechecked_zone_ids`, `learned_review_id`, `learned_normal_matches_count`, `learned_normal_adjustment`, `excluded_normal_zones`, `py_*_ms`, optional `deferred_heatmap_token` + `deferred_heatmap_base_url`, heat-map SHM descriptor; orchestrator adds `python_queue_ms`, `python_rpc_ms`, `python_server`.

## 4. Orchestrator ⇄ analisSurface op mapping
`AnalisSurfaceHttpBinaryRpcSupervisor.commandNoRetry:203-221`:
| IMLB op | HTTP |
|---|---|
| `health` | `GET /detector/health` then `/health` (:166) |
| `set_reference_shm` | `POST /upload-ref-shm` (+ ROI `POST /roi-polygon`, FP `POST /fp-zones` when present) |
| `inspect_shm` | (1) `uploadInspectionReference` if reference signature changed, (2) `ensureRoiPolygon` if ROI changed, (3) `POST /inspect-shm` |
| `render_heatmap` | `POST /render-heatmap` (token) |
| `inspect_test_frame` | `POST /inspect-test-frame` (requires `cache_key`, `file_path`, exactly one of simple/pro) |
| `replace_fp_zones` | `DELETE /fp-zones` (per scoped product) + `POST /fp-zones` ×N |
| `sync_client_reference_bundle` / `set_active_reference_view` | composite: upload ref + ROI + FP per camera |
| `clear_inspection_context` | `POST /clear-inspection-context` |
| `stop` | no-op OK |
HTTP: JDK `HttpClient`, timeout `service_command_timeout_ms`; one retry (`command` :185-193). SHM names are converted to logical names (`logicalShmNameForHttp:966-987`) because Windows paths broke uvicorn requests.

## 5. PLC (FINS / DO)
<a id="plc-fins"></a>
**FINS/UDP** (`O:plc/fins/*`): header 10 bytes (`writeHeader`), SID, commands `0101` memory area read (words), `0102` write (bit or words); area codes from `PlcMemoryArea` (`CIO, W, H, DM` with separate bit/word codes). Bit write frame is 19 bytes (`FinsFrameBuilder.buildWriteBit`), word write `18 + 2n`.
`plc_fins`: host `169.254.220.232:9600`, `dest_node 231`, `src_node 254`, `response_timeout_ms 1000`, `queue_size 64`, `pulse_ms 80`, `register_map_path plk/register-map.yaml`.
Register map (see `plk/register-map.yaml`, `plk/README.md`):

| Signal | Area/addr | Dir | Meaning / who writes |
|---|---|---|---|
| `vision_ready` | W0.04 | PC→PLC | sticky; `FanOutCoordinator.refreshPlcLevels` |
| `vision_fault` | W0.05 | PC→PLC | **replaced by DO2 timer** when `io_fault.enabled` (FINS def removed from the active map) |
| `reject_line_1/2` | W0.06 / W0.07 (`bucket_group_id` 0/1) | PC→PLC | **removed from FINS map when `io_reject.enabled`**; kept for FINS-mode fallback |
| status bits | CIO 140… | PLC→PC | read-only, UI lists |
| fault bits | CIO 191… | PLC→PC | read-only |
| control bits | CIO 240… | PC→PLC | manual writes via `/api/client/plc/signals` (locked during inspection) |
| timeouts | DM D4400‑D4404 | words, BCD, ×100 ms | `/api/client/plc/timeouts` ; `PlcBcd.toBcdWord/fromBcdWord` (0‥9999 units) |
| `handle_material_mode` | DM D4405 | word 0/1 | 0 steel, 1 plastic; cached in `FanOutCoordinator.plasticHandleMode` (read at boot `syncHandleMaterialModeFromPlc`, updated on read/write) |
Semantics of `plasticHandleMode` **only in FINS mode** (`ioReject == null`): `publishEarlyPlasticHandleReject` rejects **all lines** on the first failing camera and `PlcFinsPublisher.publishBucket(…, holdRejectUntilPass=true)` holds reject HIGH until PASS (pulse `pulse_ms` otherwise). In DO mode the flag is passed to `IoRejectPublisher.publish` but lines remain independent (“DO lines represent independent bucket verdicts; never reject a sibling line”, `FanOutCoordinator.java:216`).

**Discrete outputs** (`integration.io_reject`, `io_fault`, MV‑VC controller): `groups: {0:{port 3, Timer3, Out3, active_high:false}, 1:{port 4, Timer4, Out4, active_high:false}}`, mode `hold`: PASS ⇒ `LineSource=On` (output asserted), reject ⇒ `Off` (the `active_high:false` inversion maps pass/reject to physical level: `physical_high = (!pass) == activeHigh` → for `active_high:false` pass ⇒ HIGH). DO5 protected (camera trigger); DO2 fault timer; DO6/7 free (bench tool uses Timer6/DO6).
