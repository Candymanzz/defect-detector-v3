# Global Architecture — Defect Detector v3

> **Entry point of the knowledge base.** Always read this file first, then follow the links to the service you need.
> Never scan the whole repository: use the **Code map** tables (file + line ranges) in each service's `overview.md`.
> Language: English (sources are Russian). Maintenance rules: see [§12](#12-maintenance-protocol-for-future-changes).
> Initialised by reverse engineering on 2026‑10‑18 from branch `testlinux` (HEAD `3fcb91c3`). Tags:
> **[doc]** = stated in repo docs/comments/commits, **[code]** = verified in code at the cited place,
> `⚠️ [GUESS/CONJECTURE]` = inferred reasoning without historical evidence.
> Path shorthand used across the KB:
> `O:` = `orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/` ·
> `G:` = `java-geometry-service/src/main/java/com/example/iml/geometry/` ·
> `P:` = `java-positioning-service/src/main/java/com/example/iml/positioning/` ·
> `A:` = `analisSurface/backend/app/` · `W:` = `camera-worker/src/` · `F:` = `front-end/`.

Latest documentation review: **2026-10-20**, source HEAD `c9944af8`. Start with [recent decisions and operating context](./recent_context.md) for implemented ROI/positioning/heatmap behavior, [machine measurements](./performance/2026-10-16-log-review.md) for the dated evidence, and the [optimisation plan](./analis-surface/optimization_plan.md) for work that is **not implemented**. No application code was changed by this review.

## 1. What the system is

An industrial visual-inspection line for a **plastic/steel bucket with handle and glued label**. Ten GigE cameras photograph each product when a
hardware trigger arrives from the line (PLC/photo-eye). Each frame is:
1. positioned against a golden reference (translation by phase correlation, in Python),
2. checked for **geometry** (label seam/joint width & parallelism, rim skew vs. yellow rim, concentricity, wrinkles, shift/rotation) in Java/OpenCV,
3. checked for **surface defects** by a classical difference-map pipeline in Python (no ML since `db7e2961`).

Per-camera verdicts are aggregated per **line/bucket group** (cams 0–4 → line 1, cams 5–9 → line 2) and the reject is driven to the PLC through
IO-controller digital outputs (DO3/DO4, “hold” levels), with Omron FINS used for ready/timeouts/telemetry. An Electron kiosk UI (React) shows results,
sets references/ROI/FP-zones/analysis settings, and manages PLC parameters.

Runtime today: **Ubuntu 24.04, PREEMPT_RT kernel 6.8, CPU isolation 2‑5,8‑11, systemd + Electron kiosk** ([deploy-ops](./deploy-ops/overview.md)).
Windows code paths still exist (`*_windows`/`*_linux` config keys, `StackSupervisorMain` reboot escalation).

## 2. Service map

| Service | Dir | Stack | Role | KB |
|---|---|---|---|---|
| **orchestrator** | `orchestrator-java/` | Java 17, Maven, JNA, Log4j2, java-websocket, JDK HttpServer | Brain: boot/supervise children, DI triggers → capture → per-camera pipeline → bucket verdict → DO/FINS/WS; also lighting & IO controller in-process | [overview](./orchestrator/overview.md) · [api](./orchestrator/api_contracts.md) · [why](./orchestrator/architecture_why.md) |
| **camera-worker** | `camera-worker/` | C11, CMake, MVS SDK | 1 process/camera: grab → BGR8 → SHM ring; IMLB command server | [overview](./camera-worker/overview.md) · [api](./camera-worker/api_contracts.md) · [why](./camera-worker/architecture_why.md) |
| **geometry** | `java-geometry-service/` | Java 17 + OpenCV | Seam/rim-skew/concentricity/wrinkle/alignment checks (IMLB over stdio) | [overview](./geometry/overview.md) · [api](./geometry/api_contracts.md) · [why](./geometry/architecture_why.md) |
| **positioning** (off) | `java-positioning-service/` | Java 17 + OpenCV | ORB+RANSAC pose normalisation; **disabled**, replaced by Python phase correlation | [overview](./positioning/overview.md) · [api](./positioning/api_contracts.md) · [why](./positioning/architecture_why.md) |
| **analis-surface** | `analisSurface/backend/` | Python, FastAPI/uvicorn ×6, OpenCV, NumPy | Surface defects, references/ROI/FP/learned-normals store, analysis settings, heat-maps | [overview](./analis-surface/overview.md) · [api](./analis-surface/api_contracts.md) · [why](./analis-surface/architecture_why.md) |
| **front-end** | `front-end/` | React 19, TS, Vite, Electron | Operator/engineer kiosk UI | [overview](./front-end/overview.md) · [api](./front-end/api_contracts.md) · [why](./front-end/architecture_why.md) |
| **legacy hardware** | `LightServer.v3/`, `IoInputMonitor/`, `mvs-io-service/` | C# .NET, Python | Pre‑merge light/DI services & Linux DO6 bench tool; not in production path | [overview](./legacy-hardware/overview.md) · [why](./legacy-hardware/architecture_why.md) |
| **deploy/ops** | `deploy/`, root `*.sh`, `plk/`, `config.exemple/` | bash, Python | Build, supervisor, RT tuning, kiosk, PLC map, config templates | [overview](./deploy-ops/overview.md) · [why](./deploy-ops/architecture_why.md) |

## 3. Process topology

```
 line sensors ──► MV-VC IO controller (serial COM_Port#ttyS4, MVS SDK; ONE handle owned by the orchestrator)
   DI1 work · DI2 direction/“flash window” · DI3 trigger · DI4 shutdown
   hardware: In3 ► Timer5 ► DO5 ► Line0 of all 10 cameras   (configured in MVS, never touched by Java)
   DO2 ◄ fault timer     DO3/DO4 ◄ reject hold levels (line 1 / line 2)          Omron PLC ◄ FINS/UDP :9600
                                  │                                                        ▲
 ┌────────────────────────────────▼───────────────────────────────────────────────────────┴──────┐
 │ ORCHESTRATOR (1 JVM, -Xmx1024m)                                                               │
 │  mvs-io-acquisition thread ─► mvs-io-events thread ─► IoInputTriggerTransport ─► TriggerBus   │
 │  IntervalFlashController (DI2 window)   LightTriggerClient/NativeLightBank (MV-LE ×5, JNA)    │
 │  per-camera “camera-flow” thread ─► AsyncInspectionCycleRunner (capture→geometry→python→decide)│
 │  BucketInspectionAggregator ─► FanOutCoordinator ─► IoRejectPublisher / PlcFinsPublisher / WS │
 │  HTTP :8099 (UiHttpServer)   WS :8765 (ClientWebSocketServer)   CriticalServiceWatchdog       │
 └──┬───────────────┬───────────────────────┬─────────────────────────────┬─────────────────────┘
    │IMLB stdio     │IMLB stdio             │IMLB stdio (disabled)        │HTTP/JSON :8000‥ (6 uvicorn)
    ▼               ▼                       ▼                             ▼
 camera-worker×10  geometry×10 JVM         positioning×10 JVM        analisSurface ×6 procs (configurable threads)
    │ writes                ▲ reads SHM            ▲ reads SHM               ▲ reads SHM (mmap)
    └──► /dev/shm/iml_cam_<id>_frame (8-slot BGR8 ring) ──► pinned copies iml_line_pin_cam<id>_f<seq> ──┘
 Supervisor above all: StackSupervisorMain (health :8099/health + :8000/detector/health) ◄ run-supervised.sh / systemd
 UI: Electron (kiosk) ──REST/MJPEG/WS──► orchestrator
```

The checked-in launcher template sets `analis_surface.inspect_workers: 3` per Python process; `AnalisSurfaceLauncher` passes `ANALIS_INSPECT_WORKERS`. Without that environment variable Python defaults to **10** (clamped 1–32). The six-process topology is the template/reviewed deployment, not a hard-coded universal count. See [configuration distinctions](./recent_context.md#5-configuration-versus-observed-deployment).

## 4. Data stores & shared memory (who owns what)

| Name / path | Owner (writer) | Readers | Lifetime | Notes |
|---|---|---|---|---|
| `/dev/shm/iml_cam_<id>_frame` | camera-worker `W:main.c:2336` | orchestrator, geometry, python | process | 8 slots (`RING_SLOTS`, `W:main.c:47`) × `stride*height`; slot = `(frame_id-1)%8` |
| `iml_line_pin_cam<id>_f<seq>` | orchestrator `O:capture/LineFramePinService` | pipeline, UI sidecar | one cycle (TTL 45 s) | copy of ring slot so the next trigger can overwrite the ring |
| `iml_ref_cam<id>` | orchestrator `O:pipeline/reference/PipelineReferenceRegistry.java:114` | geometry, python | until next reference bundle | durable copy of the reference frame (not a ring slot) |
| `iml_py_ds_cur/ref_cam<id>` | orchestrator `PythonInspectDownscaleSupport` | python | cached | legacy pre-downscale path; replaced by `inspect_scale` inside Python |
| `iml_ui_heatmap_cam_<id>_frame_<fid>` | python (`heatmap_u8_output_path`) | orchestrator UI sidecar | short | gray‑u8 heat-map |
| `iml_pos_cam_<id>` | positioning (disabled) | geometry/python | – | |
| capture-window token file `iml-capture-window-*.token` | orchestrator `O:capture/DirectionCaptureWindow` | camera-worker | process | cancels `wait_frame` outside the DI2 window |
| Python RAM: references, ROI, perspective lines, per-camera `product#cam=N` | analisSurface | – | **lost on restart** | replayed by orchestrator (see §6) |
| `analisSurface/backend/app/data/{analysis_settings.json,fp_zones.json,roi_sub_zones.json,fp_zone_crops/,accepted_normals/,learning_reviews/}` | analisSurface | – | disk | gitignored except `roi_sub_zones.json`, `fp_zones.generation` |
| `config/data/{camera_runtime_settings,geometry_runtime_settings,light_brightness_settings}.json` | orchestrator | orchestrator | disk | runtime-mutable settings |
| frame archive `/tmp/iml-frame-archive` (Linux) | orchestrator `FrameArchiveService` | UI | last N per camera | N default 20, cap 100 |
| `logs/<ts>-<pid>/…` | all | humans | per launch | [LOGGING.md](../LOGGING.md); `logs/pipeline-stages.jsonl` timing |

## 5. Inter-service contracts (details in each `api_contracts.md`)

| Link | Transport | Message |
|---|---|---|
| orch ⇄ camera-worker | IMLB over child **stdio** (`--binary-stdio`) or named pipe | ops `health, capture{wait_frame\|trigger_only\|sync}, start/stop_stream, stream_poll, get/set_settings, stop` → [camera-worker/api_contracts.md](./camera-worker/api_contracts.md) |
| orch ⇄ geometry | IMLB stdio | `health, inspect_shm` (frame by SHM name) → [geometry/api_contracts.md](./geometry/api_contracts.md) |
| orch ⇄ positioning | IMLB stdio | `health, position_shm` → [positioning/api_contracts.md](./positioning/api_contracts.md) |
| orch ⇄ analisSurface | HTTP/JSON; `AnalisSurfaceHttpBinaryRpcSupervisor` **emulates IMLB ops over HTTP** | `inspect_shm→POST /inspect-shm`, `set_reference_shm→/upload-ref-shm`, … → [analis-surface/api_contracts.md](./analis-surface/api_contracts.md) |
| orch ⇄ UI | REST + MJPEG on :8099, WS on :8765 (single client) | → [orchestrator/api_contracts.md](./orchestrator/api_contracts.md) |
| orch ⇄ PLC | FINS/UDP + IO-controller DO | → [orchestrator/api_contracts.md#plc](./orchestrator/api_contracts.md#plc-fins) |
| UI → analisSurface | orchestrator generic proxy `/api/client/<path>` → `:8000/<path>` | FP zones, learning, settings |

**Naming rule that spans services:** Python keys everything by `scopedProductType = "<product_type>#cam=<cameraId>"`
(`O:clientapi/AnalisSurfaceHttpBinaryRpcSupervisor.java:1216-1235`), while *analysis settings* are keyed by `analysis_profile` (`bench-lan<id+1>`, `10-cameras.yaml`). Do not mix the two.

## 6. Lifecycle: boot → operational

Entry: `OrchestratorMain.main` (`O:../OrchestratorMain.java`) → `OrchestratorApplication.run` (lines 31-50: load YAML, report, `projectRoot = config/..`) →
`IntegrationBootstrap.start` (`O:bootstrap/IntegrationBootstrap.java:32-65`):

| # | Step | Class (line) | What happens |
|---|---|---|---|
| 1 | preflight | `IntegrationPreflightService.run` (:32-69) | enabled cameras; `ImlShmJanitor.purgeStaleFiles`; locate `camera_worker` binary & `config/config.json`; `IntegrationBootConfig.load` |
| 2 | child services (parallel, 6 threads `svc-boot`) | `ChildProcessStartupService.start` (:57-127) | analisSurface uvicorn pool, geometry pool, positioning pool, frontend (`npm run dev`, skipped if `IML_FRONTEND_AUTOSTART=false` or kiosk owns UI); builds `pythonPool` of HTTP clients; empty pool ⇒ abort |
| 3 | `assembleCoreCollaborators` | same class (:129-175) | UI sidecar, geometry snapshot cache, `GeometryRuntimeStore` (`config/data/geometry_runtime_settings.json`), `PerCameraInspectionGate`, `ManualLineDirectionService`, `ClientApiMount`, `WorkerCaptureCoordinator` |
| 4 | pipeline + lights | `LightingBootstrapService.assemblePipelineAndEngageLights` (:42-118) | positioning/geometry/python executors, `DefaultInspectionDecisionAggregator`; `LightTriggerClient` + `awaitEndpointsReady` + `startupEngage` |
| 5 | UI runtime | `UiRuntimeBootstrapService.bootstrap` (:26-86) | camera settings store, `FrameArchiveService`, `UiHttpServer` :8099, `ClientWebSocketServer` :8765, test-analyze service |
| 6 | runtime & block | `PipelineCameraRuntimeService.runBlocking` (:54-147) | SHM janitor (15 s), timing log, `FanOutCoordinator.fromConfig`, `ServiceHealthGate`, stop-signal, **camera workers** (10, 20 ms stagger, health each), `CriticalServiceWatchdog`, `LivePreviewPublisher`, stage executors, `TriggerRuntimeBootstrapService.wire`, `CriticalServiceInspectionPause`, DI4 shutdown, bind IO reject outputs, **`activateNativeIo()`**, then one `processCamera` task per camera blocks forever (`awaitCameraTasksOrStop`) |

Reference replay: python RAM is empty after (re)start → orchestrator sends `set_reference_shm` itself the first time (`uploadInspectionReference`, signature cache `SHARED_REFERENCE_SIGNATURES`)
and re-sends ROI per request (`ensureRoiPolygon`, `roi_polygon_norm` in `ShmFrameRequest`).

## 7. One inspection cycle — exact path

1. **Edge**: `MvsIoTransport.acquire` thread polls `readInputs()` (≈41 ms/scan) → enqueue `IoInputDiChange` → `mvs-io-events` thread → `IoInputTriggerTransport.applyDiChange` (`O:trigger/transport/IoInputTriggerTransport.java:253`).
2. **Trigger decision** (production config `di3_only:true, require_direction:true, direction_latch:false`): on DI3↑, requires DI2=1 and UI direction == physical direction; once per DI2 window (`captureFiredThisDi2Window`) → `publishLineCapture` → `bus.dispatchLineBroadcastWithoutPrefire("io_input")` → `triggerSequence = ++seq` and one event per camera queue (cap 512) (`InspectionTriggerBus.java:129-140, 183-224`). Dispatch is blocked while services are unhealthy.
3. **Light**: `IntervalFlashController` turns the whole flash bank On at DI2↑ and Off at DI2↓ — *independent from DI3* (`IntervalFlashController.java:137-191`).
4. **Camera thread** `ProductionInspectionOrchestrator.runTriggerDrivenLoop` (`O:pipeline/session/ProductionInspectionOrchestrator.java:97-124`): `awaitNext` → `PerCameraInspectionGate.tryBeginInspection` → STARTED / DISABLED (preview-only capture) / IN_FLIGHT.
5. **Async cycle** `AsyncInspectionCycleRunner.run` (`O:pipeline/session/AsyncInspectionCycleRunner.java:28-280`): capture → geometry → python → decision as chained `CompletableFuture`s on separate stage executors; SLA timeout (`inspection_cycle_timeout_ms 3500`) covers only up to python completion.
6. **Capture** `WorkerCaptureCoordinator.runCaptureSync` (`O:pipeline/stages/WorkerCaptureCoordinator.java:119-220`) → `LineSynchronizedCaptureCoordinator.captureForLine` → worker `capture{wait_frame:true, capture_window_*}`; frame header = SHM ref + `frame_id`, `timestamp_ns`.
7. **Geometry** `InspectGeometryExecutor.apply` (`…/InspectGeometryExecutor.java:73-226`): skip rules (no reference / `geometry_enabled:false` / client reference without joint ROI) → header via `BinaryInspectHeaders.geometryInspectHeader` (`O:pipeline/BinaryInspectHeaders.java:23-110`) + profile overrides + runtime overrides → round-robin pool + `geometrySlots` semaphore → `inspect_shm`.
8. **Python** `InspectPythonExecutor.apply` (`…/InspectPythonExecutor.java:41-241`): header (`pythonInspectHeader` :393-428 + `attachReferenceAndRoi` :503-521), `inspect_scale` per camera (`InspectionScaleConfig`), weighted server selection via `PythonWorkloadBalancer.reserve`, `pythonSlots` semaphore, HTTP `/inspect-shm`; response mapped by `inspectJsonToStdioHeader` (`AnalisSurfaceHttpBinaryRpcSupervisor.java:987-1015`: `ok = status ∉ {БРАК, FAIL, ERROR, REJECT}`).
9. **Decision** `DefaultInspectionDecisionAggregator.decide` (`…/decision/…:21-94`): `overallPass = python ok ∧ geometry overallPass`; `SKIPPED` geometry counts as pass; “no reference” both ⇒ `CAPTURE` action.
10. **Bucket** `BucketInspectionAggregator.recordFrameResult` (`…/bucket/…:179-245`) — early reject on first failing required camera; complete when all required cameras reported or timeout; `publishBucket` (:310-408) applies seam strict gate; `enqueueSyncedFanOut` (:414-453) barriers both groups of one sequence.
11. **Fan-out** `FanOutCoordinator.publishBuckets` (`O:fanout/FanOutCoordinator.java:150-177`): health gate → `IoRejectPublisher.publishBatch` (one FIFO task, one SDK critical section; `levels[port] = (!pass)==activeHigh`) → WS `server.inspect_bucket_result`; **after PLC**, deferred UI work (`pendingUi`) runs.
12. **Post-verdict (non-blocking)**: UI artifacts (`UiArtifactsSidecar.scheduleAfterInspection`), deferred heat-map render (`/render-heatmap` by token), deferred learning review, frame archive, pinned SHM release (`releaseCycleShm`).

Capture arming depends on mode: step 2 describes the DI3-gated path. With `directionWindowCapture`, `IoInputTriggerTransport` opens the frame window and dispatches capture at DI2↑, then closes it at DI2↓. The reviewed 2026-10-16 exports show this DI2-window path; do not equate window opening with the camera's electrical trigger or image-availability time. [Measurement boundaries](./performance/2026-10-16-log-review.md#2-correlation-rules).

## 8. State machines (cross-cutting)

- **Session state** (`O:clientws/session/ClientWsSessionState`): `NO_REFERENCE → READY → OPERATIONAL` after `client.reference_bundle`; `TEST` entered via `POST /api/client/mode/test` (inspection by DI3 suspended, UI runs test-analyze). PLC `vision_ready = referenceActive(state ∉ {NO_REFERENCE,TEST}) ∧ healthy` (`FanOutCoordinator.refreshPlcLevels:284-304`).
- **Per-camera inspection gate** (`PerCameraInspectionGate`): `enabled` (soft stop), `inFlight`, `cancelRequested`, `systemBlocked` (set by health pause), sequence counters, `armInspectionAfter` (resume only after a given trigger sequence).
- **Health**: `ServiceHealthGate` (set of unhealthy keys) ← `CriticalServiceWatchdog` (poll 2 s, retry 10 s) → `CriticalServiceInspectionPause` (clears queues, disables & cancels all cameras, restores snapshot on recovery) → `FanOutCoordinator.refreshPlcLevels` (ready=0, fault via DO2 timer).
- **Process supervision**: worker/geometry/positioning supervisors retry commands 3× with restart (`WorkerProcessSupervisor.command:65-84`); watchdog restarts analisSurface pool; outer `StackSupervisorMain` restarts the whole orchestrator on health failure (6 misses × 5 s) and may schedule a Windows reboot after 3 failed recoveries.

## 9. Failure-mode matrix

| Failure | Detection | System reaction | Operator-visible |
|---|---|---|---|
| analisSurface/geometry process dies | `ExternalServiceProcess.onUnexpectedExit` / probe | `handleDeath` → health gate unhealthy → inspection paused, DO2 fault pulse, `vision_ready=0`; restart attempts with back‑off | UI backend status |
| Camera frame missing (timeout, lost packets) | worker error `hik timeout/error`, `lost_packets` | cycle gets unusable capture ⇒ geometry/python skipped ⇒ REJECT for that camera; bucket still completes | camera card reject |
| Camera never reports into bucket | `timeout_ms 3500` | bucket published with `missingRequired ⇒ anyReject` | line reject |
| Only one line group finished | sequence barrier timeout | synthetic reject for the missing line (`onSequenceSyncTimeout`) | line reject |
| IO controller disconnects | `MvsIoTransport` reconnect loop (1 s) | outputs raise `IllegalStateException`; session reset event clears direction/work state | log `io event=connection_failed` |
| PLC FINS unreachable | UDP timeout 1000 ms | publisher queue (64) drops jobs, counter `droppedTotal` | PLC panel |
| Frontend (Electron) exits | `ExternalServiceProcess.onUnexpectedExit` | `OrchestratorStopSignal("frontend_exited")` → full shutdown, `vision_ready=0` | kiosk loop restarts UI after 5 s |
| DI4=1 | `DiShutdownController` | `LightsShutdown`, stop signal | – |
| Orchestrator crash/hang | `StackSupervisorMain` health probe | kill process tree, free ports (8000‑8009, 8099, 8765, 5173, 8088), respawn | – |

## 10. Technology & hardware facts

- Cameras: 10 × Hikrobot/Hikvision GigE; 5 switches × 2 cameras (egress buffer 448 KB); frame 1224×1024 (binning 2×2 on camera), BGR8.
- Lights: MV‑LE controllers — 4 Ethernet (`169.254.220.{1,2,22,23}`, 4 ch) + 1 COM (2 ch, cams 3 & 8).
- IO: MV‑VC3704H (`DA8813541`, `COM_Port#ttyS4`), PLC Omron at `169.254.220.232:9600` (node 231/254), network `169.254.0.0/16` link-local.
- Toolchain: JDK 17+, Maven; CMake + MVS SDK at `/opt/MVS`; Node ≥20.19; Python venv `analisSurface/backend/.venv`.
- Tests: orchestrator ≈358 JUnit (355 pass, 3 skipped at 2026‑10‑15), geometry 46, Python ≈384 pass / 11 skip, front-end 45 Vitest (2026‑10‑08), camera-worker C tests, `deploy/linux/test_*.py`.

## 11. Index: “where do I look for …”

| Question | Go to |
|---|---|
| What exactly triggers a capture? | [orchestrator/overview.md#trigger-subsystem](./orchestrator/overview.md#trigger-subsystem) |
| Why hold‑mode DO instead of FINS pulses? | [orchestrator/architecture_why.md#reject-signalling](./orchestrator/architecture_why.md#reject-signalling) |
| What does geometry measure and thresholds? | [geometry/overview.md](./geometry/overview.md) |
| Customer's seam tolerance and horizontal/vertical label-placement requirements | [geometry/customer_requirements.md](./geometry/customer_requirements.md) — requirements, not an implemented feature claim |
| How is a Python verdict computed (steps, files, lines)? | [analis-surface/overview.md#pipeline](./analis-surface/overview.md#pipeline-_inspect_frame_unlocked) |
| Which knobs exist & how 0..100 % maps? | [analis-surface/overview.md#analysis-settings](./analis-surface/overview.md#analysis-settings) |
| IMLB wire format | [camera-worker/api_contracts.md#imlb-framing](./camera-worker/api_contracts.md#imlb-framing) |
| WS messages & reference bundle schema | [orchestrator/api_contracts.md](./orchestrator/api_contracts.md) |
| PLC register map | [orchestrator/api_contracts.md#plc-fins](./orchestrator/api_contracts.md#plc-fins) |
| Build/run/RT/kiosk | [deploy-ops/overview.md](./deploy-ops/overview.md) |
| Latest implemented decisions: X/Y positioning, preserved ROI, deferred artifacts | [recent_context.md](./recent_context.md) |
| Latest machine latency, DI2 deadlines and learning spikes | [2026-10-16 log review](./performance/2026-10-16-log-review.md) |
| What to optimise next; is C++ already implemented? | [Python optimisation plan](./analis-surface/optimization_plan.md) |
| What changed when, and why | [global_changelog.md](./global_changelog.md) |

## 12. Maintenance protocol for future changes

1. **Locate & navigate** — start here, follow the link to the service, use its *Code map* to find file/lines **before** editing.
2. **Implement & explain** — after the code change add a changelog entry in the mandatory format:
   *What changed* (files+lines) / *Why it was done* (architecture reason, trade‑offs, why this pattern) / *Impact* (other services, global state).
3. **Update MD files** — update the affected `overview/api_contracts/architecture_why` and this file if a link, contract, port, key or flow changed;
   keep `⚠️ [GUESS/CONJECTURE]` only where no evidence exists, and replace it when the author explains the reason.

## 13. Known gaps & open risks

- Production `config/` directory is not versioned (only `config.exemple/`); IPs/COM ports must be configured per machine; camera IPs duplicated in `config.json` **and** `10-cameras.yaml`.
- DI scan ≈ 41 ms; short pulses/two events within ~80 ms may be missed (audit §4). Light‑on is not acknowledged before capture (audit §6).
- Camera-worker flushes the SDK buffer before `wait_frame`; a frame that arrived earlier is dropped (audit §5); some `TriggerMode` set errors are ignored (audit §7).
- `tools/run-analis-isolated.sh` (Linux python launcher) is referenced by config but `/tools/` is gitignored — not reproducible from the repo.
- `.idea/`, historic `LightServer.v3/logs/*.log`, `bin/obj` artifacts and an `.exe` were committed in the past; ignore for architecture.
- Stale docs: `analisSurface/docs/GUIDE.md` (PatchCore), `analisSurface/README.md` (removed `c_capture`), `front-end/README.md` (API path), UI label “Стальная ручка 111” (`F:src/app/App.tsx:175`, stray “111”).
