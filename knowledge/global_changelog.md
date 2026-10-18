# Global Changelog (reverse-engineered)

Back to [global_architecture.md](./global_architecture.md)

Source: `git log` of branch `testlinux` (615 commits, 2026‑05‑14 → 2026‑10‑16) plus code reading. Commit messages are terse Russian; each entry below uses the mandatory protocol format:
**What changed** (files / lines) · **Why it was done** (architectural reason, trade-offs, why this pattern; **[doc]** = from commit message, repo docs or code comments; `⚠️ [GUESS/CONJECTURE]` = inferred) · **Impact** (other services / global state).
Newest first. Entries marked *(reconstructed)* summarise multi-commit work. Hash anchors can be inspected with `git show <hash>`.
Legend of service links: [orchestrator](./orchestrator/overview.md) · [camera-worker](./camera-worker/overview.md) · [geometry](./geometry/overview.md) · [positioning](./positioning/overview.md) · [analis-surface](./analis-surface/overview.md) · [front-end](./front-end/overview.md) · [legacy-hardware](./legacy-hardware/overview.md) · [deploy-ops](./deploy-ops/overview.md).

---

## 0. Knowledge-base era

### 2026‑10‑18 — Knowledge base created and deepened
- **What changed:** new directory `knowledge/` (this tree). No source files touched.
- **Why:** repo is nearly finished but history is incomplete; a link-driven KB lets a future change start at [global_architecture.md](./global_architecture.md) and jump to exact files/lines instead of re-scanning ~900 files.
- **Impact:** none on runtime. Findings recorded while documenting (candidates for fixes):
  1. `python_detector.setting_ranges` / `knob_ranges` in `20-detectors.yaml` are **dead config** — Python stopped reading them in `032455c0` ([analis-surface §5](./analis-surface/overview.md#analysis-settings)).
  2. `ReferenceShmMatCache` javadoc says 10 references, constant is 2 ([geometry why §3](./geometry/architecture_why.md)).
  3. `tools/run-analis-isolated.sh` referenced by config but `/tools/` is gitignored.
  4. Stale docs: `analisSurface/docs/GUIDE.md` (PatchCore), `analisSurface/README.md` (`c_capture`), `front-end/README.md` (API path); UI label “Стальная ручка 111” (`App.tsx:175`); TS `WsSessionState` lacks `TEST`.
  5. `AnalysisSettings.use_patchcore` is dead after `db7e2961`.

---

## 6. Phase 6 — Linux production, RT tuning, latency (2026‑10‑08 → 2026‑10‑16)

### 2026‑10‑16 · `18fcd180` — Early reject for any failing camera of a line
- **What changed:** `O:fanout/FanOutCoordinator` (+13: `publishEarlyBucketReject`), `O:fanout/BucketFanOutSink` (+4 default method), `O:io/mvs/IoRejectPublisher` (+18: `earlyRejectedSequences`, `publishEarlyReject`, fence in `publishBatch`), `O:pipeline/bucket/BucketInspectionAggregator` (+19: `earlyRejectPublished`, `isCameraRequired`, `bucketPass = !anyReject && !earlyRejectPublished`); tests `DoRejectRoutingTest`, `IoRejectPublisherTest`, `BucketInspectionAggregatorTest` (+89).
- **Why:** [doc: message “отбраковка дискретки при файла на любой из камер”] DO must drop as soon as any required camera of the line fails, not after the slowest camera; trade-off: early delivery is an optimisation — failure is swallowed and the final batch retries; once emitted the verdict is final for the sequence.
- **Impact:** PLC sees reject earlier (DO3/DO4); UI may update later (PLC-first ordering kept); FINS-mode semantics untouched (`publishEarlyPlasticHandleReject` still handles plastic). See [orchestrator why §5](./orchestrator/architecture_why.md).

### 2026‑10‑16 · `43ec2a9a` — “save roi”
- **What changed:** `F:components/ReferenceSetup/ReferenceController.ts` (+95), `ReferenceSetup.tsx` (+23).
- **Why:** ⚠️ [GUESS/CONJECTURE] ROI edits were not persisted across reference commits; controller now persists contours with the staged bundle (`stageReferenceBundleContours`).
- **Impact:** front-end only (IndexedDB library).

### 2026‑10‑16 · `db7e2961` — Remove ML branch (anomalib/PatchCore)
- **What changed:** `analisSurface/backend/requirements.txt` (−`anomalib`), `S:inspection_service.py` (−30: `_load_anomalib_engine`, `_anomaly_engine`, the `use_patchcore` branch in `_run_anomaly_model`).
- **Why:** [doc: message “удалил все с моделями и anomalib”; **[code]** diff] the branch was optional (silent `None` when the OpenVINO model file was missing) and the verdict is heuristic; remove dependency and dead path.
- **Impact:** Python start-up smaller; settings field `use_patchcore` now inert; docs stale.

### 2026‑10‑15 · `c9648be6` — Per-camera inspect scale for centre cameras
- **What changed:** new `O:config/InspectionScaleConfig`, `InspectPythonExecutor` (−/+), `UiArtifactsSidecar`, `20-detectors.yaml` (+5: `inspect_scale_by_camera {2:0.5, 7:0.5}`), `INSPECTION_OPTIMIZATION.md`, tests.
- **Why:** [doc] end-on cameras 2 and 7 don't need 0.75 scale; applied *after* full-res alignment in Python.
- **Impact:** Python latency for 2/7 ↓; heat-map space differs per camera (`ReferenceBundleWsHandler.normalizeHeatmapSpaceForInspectScale`).

### 2026‑10‑15 · `a8f65721` — “Ultra” pipeline optimisation
- **What changed:** `IoRejectPublisher`/`JnaMvsIoDevice` (one FIFO + one SDK critical section for both DO, no enum read-back), `PythonWorkloadBalancer` (weighted EWMA scheduler, one job per server URL), Python immutable ROI/FP caches (32 MiB/process), reference prep once (`reference_preparation.py`), `inspection_evidence`/`inspection_geometry` hot-path rewrites, `scripts/verify_hotpath_equivalence.py`, `tests/test_pipeline_hotpath.py`, `INSPECTION_OPTIMIZATION.md` (+57).
- **Why:** [doc] get DO out *before DI2 falls*; three threads in one uvicorn process do not give three CPU-parallel inspections; results must stay numerically identical (30 map/mask/score + 78 compact residual comparisons identical, Σ median diff-map 580.0→536.9 ms).
- **Impact:** orchestrator↔Python routing semantics changed (server FIFO), DO write latency ↓. Needs on-line re-measurement (doc says micro-benchmark ≠ wall time).

### 2026‑10‑15 · `0da1c38e` — 6 Python processes on 6 cores
- **What changed:** `config.exemple/blocks/01-core.yaml` (3 lines: `python_parallelism 12`, `python_server_pool_size 6`, `inspect_workers 3`).
- **Why:** [doc: YAML comment “Шесть Python-процессов на шести физических ядрах; по 3 обработчика на процесс”].
- **Impact:** ports 8000‥8005; reference/ROI/FP replay to 6 servers; cross-process consistency tokens needed.

### 2026‑10‑15 · `c11d7b13` — Positioning reference prepared once; background workload gate
- **What changed:** `S:inspection_positioning.py` (351 lines rewritten → phase correlation), new `S:inspection_workload.py`, `S:reference_preparation.py`, `dependencies.py`, `inspection_routes.py`, `scripts/benchmark_optimization.py`, `validate_optimization.py`, tests; `INSPECTION_OPTIMIZATION.md` (+117).
- **Why:** [doc] move expensive preparation off the hot path; background tasks (history, deferred heat-map) must yield to inspections.
- **Impact:** `/render-heatmap` may return 429; deferred review may be dropped when its queue is full.

### 2026‑10‑15 · `4616cb7a`, `c499bf23` — Frontend update without password; `/opt/logs` permissions
- **What changed:** `deploy/linux/setup-frontend-updates.sh`, `defect-detector-restart-ui`, `rebuild-and-run.sh` (+39), `RUN.md`.
- **Why:** [doc] operator `o` must redeploy UI without sudo prompts, but only the three UI paths may be writable.
- **Impact:** deploy-ops only; see [deploy-ops why §8](./deploy-ops/architecture_why.md).

### 2026‑10‑14 · `0af3683a`, `7201861d`, `4c0c0ebc`, `8f03d0cd`, `a57269cc`, `7b5ce503`, `9f42f50c`, `80c92a89`, `e1c537b3` — RT diagnostics, logging, script split
- **What changed:** new `deploy/linux/{rt_diagnostics,stack_processes,stop_stack}.py` (+tests), `rt-logs.sh`, `run-supervised.sh` rewritten (95), `run-rt.sh`, `rebuild-and-run.sh` (build-only, frontend install), `RT-LINUX.md`, `LOGGING.md`, `RUN.md`; per-launch log dir `IML_LOG_SESSION_DIR`.
- **Why:** [doc] observe CPU/IRQ/PSI/schedstat during real runs without changing the system; separate build/run/measure; one directory correlates logs of all processes.
- **Impact:** all services write into the session directory; `-r` flags only observe.

### 2026‑10‑14 · `032455c0` — “актуальная версия на линукс” (large sync)
- **What changed:** Python: **new** `inspection_curvature.py` (315), `inspection_evidence.py` (217) (+119 in `inspection_geometry.py`), `inspection_routes.py`, `schemas.py`, `analysis_settings.py` (+13); `analysis_settings_presets.py` −142/+: configurable-range code from `4ee2291c` **removed** (109 deletions).
- **Why:** [doc: message only] sync of the Linux working copy; ⚠️ [GUESS/CONJECTURE] the older presets file was restored during the merge, unintentionally dropping the YAML range feature while adding the curvature/evidence modules from a parallel branch.
- **Impact:** `setting_ranges`/`knob_ranges` became dead config; compact-residual evidence and curvature model now part of every diff computation.

### 2026‑10‑12 · `47008533` — Swap FINS → DO via timers
- **What changed:** new `O:io/mvs/IoFaultPublisher` (59), `MvsIoConfig` (+39), `JnaMvsIoDevice` (+19), `FanOutCoordinator` (+20), `52-io.yaml` (+31: `io_reject`, `io_fault`, `timers`), `45-plc-fins.yaml`, `MVS-IO.md` (+41).
- **Why:** [doc: message “свап с финса на до через таймеры”] deterministic low-latency electrical outputs for reject/fault; FINS kept for ready/timeouts/telemetry.
- **Impact:** FINS map entries `reject_line_*` and `vision_fault` removed from the active map when DO enabled; PLC must wire DO3/DO4/DO2.

### 2026‑10‑12 · `006b242e` — “рабочая версия для линукса”
- **What changed:** `camera-worker/src/main.c` (+45: capture window, flush rules), `tests/test_capture_window.c`, `O:capture/DirectionCaptureWindow` (41), `MVS-IO.md` (+40), `RT-LINUX.md`, `network-camera-backup-20261012-125150/*` (NIC snapshots).
- **Why:** ⚠️ [GUESS/CONJECTURE] stop hardware-triggered frames outside the DI2 window from being used (token file shared by orchestrator and workers); back up camera network configuration before changes.
- **Impact:** every `wait_frame` now carries `capture_window_path/token`; DI2↓ cancels waits.

### 2026‑10‑09 · `3deef3bc`, `2396fc5a`, `df90df97`, `17281a7c`, `d7252ce4` — Linux kiosk fixes
- **What changed:** `deploy/kiosk/{session.sh,defect-detector.desktop}`, `front-end/electron/main.cjs` (+24: kiosk bounds, shortcut blocking), `inspectResult.ts`.
- **Why:** [doc: messages “фикс экрана/хромиума”] bare Xorg, no WM; stable fullscreen.
- **Impact:** UI only.

### 2026‑10‑08 · `e58636e1` — Linux version; IoInputMonitor + LightServer merged into the orchestrator
- **What changed:** `RT-LINUX.md` (+83), `camera-worker/CMakeLists.txt` (+17) and `main.c` (±63: MVS Linux paths), config blocks (`01-core` −71, `50-lighting`, `51-light-hardware`), `MvsComPortEnumerator.cs` (+9), Java: `O:io/mvs/*`, `O:lighting/nativeio/*`, `LightHttpController` (in-process dispatch).
- **Why:** [doc] one SDK session for DI/DO/timers; drop .NET from Linux; fewer hops on the reject path.
- **Impact:** LightServer.v3 / IoInputMonitor no longer started; same HTTP paths served by the orchestrator; see [legacy-hardware](./legacy-hardware/architecture_why.md).

## 5. Phase 5 — Positioning, perspective, settings model (2026‑08‑21 → 2026‑10‑05)

### 2026‑10‑05 · `b9a23553`, `4ee2291c` — Configurable ranges for knobs (later lost)
- **What changed:** `analysis_settings_presets.py` (+88/+54: `_configured_ranges`, `_knob_internal`, `_anchors`, `_interpolate`), `20-detectors.yaml` (+41/+2: `python_detector.setting_ranges`, `knob_ranges`).
- **Why:** [doc: YAML comments] retune 0/50/100 % anchors per installation without code.
- **Impact:** superseded by `032455c0` (code removed, YAML kept).

### 2026‑10‑02 · `6a99dd74`, `436e74d6` — Far-edge boost and perspective line
- **What changed:** `ReferenceViewSlot.perspective_line_norm` through UI/WS/bundle parser/registry/python (`ShmFrameRequest.perspective_line_norm`, `/roi-polygon` accepts line), `inspection_geometry.py` (+336: `PerspectiveLine`, `perspective_far_weights`), settings `far_edge_max_gain`, `far_edge_edge_suppress_factor` + `far_edge_boost` strength, UI panel.
- **Why:** [doc] oblique cameras: far side of ROI has weaker contrast; engineer draws near→far direction.
- **Impact:** bundle schema gained an optional field; detector gain depends on the line; 6th strength knob in UI.

### 2026‑09‑30 · `97c88415`, `9ba4efc5` — Stabilise latency; centralise positioning in Python
- **What changed:** `dependencies.py` (+9 executor), `inspection_service.py` (+63/+146), `InspectPythonExecutor` (+15/+39), `BinaryInspectHeaders` (−31), `main.cjs` (+16).
- **Why:** [doc] move alignment into Python so it runs at full resolution before `inspect_scale`; stop Java positioning hop.
- **Impact:** `java_positioning.enabled:false`; geometry normally no longer `pose_locked`.

### 2026‑09‑22 · `5b4b7f45`, `3df6b127`, `5b25b4ee` — “stable good commit”; positioning trimmed
- **What changed:** `inspection_service.py` (+146 shadows/speed), `LabelSeamBandSegmenter`, `OpenCvGeometryAnalysisService` (+128), `BucketPositioningService` (−310), `IoCaptureGate`, `InspectionStageTimingLogger`.
- **Why:** [doc: message “фикс теней, скорости инспекции, комит стабильный и хороший, не херить его”] marked by authors as the baseline; shadow/glare guard (`_suppress_smooth_illumination`).
- **Impact:** reference point for regressions (`git show 5b4b7f45`).

### 2026‑09‑18 · `ed237269`, `df9c077a` — Defect analysis and heat-map fixes; time optimisation
- **What changed:** heat-map built/exported in the primary `inspect-shm` pass without risking the verdict; unmatched defects kept after learned normals/FP; position tolerance in pixels; Python timings passed to Java telemetry; regression tests (commit body bullets).
- **Why:** [doc: commit body].
- **Impact:** `py_*_ms` in responses; `ShmVisualsResponse` behaviour (visual failures never fail verdict on `/inspect-shm`).

### 2026‑09‑13/14 · `93a6a292`, `4da3ec5a` — Constant reject signal per line (FINS)
- **What changed:** `PlcFinsPublisher.publishBucket` (+11/+21), `FanOutCoordinator` (+36: early plastic reject).
- **Why:** [doc: “постоянный импульс при браке по линии”] PLC needs a held reject until PASS for the plastic-handle mode; precursor of hold-mode DO.
- **Impact:** FINS-mode only.

### 2026‑09‑10 · `ed636eaa` — Gate rim skew / concentricity to the joint camera
- **What changed:** `LabelRimSkewAnalyzer` (+7), `OpenCvGeometryAnalysisService` (+63: `evaluateRimSkewPass`, `siblingView`), `20-detectors.yaml` (+2), `RimSkewPolicyTest` (75).
- **Why:** [doc: commit title + code comments] angled siblings show an optical wedge; yellow stripes needn't be on every bucket.
- **Impact:** fewer false rejects; siblings report visibility only.

### 2026‑09‑08 · `ab3c234b`, `72712964`, `65d8b0f3`, logs — sibling-process killer; reject fix
- **What changed:** `IoSiblingProcessKiller.cs`, `ServiceHealthGate` (+23), `CriticalServiceWatchdog` (±16), `FanOutCoordinator`, `CriticalServiceInspectionPause`.
- **Why:** [doc] second IoInputMonitor instances held the COM port and duplicated signals; health gate semantics tightened.
- **Impact:** later superseded by the JVM merge.

### 2026‑09‑07 · `2a678aad`, `27af33eb` — UI frame fetched once per inspection after positioning; 5 min → 2 min
- **What changed:** `LivePreviewPublisher` (+47), `UiArtifactsSidecar` (+67), publish-gate test.
- **Why:** [doc] reduce UI load/latency; shorter timeout.
- **Impact:** previews no longer mirror every capture.

### 2026‑09‑01‥03 · `4e13558c`, `8cf621f7`, `a24ed03c`, `859b86a7`, `f168682f` — ROI sync per inspection; autostart; file-logging flag
- **What changed:** `inspection_routes._sync_request_roi` + `ShmFrameRequest.roi_polygon_norm`, `AnalisSurfaceHttpBinaryRpcSupervisor` (+2), Windows autostart scripts, `python_detector.file_logging`.
- **Why:** [doc: commit title “Keep camera ROI synchronized for every inspection”] a Python restart must never fall back to full-frame analysis.
- **Impact:** every inspect request carries ROI; logging can be disabled for long runs.

### 2026‑08‑28 · `ed6cb71c`, `9a1361e4`, `92ab0d84`, `39b601f0`, `f200b192`, `eba791b8`, `771a0344` — perspective compensation, reference naming/editing, crash fix, repo hygiene
- **What changed:** perspective compensation in detector, reference names/save/edit in UI, build artefacts and logs untracked (`.gitignore`).
- **Why:** [doc: messages].
- **Impact:** repo no longer carries bin/obj/logs (older ones remain in history).

### 2026‑08‑21‥27 · `67f165c7`, `94efbfe1`, `5dc1e6a0`, `0458f5b8`, `b6e16890`, `7405bccb`, `7e0c30a9`, `c607df46`, `c2c7e6b2`, `573835b1` — heat-map v1/v2, frame pin, python parameter-test service
- **What changed:** heat-map pipeline (gray‑u8 + UI colouring), pinned analysis frame (`TestFramePinStore`, `/inspect-test-frame`), separate folder for analysis frame, proxying of Python endpoints through the orchestrator.
- **Why:** [doc] tune parameters on one pinned frame; “analyse this exact frame”.
- **Impact:** TEST session state; `UiTestAnalyzeService`.

## 4. Phase 4 — Learning, settings, seam analysis (2026‑08‑03 → 2026‑08‑20)

### 2026‑08‑13/14 · `7d39b0db`, `972efdc0`, `8be4f1ca` — Learned acceptable normals; history stores PASS and REJECT
- **What changed:** `learned_normals.py`, `learned_normal_routes.py`, tests; `LEARNED_NORMALS(_API).md`.
- **Why:** [doc] operator labels false positives post-factum; effect only for following inspections.
- **Impact:** new on-disk state (`accepted_normals/`, `learning_reviews/`), `excluded_normal_zones` in responses/UI.

### 2026‑08‑10/11 · `15ccb181`, `0ec22782`, `165b880b`, `10bc8ca2`, `c7033f65` — Simple/Pro settings, geometry settings, in-frame tuning
- **What changed:** `analysis_settings_preset_routes.py`, UI `AnalysisSettingsPanel` (+195), `GeometryRuntimeConfig/Store`, camera-worker `TOK_POOL` raised (config.json grew).
- **Why:** [doc] operators vs engineers; tune on the current frame; JSON token overflow fix.
- **Impact:** `/api/client/geometry-runtime`, `config/data/geometry_runtime_settings.json`.

### 2026‑08‑03 · `768f650f` — Release: seam/geometry, flashes by DI2, COM in bank On/Off
- **What changed:** `LabelSeamBandSegmenter` (new 340), `LabelSeamAnalyzer` (±382), `OpenCvGeometryAnalysisService` (+64), `JointPassPolicyTest`; UI `orientedRectRoi.ts`/`RoiContourEditor` (+316); LightServer `CameraFlashBankController` (+89), `50-lighting.yaml`; `LightTriggerClient` interval flash.
- **Why:** [doc: commit body] stronger seam and ROI analysis, `interval_flash` by DI2 pulse, bank turns off Ethernet + COM.
- **Impact:** joint ROI becomes an oriented rectangle; lights On for the whole DI2 window.

## 3. Phase 3 — Hardware: PLC, IO, lights, FP-zones (2026‑06‑19 → 2026‑07‑29)

### 2026‑07‑29 · `c3a55c49` — new PLC timeout
- **What changed:** `plk/register-map.yaml` (+9).
- **Why/Impact:** [doc] additional DM timeout (likely D4405 `handle_material_mode`) readable/writable via `/api/client/plc/timeouts`.

### 2026‑07‑24 · `d5935541`, `45a008c6`, `0042e6bb` — FP-zones with mini-etalon, stats, rounded corners
- **What changed:** `inspection_service.py` (FP-zone add/recheck), UI `FpZoneEditor`.
- **Why:** [doc] known false-positive regions; compare crop to stored mini-etalon, only match is excluded.
- **Impact:** `fp_zones.json`, `fp_zone_crops/`, `fp_zones.generation`.

### 2026‑07‑21‥23 · `5bfe41ed`, `87d74f3e`, `5f7ab035`, `483d8649` — Discrete IO + FINS signals; capture dispersion fix; two light modes
- **What changed:** `IoInputMonitorRejectClient` (196 lines, orchestrator-side client for reject via the IO monitor DO), `IoLineDirectionHttpServer` (+282), `FanOutCoordinator` (+75/+49), `PlcRegisterMapLoader`/`PlcSignalDefinition`, `plk/*`, `LineSynchronizedCaptureCoordinator` (+11), UI `SettingList` light mode.
- **Why:** [doc] reject on DO instead of only FINS, UI-selected direction, remove capture spread between cameras (DO5→Line0).
- **Impact:** foundation of today's direction/DI2 logic; FINS map gets `direction`/`bucket_group_id`.

### 2026‑07‑14‥16 · `83bb9d19`, `f9c260dc`, `92f2824a`, `81ff1ac9`, `8dc6cc8e`, `8d60f820`, `e2e81e5e` — Java positioning service; memory optimisation
- **What changed:** new module [positioning](./positioning/overview.md); `ImlShmJanitor` (+79), `PipelineReferenceRegistry` (+89: durable `iml_ref_cam<N>`), `LineFramePinService`.
- **Why:** [doc] registered frames for geometry/Python; stop SHM growth.
- **Impact:** new pool (`positioning_pool_size`), new SHM names; later disabled.

### 2026‑07‑09/10 · `c616d43e`, `ddb9be32` — flash operation, relay guide
- **What changed:** `LightTriggerClient` (±54), test; relay guide doc.
- **Why/Impact:** [doc] stabilise flash On/Off from the orchestrator.

### 2026‑06‑24/25 · `459d9358`, `5aaba1c5`, `c1a57caa` — Omron FINS; DLLs into project
- **What changed:** `O:plc/*` (+`pom.xml`), `plk/register-map.yaml` (`vision_ready W0.04`, `vision_fault W0.05`, `reject_line_1 W0.06`, `reject_line_2 W0.07`), `WsOutboundMessenger` (+64 `server.plc_fins_traffic`), robot/HTTP-stub fan-out removed; MVS DLLs vendored (87 files).
- **Why:** [doc] talk to the PLC directly; reproducible Windows deployment.
- **Impact:** FINS becomes the verdict channel (later DO).

### 2026‑06‑19‥22 · `796dd09d`, `b9e98e66`, `5fcf0d07`, `b70506cf`, `6bedf947` — COM flashes, inspection ids/history, ROI save/edit, per-camera start/stop
- **What changed:** `PerCameraInspectionGate` (+80), `InspectionTriggerBus.injectSequence` (+22), `InspectionResumeBoundaryTest`.
- **Why:** [doc] stop/start one camera without restarting the cycle; rejoin on the open sequence.
- **Impact:** soft-stop semantics throughout the aggregator.

## 2. Phase 2 — 10 cameras, front-end, reference flow (2026‑06‑01 → 2026‑06‑18)

### 2026‑06‑15/17 · `d4752eff`, `771d2957`, `72e3e9a9`
- **What changed:** UI `MainOverview` (+425) result synchronisation; `AsyncInspectionCycleRunner` timeout after reference (±15).
- **Why:** [doc] consistent per-cycle results across cameras; timeout fix after setting a reference.

### 2026‑06‑12 · `2fa2673b` — stop inspection, UI for 10 cameras
- **What changed:** `MainOverview`, `ReferenceController`, `referenceBundle`.
- **Why/Impact:** [doc] UI scaled from 4 to 10 cameras (5+5 buckets).

### 2026‑06‑09/10 · `368b2b44`, `dcc213c6`, `e7545e9c` — analysis-settings + `camera{id}`; pinned reference frame
- **What changed:** `OrchestratorAnalysisSettingsHttpController` camera-scoped rewrite, `ConfiguredCameras` analysis profiles, `useReferenceFrames` freeze.
- **Why/Impact:** [doc] per-camera analysis profile (`bench-lanN`).

### 2026‑06‑03/04 · `e4876da2`, `2da39ebe`, `7afc6c41`, `51fee9aa` — first heat-map; reference fixes; 1 → 4 cameras

### 2026‑06‑01 · `4ecab6b9` — “java moment” (orchestrator core)
- **What changed:** `IntegrationBootstrap`, `ConfiguredCameras`, `HttpFrontController`, `CameraPreviewHttpController`, `LightTriggerClient` (+98), config JSON/YAML (~17 k lines incl. generated).
- **Why:** [doc: message] Java becomes the brain.
- **Impact:** current architecture's backbone.

## 1. Phase 1 — Foundation (2026‑05‑14 → 2026‑05‑29)

- `02bb7301`/`dcfc6123` (05‑28/29) `client.reference_bundle` body redefined — reference comes from UI.
- `2a62b05d` (05‑26) reference caching in Python.
- `5b05aa4b` (05‑21) **LightServer.v3 integration** (old `LightServer`/`LightServerv.v2` deleted); `2fd3e9e8` flashes fire simultaneously (`MvLeFlashSync` 779 lines).
- `9ad1bded` (05‑18) orchestrator ↔ LightServer link points; **analisSurface switched to HTTP** (`orchestrator_stdio_runner.py`, `runner.py`, `run_stdio_worker.py` removed; `AnalisSurfaceHttpBinaryRpcSupervisor` +152): [doc] simpler FastAPI deployment; the Java IMLB abstraction is kept by emulation.
- `e5ef57f0` (05‑14) `kopcheniservice` → `analisSurface` (first commit in history).

### Missing-history notes
- No commit describes the initial C camera-worker, geometry core or the first Java skeleton in detail (history starts with renames and large merges) → those parts are documented from code; their rationale is tagged GUESS where not stated in comments.
- Accidentally committed artefacts: `DefectDetector.exe` (`86ab5bc7`), `.idea/` shelves, historic `LightServer.v3/logs/*.log`, `bin/obj` of LightServer, `network-camera-backup-*`; ignore for architecture.
