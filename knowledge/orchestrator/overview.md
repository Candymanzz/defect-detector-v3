# Orchestrator (`orchestrator-java/`)

Back to [global_architecture.md](../global_architecture.md) · [API contracts](./api_contracts.md) · [Why](./architecture_why.md)
Path shorthand: `O:` = `orchestrator-java/src/main/java/com/example/iml/orchestrator/integration/`, `R:` = `orchestrator-java/src/main/java/com/example/iml/orchestrator/`.

## 1. Purpose
The single control process of the line (≈40.5 kLOC, 1 JVM, started as `java -Xms256m -Xmx1024m -XX:+ExitOnOutOfMemoryError -jar orchestrator-0.1.0-SNAPSHOT.jar config/config.yaml`
by `StackSupervisorMain`, `R:supervisor/StackSupervisorMain.java:~318`). It:
1. loads layered YAML and boots/supervises every other process;
2. converts IO-controller DI edges into numbered inspection cycles (`triggerSequence`);
3. synchronises capture on 10 cameras and runs the per-camera pipeline (geometry + surface);
4. aggregates camera verdicts per line (bucket group) and drives PLC outputs (DO + FINS);
5. serves HTTP (:8099) and WebSocket (:8765) to the UI;
6. owns lighting (5 MV‑LE controllers) and the IO controller (DI/DO/timers) **in-process through JNA** — LightServer.v3/IoInputMonitor were merged in (`e58636e1`).

## 2. Stack & build
Java 17 · Maven (`orchestrator-java/pom.xml`, shaded JAR) · Log4j2 · Jackson · JNA (`libMvCameraControl.so` Linux `/opt/MVS/lib/64`, Windows `native/mvs/win64/MvCameraControl.dll`) ·
`org.java_websocket` (WS server) · JDK `HttpServer` (UI) · `java.net.http` (analisSurface client). No DB. Tests: `src/test/**` (~358).
Build: `bash ./rebuild-and-run.sh -c` (skip camera-worker) → `-Dmaven.test.skip=true`.

## 3. Code map (package `com.example.iml.orchestrator`)

### 3.1 Entry & boot
| File (lines) | Role |
|---|---|
| `R:OrchestratorMain.java` | CLI arg = config path (default `config/config.yaml`); exit 1 if missing, 2 on `OrchestratorStartupException`; static init `SessionLogs.initialize()` |
| `R:OrchestratorApplication.java:31-50` | load → `StartupConfigurationReporter.report` → `IntegrationBootstrap.start(root, projectRoot)` |
| `R:config/YamlFileConfigLoader` | resolves `imports:` (blocks) into one `Map<String,Object>` |
| `O:bootstrap/IntegrationBootstrap.java:32-65` | the 6-step boot; on exception `fanOut.signalVisionFault(true)`; `finally` → `IntegrationShutdownHook.run` + `lifecycle.close()` |
| `O:bootstrap/service/*` | see global §6. `PipelineCameraRuntimeService.runBlocking:54-147` is the real “main loop” (blocks in `awaitCameraTasksOrStop:329-379`) |
| `O:bootstrap/context/IntegrationRuntimeContext` (656 lines) | mutable service locator passed everywhere (≈80 setters) — ⚠️ [GUESS/CONJECTURE] grew organically instead of DI |
| `O:bootstrap/lifecycle/*` | `IntegrationShutdownHook`, `IntegrationShutdownCoordinator`, `OrchestratorStopSignal` (reason string, await), `IntegrationLifecycleComposite` (close-only) |
| `O:bootstrap/factory/DefaultIntegrationServicePoolFactory` | creates geometry/positioning `ServiceProcessSupervisor` pools, python HTTP clients, stage executors (`stage-capture/python/geometry/decision`, bounded queue `stage_queue_size 20`) |
| `R:supervisor/StackSupervisorMain.java` (605) | external watchdog JVM: probes `http://127.0.0.1:8099/health` and `:8000/detector/health`; env `IML_SUPERVISOR_*` (interval 5 s, fail threshold 6, startup timeout 180 s, stable 120 s, reboot after 3 failures); kills process tree & orphan ports `DEV_STACK_PORTS` (8000‑8009, 8099, 8765, 5173, 8088); attach mode to a launcher PID |

