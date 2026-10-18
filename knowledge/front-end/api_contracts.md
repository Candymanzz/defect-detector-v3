# front-end — API contracts (as consumer)

Back to [overview](./overview.md) · Server side: [orchestrator/api_contracts.md](../orchestrator/api_contracts.md). `F:` = `front-end/`.

The UI exposes no API. It consumes:

## 1. REST (`F:src/shared/api/orchestratorApi.ts`, `HttpClient` with timeouts; base = `appEnv.apiRequestBaseUrl`)
| Method (line) | HTTP |
|---|---|
| `health` (:52) | `GET /health` (text, 3 s) |
| `listCameras`, `getLatestSnapshot`, `currentFrameUrl`, `heatmapUrl`, `getHeatmap`, `streamMjpegUrl` | `GET /api/cameras`, `/api/camera/{id}/{latest.json,current.jpg,heatmap.u8,stream.mjpeg}` |
| `getCameraSettings`/`updateCameraSettings` | `GET/PATCH /api/camera/{id}/settings` |
| `heatmapArtifactUrl`, `getHeatmapArtifact(id, signal)` | `GET /api/heatmap-artifact/{id}` (octet-stream) |
| `listGeometryCameras`, `getGeometryLatestSnapshot` | `/api/geometry/cameras`, `/api/geometry/camera/{id}/latest.json` |
| `get/replace/patch/clearGeometryRuntime(cameraId?)` | `GET/PUT/PATCH/DELETE /api/client/geometry-runtime[?camera_id]` |
| `getInspectionStatus`, `setInspectionEnabled(cam, bool)`, `resetInspection`, `stopAll/startAllInspections` | `/api/client/inspection/{status,start,stop,clear-reference,stop-all,start-all}` |
| `getLightBrightness/setLightBrightness`, `getLightMode/setLightMode(constant)` | `/api/orchestrator/light/{brightness,mode}` |
| `getLineDirection/setLineDirection` | `/api/client/line-direction` |
| `getPlcStatus`, `getPlcSignals`, `writePlcSignal`, `getPlcTimeouts`, `putPlcTimeouts` | `/api/client/plc/{status,signals,timeouts}` |
| `getFrameArchiveSettings/setFrameArchiveMaxFrames/getFrameArchiveHistory/deleteFrameArchiveFrame/clearFrameArchive` | `/api/client/frame-archive`, `/api/frame-archive/cameras/{id}/history`, `…/frames/{frameId}`, `/api/frame-archive` |
| analysis settings (`get/setAnalysisSettings(productType)`, `…CameraAnalysisSettings(cameraId)`, simple/strength/pro variants, defaults) | `/api/orchestrator/analysis-settings[/camera/{id}][/simple|/strengths|/pro]` (proxied to python) |
| `getFpZones(productType)` | `/api/orchestrator/fp-zones/{product}` |
| `getClientMode`, `setTestMode(bool)` | `GET /api/client/mode`, `POST /api/client/mode/test` |
| `testAnalyzeArchiveFrame(cam, frameId)`, `pinTestFrame(req)` | `POST /api/client/inspection/test-analyze`, `/test-pin` |
| learning (`LearnedNormalCase` flows) | `/api/orchestrator/learning/**` |

## 2. WebSocket (`F:src/shared/ws/*`)
Singleton `orchestratorWs` (`OrchestratorWebSocketClient(appEnv.wsUrl)`), protocol_version 1, `message_id` = generated per send (used to correlate `server.reference_bundle_ack`).
Sends: `client.reference_bundle` (payload `ClientReferenceBundlePayload`), `client.set_active_reference_view`, `client.fp_zones_update`, `client.light_brightness`, `client.stream_start/stop`, `client.preview_pause/resume`, `client.preview_images_enable/disable`.
Receives: `server.hello/state/inspect_result/inspect_bucket_result/preview_frame/preview_batch/stream_*/reference_bundle_ack/fp_zones_ack/active_reference_view_ack/light_brightness_ack/plc_fins_traffic/error` (typed in `types.ts`; `server.unknown` for forward-compat). `WsSessionState` in the UI type lacks `TEST` (server may send it — `TEST` appears as `string` fallthrough; ⚠️ type drift: `WsSessionState = "NO_REFERENCE"|"READY"|"OPERATIONAL"` vs server enum with `TEST`).

## 3. Images
`/api/inspection-artifacts/{bundleId}/…` (card JPEG 384 px q30, modal JPEG 960 px q45), `/api/heatmap-artifact/{id}` (gray‑u8 ≤384 px width), `/api/camera/{id}/stream.mjpeg` (live), frame-archive artifacts.

## 4. Diagnostics & host integration
Electron IPC `app:diagnostic` → `session-logger.cjs` → `IML_LOG_SESSION_DIR/frontend-*.log`; in browser mode `POST /diagnostics/frontend` (≤20 records). `app:getEnvironment` returns mode/platform/versions. Browser storage: **IndexedDB** `defect-detector-reference-library` (reference library only).

## 5. PLC handle-material switch (D4405)
`PlcTimeoutState.name == "handle_material_mode"` or `address == "D4405"`; read `valueUnits ?? value_units ?? rawWord`; non-zero ⇒ plastic. Written with both keys (`handle_material_mode` and `D4405`) in one `PUT /api/client/plc/timeouts`.