### 3.2 Camera & capture
| File | Role |
|---|---|
| `O:camera/WorkerProcessSupervisor.java:17-100` | per-camera `camera_worker <config.json> <id> --binary-stdio`; `command()` = ≤3 attempts, `restart()` between; `reportUnhealthy()` on failure |
| `O:binaryrpc/AbstractBinaryRpcSupervisor` | shared: timeout executor, `commandNoRetry`, `restart`, `restartCount`, health listener |
| `O:camera/BinaryProcessClient` / `NamedPipeBinaryClient` / `WorkerIpcMode` | stdio vs named pipe/UDS transports for IMLB |
| `O:camera/CameraSettingsService`, `CameraSettingsStore` | PATCH keys → worker `set_settings`; persisted to `config/data/camera_runtime_settings.json`, re-applied at boot (excluding `capture_trigger_mode` when hardware trigger) |
| `O:capture/LineSynchronizedCaptureCoordinator.java` (909) | per-sequence `Round`; `captureForLine` (barrier → leader fires → each camera `wait_frame`), `collectWaitFrames[Parallel]` (waves, `transfer_wave_gap_ms`), late-join (Stop→Start), first-frame callback; `lineCaptureExecutor` threads priority MAX |
| `O:capture/DirectionCaptureWindow.java` | token file shared with workers; `open()` at DI2↑, `close()` at DI2↓, `bind(seq)`, `command(token)` adds `capture_window_path/token` to `capture` |
| `O:capture/LineFramePinService` | copies a ring slot to `iml_line_pin_cam<id>_f<seq>`; `releasePinnedCapture` after cycle |
| `O:capture/ImlShmJanitor.java` | purge on start (non-stable names), TTL 45 s every 15 s for pins; stable patterns `iml_cam_N_frame`, `iml_ref_camN`, `iml_pos_cam_N`, `iml_ds_*`, `iml_py_ds_*`, `iml_ui_*` |
| `O:capture/FrameJpegWriter` | SHM path resolution (`/dev/shm` or `%LOCALAPPDATA%\iml_shm`), JPEG saving (`save_captures`) |
| `O:stream/CameraStreamService` + `MjpegStreamHub` | client video stream: worker `start_stream`+`stream_poll`, MJPEG fan-out; inspection blocked while streaming (`WorkerCaptureCoordinator:138-141`) |
| `O:preview/LivePreviewPublisher` (761) | periodic capture → JPEG on ui_http + WS `server.preview_batch`; disabled in config (`live_preview.enabled:false`); blocked by `LivePreviewGate` during inspection |

### 3.3 Trigger subsystem
<a id="trigger-subsystem"></a>
| File | Role |
|---|---|
| `O:io/mvs/MvsIoTransport.java` | **acquisition thread** `mvs-io-acquisition` (connect, seed state, poll DI each `poll_ms`, DO poll each `output_poll_ms`, reconnect 1 s) and **processing thread** `mvs-io-events` (FIFO, drops events of old session generation). Startup: DI3 seeded 0 (never synthesise a trigger), `startup_edge_only_inputs:[4]` |
| `O:io/mvs/JnaMvsIoDevice.java` | SDK calls via JNA (`MV_CC_*`): open (:56-92, expects exactly one MV‑VC controller), `readInputs` (:144, per-DI selector + `LineStatus`, yields lock between DI), `setOutputs` (:177-203, hold levels via `LineSource On/Off`, validates whole batch first, DO5 protected), `triggerTimer` (:205), `readOutputs` (:227), `subscribe` (:239, event backend) |
| `O:io/mvs/MvsTimerCommand.java` | `configure` (timers/enums/integers, never Timer5/Out5) and `trigger` (checks TimerTriggerSource=Software, LineSource=timer, TriggerSoftware writable) ; restores selectors in `finally` |
| `O:io/mvs/MvsIoConfig`, `MvsIoDevices`, `MvsSdkLease`, `MvsNativeLibrary`, `MvsIoAccess` (read/write lock around the single handle) | config parsing, backend factory (`mv_camera` vs `mv_io`), ref-counted `MV_CC_Initialize/Finalize`, library loader |
| `O:trigger/transport/IoInputTriggerTransport.java` (943) | DI semantics; the core is `handleDiChange:263-563` (see §4) |
| `O:trigger/InspectionTriggerBus.java` | `sequence` counter, per-camera queues (512), `prefireLineBroadcast`, `dispatchLineBroadcast[WithoutPrefire]`, `injectSequence` (Stop→Start rejoin), `setDispatchAllowed(health)`, optional stagger |
| `O:trigger/InspectionTriggerRuntime.java:112-174` | wires transport(s) and `MvsIoTransport`; `activateNativeIo()` starts DI scanning *last* (after listeners & health gates) |
| `O:trigger/strategy/{Bus,Timer,Continuous}TriggerStrategy`, `InspectionTriggerStrategyFactory` | `awaitNext(cameraId)` abstraction; production = `BusTriggerStrategy` |
| `O:trigger/ManualLineDirectionService` | UI forward/reverse; capture only if DI2 matches |
| `O:trigger/DiShutdownController` | DI4 0→1 once per process → `LightsShutdown` + `OrchestratorStopSignal` |
| `O:trigger/BucketLineTriggerBroadcaster` | periodic sequences for timer/continuous modes with buckets |
| `O:trigger/config/*`, `parse/*`, `gpio/*`, `transport/IoInputDirection*` | config records, UDP payload parsers (legacy `io_input`), edge evaluator, latch variants (`direction_latch*`, `direction_arm_next_di3`), all disabled in production config |
| `O:lighting/IntervalFlashController.java:137-191` | DI2↑ → bank On, DI2↓ → bank Off (own single thread) |

### 3.4 Pipeline
| File | Role |
|---|---|
| `O:pipeline/InspectionPipeline.java:42-186` | `processCamera`: resolves `productType` = camera `analysis_profile`, builds shared `AsyncInspectionCycleInput`, reference bootstrap (only when `reference_source=camera`), calls `ProductionInspectionOrchestrator.run` |
| `O:pipeline/session/ProductionInspectionOrchestrator.java:97-292` | per-camera infinite loop; `runCycle:126-248` (gate, preview-only for disabled cameras, `inspection_id = triggerSequence`), `resolveCycleInput:265-286` |
| `O:pipeline/session/AsyncInspectionCycleRunner.java:28-526` | stage chaining, cancel polling every `CANCEL_POLL_INTERVAL_MS`, capture-only variant `:287-409`, SLA `awaitPythonOrCancel:435-473` |
| `O:pipeline/session/PerCameraInspectionGate.java` (418) | see global §8; `runIfInspectionActive` guards result publishing against client stop |
| `O:pipeline/session/InspectionCycleResumeService` | Stop→Start: injects the camera into the currently open `triggerSequence` (`bus.injectSequence`, `markLateJoin`) |
| `O:pipeline/stages/WorkerCaptureCoordinator.java:119-220` | `runCaptureSync`; `hasUsableCaptureHeader` = shm_name & width/height>0 & frame_id≥0; no sync fallback in line mode (avoid double exposure) |
| `O:pipeline/stages/InspectGeometryExecutor.java:73-226` | + `applyPoseQcFromPositioning:257-310` (recomputes alignment QC from positioning metrics when `aligned`) |
| `O:pipeline/stages/InspectPositioningExecutor.java` (310) | optional Java positioning stage (disabled) — sets `HEADER_ALIGNED`, `HEADER_HARD_FAIL`, `positioning_*` into capture header |
| `O:pipeline/stages/InspectPythonExecutor.java:41-241` | header build, scale, weighted reserve → HTTP |
| `O:pipeline/stages/CaptureFrameDownscaleService`, `PythonInspectDownscaleSupport` | optional pre-downscale (config off); superseded by in-Python `inspect_scale` |
| `O:pipeline/BinaryInspectHeaders.java` (620) | all header builders: `geometryInspectHeader:23`, `positioningHeader:115`, `applyGeometryProfileOverrides:167`, `applyMainRoiFromPolygon:369`, `pythonInspectHeader:393/430`, `pythonTestFrameInspectHeader:450`, `attachReferenceAndRoi:503`, `setReferenceShmHeader:601` |
| `O:pipeline/decision/DefaultInspectionDecisionAggregator.java` | verdict per frame; `InspectionDecision` record (`O:pipeline/InspectionDecision.java`) incl. `hasAnomalyScore()` guard |
| `O:pipeline/bucket/BucketInspectionAggregator.java` (644) | line aggregation; `BucketInspectionConfig`, `BucketGroup`, `JointSeamPolicy`, `InspectionOperationMode` |
| `O:pipeline/reference/PipelineReferenceRegistry.java:59-109` | `applyClientBundle`: per view builds capture-like header (`frameToCaptureHeader:190`), pins durable copy (`pinDurableReference:114`), `set_reference_shm` to *every unique* python server; bucket group id = `minCameraId / 5` |
| `O:pipeline/telemetry/PipelineInspectionTelemetry`, `session/InspectionStageTimingLogger`, `logging/PipelineStagesLog` | `inspection_timing …`/`logs/pipeline-stages.jsonl` |
| `O:python/PythonWorkloadBalancer.java` | server choice (see api_contracts §6.3) ; `AnalisSurfacePoolSupport.uniqueServerClients` |
| `O:python/AnalisSurfaceLauncher.java` | starts `poolSize` uvicorns at `base_port+i` (8000‑8005), env `ANALIS_INSPECT_WORKERS` = `inspect_workers` (3) |

### 3.5 Output
| File | Role |
|---|---|
| `O:fanout/FanOutCoordinator.java` (568) | see §5 |
| `O:io/mvs/IoRejectPublisher.java`, `IoRejectConfig`, `IoFaultPublisher` | DO reject/fault (see §5) |
| `O:plc/*` | FINS: `PlcFinsPublisher` (queue 64, worker thread `plc-fins-publisher`), `fins/FinsFrameBuilder` (write bit / write words / read words), `OmronFinsClient/Transport` (UDP, SID, traffic observers), `PlcRegisterMapLoader` (`plk/register-map.yaml`), `PlcBcd` (units ×100 ms), `PlcFinsServiceHolder` |
| `O:clientws/**` | WS server, router (`WsRouter` ← `WsRoute`s), handlers, `ReferenceBundleParser` (382), `WsOutboundMessenger` (785), `ClientWsSessionState`, `AnalisSurfaceClientWsSync` (bundle → python) |
| `O:ui/UiHttpServer` (565) + `http/HttpFrontController` + `http/controller/*` | REST; `UiArtifactsSidecar` (1264): after-inspection artifacts; `FrameArchiveService` (806); `InspectionArtifactRegistry`, `HeatmapArtifactRegistry`, `GeometrySnapshotCache`, `CameraPreviewStore` |
| `O:clientapi/*` | `ClientApiMount` (flags + holders), `AnalisSurfaceHttpBinaryRpcSupervisor` (1336, HTTP↔IMLB emulation), `GeometryRuntimeConfig/Store` (per-profile overrides), `UiTestAnalyzeService` (941, TEST mode), `TestFramePinStore`, `LearnedReviewIndex`, `JpegBgrShmWriter` |
| `O:lighting/*` | `LightTriggerClient` (843) high-level API; `nativeio/NativeLightBank` (per-device session threads `light-<id>`, bounded FIFO, 1 job = 1 command, health probe), `JnaLightDevice`, `LightBrightnessStore/Commands/Scale`, `LightsShutdown` (JVM hook), `LightServersConfig/RuntimeConfig`, legacy `LightServerV3Http`, `LightUpstreamClient` |
| `O:health/*` | `ServiceHealthGate`, `CriticalServiceWatchdog` (689), `CriticalServiceInspectionPause` |
| `O:services/*`, `subprocess/*` | `ServiceProcessSupervisor` (IMLB stdio child), `ExternalServiceProcess` (+`onUnexpectedExit`, `isClosing`), `IntegrationExternalProcessLauncher` (npm/PATH resolution), `ServicePoolLifecycle` |

## 4. Trigger decision logic (production profile)
Config (`52-io.yaml`): `inspection_trigger.io_input { di3_only:true, require_direction:true, require_work:false, direction_latch:false, direction_latch_on_work:false, trigger_edge:rising, external_hardware_capture:true }`, `mvs_io.enabled:true backend:mv_camera input_mode:poll`.

`IoInputTriggerTransport.handleDiChange` (:263-563) for this profile:
- `change == null` ⇒ **session reset** (MVS reconnect): clears work/direction/trigger flags (:264-276).
- every DI → `notifyDiChangeListeners` (IntervalFlash, DiShutdown) *before* capture logic (:279).
- DI1 (work): only updates `workActive`/`lineWorkActive` (not required).
- DI2 (direction): `directionActive = mapDirection(raw)` (invert flag); with `direction_window_capture` it opens/closes `DirectionCaptureWindow` and may fire capture at DI2↑ (:393-403). Otherwise `previousMapped != mapped ⇒ captureFiredThisDi2Window=false` (:405-407).
- DI3 rising (:441-483, `di3_only` branch): `directionOk = !requireDirection || directionMatchesSelected()`; skip if not OK, or if already captured in this DI2 window (non-hardware sources); else `fireLineCapture()` (:721-759) → `allowsCaptureForSelectedDirection` → `publishLineCapture` (:773-786): with `hardwareObservationSource` requires DI2=1 and calls `bus.dispatchLineBroadcastWithoutPrefire` (cameras are exposed by the electrical chain).
- DI4: ignored here (handled by `DiShutdownController`).
Debounce: none by design (`MvsIoTransport` comment “no debounce, refractory or SkipBusy”).

## 5. Verdict & output path
- **`BucketInspectionAggregator`** (global §7 step 10). Config: `inspection_bucket {enabled, mode: ten_cameras, timeout_ms 3500}` → groups `0:[0-4]`, `1:[5-9]` (`BucketInspectionConfig`, preset 5 cameras per group). A camera is *required* iff its gate has inspection enabled (soft stop excludes it).
  Complete ⇒ `publishBucket`. Result `BucketFanOutResult(groupId, triggerSequence, overallPass, bucketCameraIds, frameDecisions)`.
  Seam gate (`evaluateSeamStrictGate:555-585`): only when the joint camera failed `jointPass` **and** mean sibling visibility `< siblingMinVisibility (0.25)`: bucket still passes if `JointSeamPolicy.passesStrict(parallelism, width)`; otherwise `forceReject`.
  Note: when `joint.jointPass()` is true the gate is inactive — low seam *visibility* on siblings can never fail a bucket by itself (siblings can still fail through their own Python/geometry verdicts).
- **`FanOutCoordinator.publishBuckets` (:150-177)**: in DO‑hold mode all groups of one sequence in one batch (`IoRejectPublisher.publishBatch`); `ServiceHealthGate` unhealthy ⇒ publish skipped (warn); `inspectionEnabled()` (= session ∉ {NO_REFERENCE,TEST}) required for physical output; then `clientWsServer.notifyInspectBucketResult` per group.
- **`IoRejectPublisher`** (`O:io/mvs/IoRejectPublisher.java`): modes `hold` (production), `level` (set + optional pulse), `timer` (software timer trigger). Hold: one FIFO thread `io-reject-hold`, `heldSequences` fence (older sequences ignored), `earlyRejectedSequences` (a later PASS of same sequence cannot release), `generation` counter invalidates queued tasks on `resetIdle()`; ack timeout `ack_timeout_ms 3000`; on close writes idle levels (PASS). Idle level of each group is `!activeHigh`.
- **`IoFaultPublisher`**: one `Timer2→Out2` software-timer pulse per fault episode; retries every `retry_ms` only if SDK did not accept.
- **FINS** (`PlcFinsPublisher`, FINS-mode only): `publishBucket(result, awaitEdge, holdRejectUntilPass)`; reject signals `reject_line_1/2` are *removed from the FINS map when DO reject is enabled* (`FanOutCoordinator.fromConfig:94-100`), as is `vision_fault` when `io_fault` is enabled.

## 6. Lighting (native)
`LightTriggerClient.fromRootYaml` (:55) builds `NativeLightBank` (`light_hardware.devices`, `camera_routes`). API stays HTTP-shaped (`flashBankUrl = /api/camera-flash/bank`, calls routed in-process by `nativeLightRequest`).
Modes: `hold_mode:true` ⇒ no On/Off in capture path (`runCaptureWithLighting:414-455`); production uses **interval flash**: `IntervalFlashController` On at DI2↑ / Off at DI2↓ via `bankAllOn/bankAllOff` (parallel across devices). `startupEngage()` sets brightness, `armStartDark()` extinguishes (start_dark). `constant` mode (UI `PUT /api/orchestrator/light/mode`) keeps lights always On.
`NativeLightBank.Session.connect` always starts **dark** after reconnect (no replay of stale On); `activate` refreshes brightness (`refresh_before_on`) when brightness changed while Off. Brightness API scale 0..255 / UI 0..100 %, persisted by `LightBrightnessStore`. `LightsShutdown` is idempotent and runs from the JVM shutdown hook.

## 7. Thread inventory (names seen in code)
`camera-flow` ×N (one blocking task per camera), `stage-capture|python|geometry|decision`, `line-capture` (MAX priority), `mvs-io-acquisition`, `mvs-io-events`, `io-reject-hold`/`io-reject-DO<n>`, `io-fault-timer`, `interval-flash`, `light-<deviceId>`, `light-health`, `plc-fins-publisher`, `critical-service-watchdog`, `bucket-inspection-timeout`, `iml-shm-janitor`, `svc-boot`, `camera-worker-boot`, `worker-call-<id>`, `ui-test-analyze`, `inspection-trigger-stagger`, `io-input-direction-wait`, `io-input-capture-delay`, UI publish executors.

## 8. Configuration owned (all under `config/blocks`)
`integration.*` (`01-core`): pools (`camera_parallelism 10`, `geometry_pool_size 10`, `python_server_pool_size 6`, `python_parallelism 12`, `python_request_parallelism 10`, `stage_queue_size 20`), timeouts (`worker_command_timeout_ms 30000`, `service_command_timeout_ms 15000`, `inspection_cycle_timeout_ms 3500`), `simultaneous_line_capture`, `inspection_bucket`, `client_stream`, `save_captures`, autostart commands.
`cameras` (`10-cameras`), `java_geometry`, `java_positioning`, `python_detector` (`20-detectors`), `ui_http`, `frame_archive`, `client_api`, `client_ws` (`30-ui-and-client`), `plc_fins` (`45`), `light_servers` (`50`), `light_hardware` (`51`), `integration.inspection_trigger/io_reject/io_fault/interval_flash` (`52-io`).
Full annotated templates: `config.exemple/blocks/*.yaml` (comments are the best field reference).

## 9. Tests worth knowing
`BucketInspectionAggregatorTest` (early reject, soft-stop, barrier), `DoRejectRoutingTest`, `IoRejectPublisherTest`, `InspectionScaleConfigTest`, `InspectPythonExecutorTest`, `BinaryInspectHeadersTest`, `ServiceHealthGateTest`, `PlcRegisterMapLoaderTest`, `IntervalFlash…`. Run: `cd orchestrator-java && mvn test` or `bash ./run-all-tests.sh`.
