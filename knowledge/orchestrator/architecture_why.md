# Orchestrator — architecture "WHY"

Back to [overview](./overview.md) · [api](./api_contracts.md) · [global_changelog](../global_changelog.md)
Evidence: **[doc]** repo docs/comments/commit message · **[code]** verified in code · `⚠️ [GUESS/CONJECTURE]` inferred.

## 1. Process-per-concern joined by IMLB + shared memory
- Cameras (C), geometry (Java/OpenCV), surface analysis (Python) are separate OS processes; the JVM only moves *descriptors* of frames (`shm_name, offset, stride`). **[code]** `ShmFrameRequest`, `inspect_shm`; **[doc]** `ShmMatReader` comment, commit `8dc6cc8e` “оптимизация памяти”.
  Trade-off: no per-request image copy (3.7 MB × 10 per cycle) but readers depend on file-backed SHM semantics and the ring never being overwritten while read → that is why pins exist (§3).
- ⚠️ [GUESS/CONJECTURE]: isolation of native crashes (MVS SDK, OpenCV) and ability to pin processes to isolated CPUs on the RT kernel (commit `0da1c38e` “6 процессов и шесть ядер”).
- The binary protocol (IMLB) is hand-rolled and duplicated in three languages (`BinaryProtocol.java` ×3, `main.c`) instead of gRPC/JSON-RPC: **[code]** header JSON + opaque payload keeps frame transport out-of-band. ⚠️ [GUESS/CONJECTURE]: chosen from the first “java moment” commit (`4ecab6b9`, 2026‑05‑29) to avoid native deps in C and to share a format with Python; analisSurface was later converted to HTTP (commit `9ad1bded`, 2026‑05‑18: removed `orchestrator_stdio_runner.py`) while the Java side kept the IMLB abstraction by emulating it (`AnalisSurfaceHttpBinaryRpcSupervisor`) so executors did not change.

## 2. Merging LightServer.v3 and IoInputMonitor into the JVM
- **[doc]** commit `e58636e1` (2026‑10‑08), `MVS-IO.md` first line “Отдельный IO-процесс не используется”, `LINUX-PIPELINE-AUDIT.md`: the MV‑VC controller exposes **one SDK handle**; DI reads and timer/output commands must be serialised on it; separate processes would contend for the serial port.
- **[code]** `MvsIoAccess` (read/write lock) lets a DO batch interleave between DI samples: `readInputs` re-acquires the read lock per DI (`JnaMvsIoDevice.java:144-155`, comment “Yield between DI samples so a pending DO batch does not wait for the entire scan”).
- Removes .NET from the Linux target and an HTTP hop on the critical trigger→reject path.
- Risk: the JVM now owns the safety-critical IO; mitigated by `StackSupervisorMain`, `LightsShutdown` JVM hook, DO idle levels on close (`IoRejectPublisher.close`).

## 3. Hardware trigger chain and frame retrieval
- Chain `In3 → Timer5 → DO5 → Line0` is configured **inside MVS**; Java never writes Timer5/Out5 (`protected_outputs:[5]`, `MvsTimerCommand` throws on Timer5/Out5). **[doc]** `52-io.yaml` header comments. Why: one electrical edge exposes all ten cameras simultaneously; software `trigger_only` to ten workers over IPC has ≥10s of ms jitter. Evidence: commit `5f7ab035` “фикс разброса съёмки” (2026‑07‑23) and `hardware_line_trigger` option.
- `wait_frame` retries = **1** in HW mode (`LineSynchronizedCaptureCoordinator.WAIT_FRAME_MAX_ATTEMPTS`) because a retry flushes the camera buffer and could swallow the only frame **[code comment]**.
- Workers receive a `capture_window` token file so frames produced by Line0 outside the DI2 window (noise, other products) are discarded and `wait_frame` is cancelled when DI2 falls (`DirectionCaptureWindow`, camera-worker `capture_window_open` `W:main.c:1942-1951`). ⚠️ [GUESS/CONJECTURE]: added in `006b242e` (2026‑10‑12) after “random frames from next impulse/noise” observed on the line (see comment in `W:main.c:1119-1124`).
- **Pins**: `LineFramePinService` copies the ring slot into a per-cycle file so the worker can accept the next trigger while geometry/python still read the previous frame; janitor TTL 45 s for leaks **[doc]** `ImlShmJanitor` javadoc (“цикл ~4 с, запас на backlog”).
- DI is **polled** (`input_mode:poll`), not event driven: **[doc]** comment “supplied MV_IO event DLL exposes no timer command”; backend `mv_camera` gives one connection for DI *and* software timers. Cost ≈41 ms/scan (audit §4) → the only DI timing-critical path is DI3↑ followed by DI2 window logic, never pulse-width measurement.
- Startup safety: DI3 seeded to 0 (never synthesise a trigger on (re)connect), DI4 stored as `startup_edge_only` so a high level at boot is not a shutdown pulse (`MvsIoTransport.acquire:55-64`, `startup_edge_only_inputs:[4]`). **[doc]** YAML comment.
- No debounce/refractory by design (`MvsIoTransport` class comment): a debounce could hide a genuine second product; double-trigger protection is semantic (`captureFiredThisDi2Window`, per-DI2-window one capture).

## 4. Reject signalling
<a id="reject-signalling"></a>
History (see changelog): UDP relay to `IoInputMonitor` DO client (`5bfe41ed` 2026‑07‑21 `IoInputMonitorRejectClient`) → FINS bits `reject_line_1/2` W0.06/W0.07 (`459d9358`, 2026‑06‑24 first FINS; `87d74f3e` 2026‑07‑23 signal map) → “constant impulse on line reject” in FINS (`93a6a292`, `4da3ec5a`, 2026‑09‑13/14) → **DO via software timers** (`47008533` 2026‑10‑12) → **DO hold levels** (`io_reject.mode: hold`, 2026‑10‑14/15 with `a8f65721`).
- Why DO instead of FINS **[doc]**: `INSPECTION_OPTIMIZATION.md` “срок выдачи DO до спада DI2” — the verdict must reach the PLC *before DI2 falls*; FINS over UDP adds queueing (`pulse_ms`, 64‑job queue, 1 s response timeout). FINS stays for ready/fault telemetry, timeouts, manual bits.
- Why *hold* levels instead of timer pulses: ⚠️ [GUESS/CONJECTURE] fail-safe/level semantics — PASS keeps the output asserted (`LineSource=On`), reject drops it; a PLC that loses the cable or an orchestrator crash looks like “reject”. Evidence: YAML comment “hold: запуск/PASS → On, брак → Off”, `close()` writes idle levels (`IoRejectPublisher:207-209`), `resetIdle()` on `NO_REFERENCE/TEST` (`FanOutCoordinator.onSessionState:264-271`).
  Timers remain available (`mode: timer`) — the commit message says “свап с финса на до через таймеры”.
- One FIFO + one SDK critical section for *both* lines (`publishBatch`): **[doc]** `INSPECTION_OPTIMIZATION.md` — PLC sees line 1 and line 2 changes together and the SDK is not re-entered between them; hot-path enum read-backs and `LineSelector` restoration were removed **[doc]**.
- Fences against stale data: `heldSequences` (ignore older sequences), `earlyRejectedSequences` (later PASS for same sequence cannot release), `generation` (queued tasks invalid after reset). **[code]** `IoRejectPublisher.java:67,116-122,110`.
- `io_fault` uses an independent timer/output and refuses to share with reject outputs (`IoFaultPublisher.fromConfig:20-33`): fault must never be confused with line reject on the PLC side.
- Plastic handle (`D4405`): in FINS mode a failing camera rejects **all** lines at once and keeps the signal until PASS; in DO mode lines are independent. **[code]** `FanOutCoordinator.publishEarlyPlasticHandleReject:214-240`. ⚠️ [GUESS/CONJECTURE]: with a plastic handle the PLC needs a latched stop (handle shape affects both lines/downstream rejector), steel handle works with pulses.

## 5. Bucket aggregation, early reject, barrier
- Two lines share one trigger; the PLC needs a verdict per line, not per camera. `BucketInspectionAggregator` joins by `(triggerSequence, groupId)` and a **cross-group barrier** per sequence so both lines are written in one batch **[code]** (`SequenceBarrier`, `enqueueSyncedFanOut`). Missing group after `timeout_ms` ⇒ synthetic reject for that line (`onSequenceSyncTimeout:466-502`): safety over throughput.
- **Required cameras** = gate-enabled ones. Soft‑stop of a camera must not time out a bucket **[doc]** class javadoc; `reevaluateOpenBucketsAfterGateChange` re-checks open buckets after Stop/Start.
- **Early reject** (`18fcd180`, 2026‑10‑16): when any required camera fails (`!overallPass`, not `CAPTURE`/`NO_REFERENCE`) the line's DO drops immediately (`publishEarlyBucketReject`) instead of waiting for the slowest of five cameras. `bucketPass = !anyReject && !earlyRejectPublished` so a verdict already on the wire is final **[code comment]** (:352-354). Early delivery is an *optimisation*: exceptions are swallowed and the final batch retries (:235-238).
  Impact: the reject can precede UI/heat-map; PLC-first ordering is kept by `pendingUi`.
- Seam strict gate (`JointSeamPolicy`, `ed636eaa` + YAML): if the seam is visible on few siblings the joint camera alone decides stricter. **[doc]** YAML “Гейт только на joint-камере (где жёлтая полоса/стык)”.
- UI after PLC: **[doc]** comment “UI после FINS по seq (приоритет ПЛК)”.

## 6. Asynchronous cycle design
- A cycle is a `CompletableFuture` chain across four bounded executors (`stage-*`) so cameras overlap stages; `inspection_cycle_timeout_ms` bounds only up to python completion (decision/fan-out “stays outside timeout window”, `AsyncInspectionCycleRunner:243`) — a verdict that is already computed must be delivered.
- Cancellation is cooperative with polling (`CANCEL_POLL_INTERVAL_MS`) and checked between stages; `publishIfAllowed` re-checks `inspectionEnabled` right before publishing (client stop suppresses results; comment at :411-415 warns about a short-circuit pitfall that once skipped the publish runnable).
- `PerCameraInspectionGate` ensures ≤1 in-flight cycle per camera; a trigger arriving while the previous cycle runs hits `IN_FLIGHT` (logged as unexpected because capture is retained at DI3 prefire).
- Capture-only cycles (no reference) still run through the bucket aggregator with `CAPTURE` decisions so the UI keeps updating (`runCaptureOnly:287-409`).
- `inspection_id := triggerSequence` (`ProductionInspectionOrchestrator:208-211`): one id across all cameras and the bucket UI; otherwise Stop→Start rejoin created a second counter and the front-end showed “parallel” runs **[doc comment]**.

## 7. Python scheduling
- `python_server_pool_size 6` uvicorn processes on 6 physical cores, 3 threads each; orchestrator allows **one production inspection per unique server URL at a time** (`PythonWorkloadBalancer.reserve` + `awaitTurn` FIFO per `baseUrl`), choosing the server with the least *remaining expected work* (EWMA 0.75/0.25 of `py_total_ms` per `camera:productType`, initial 100 ms, cap 128 scopes). **[doc]** `INSPECTION_OPTIMIZATION.md`: three threads per process do not give three concurrent CPU inspections; duplicate HTTP clients to one URL used to create independent FIFOs on one CPU. `round_robin` retained for A/B.
- `python_request_parallelism 10` semaphore (`pythonSlots`) is a global cap; comment in `PipelineCameraRuntimeService:239-241`: limiting to #URLs serialised ten cameras in batches of two.
- `inspect_scale` is passed to Python (not applied in Java) so alignment happens on full-resolution frames, then frame+reference are resized together (commit `9ba4efc5` “centralize positioning”; `c9648be6` per-camera scale: cams 2 and 7 → 0.5). **[doc]** `BinaryInspectHeaders.java` comments and `InspectPythonExecutor:187-191`.

## 8. Reference handling
- `reference_source: client` — the UI (engineer) owns references/ROI/FP‑zones; orchestrator keeps *descriptors* only and **pins a durable copy** `iml_ref_cam<N>` (`pinDurableReference`) because ring slots and line pins are overwritten/cleaned (class comment :33-35).
- The reference is sent to **every unique python server** (`AnalisSurfacePoolSupport.uniqueServerClients`) — each uvicorn process has its own RAM state; the same applies to FP‑zones. ROI/perspective line travel in each inspect request so a python restart cannot silently drop to full frame (`roi_polygon_norm` comment in `schemas.py`).
- `capture_without_reference:true`: line keeps capturing before the first bundle so the UI/archive show live frames; verdict `CAPTURE` doesn't trigger rejects (`FanOutCoordinator.inspectionEnabled`).
- Bundle shape: 5 views per bucket, joint view index selects the camera with the seam ROI; `bucket_group_id = minCameraId/5` (`PipelineReferenceRegistry:182-188`) ⚠️ [GUESS/CONJECTURE]: the UI sets up one bucket (5 cameras) at a time, two bundles cover all ten cameras (class javadoc: “после двух пакетов эталонов (ведро 0–4 и 5–9)”).

## 9. Health, pause and recovery
- Everything critical (analisSurface, geometry, positioning, workers) maps to **one** boolean `healthyForVision()` (`ServiceHealthGate` is a set of unhealthy names) — deliberately simple: any critical failure ⇒ `vision_ready=0`, fault pulse, trigger dispatch blocked, in-flight cycles cancelled, queues cleared (`CriticalServiceInspectionPause`). On recovery the previous per-camera enabled snapshot is restored (not “enable all”).
- Watchdog recovery is serialized (`restarting` flag), has back-off per service (`restartNotBeforeMs`, `consecutiveRestartFailures`) and a periodic retry every 10 s (`RECOVERY_RETRY_MS`). Commit `72712964` (2026‑09‑08 “фикс отбраковывателя”) tuned these (health gate + watchdog).
- Frontend exit ⇒ orchestrator shutdown: **[doc]** `45-plc-fins.yaml` header “Выход процесса frontend → полный shutdown оркестратора → vision_ready принудительно 0”. ⚠️ [GUESS/CONJECTURE]: operators must not run the line blind; the kiosk loop restarts UI in 5 s and the supervisor restarts the stack.
- `StackSupervisorMain` “attach mode” exists so `run-supervised.sh` can monitor an orchestrator started elsewhere without spawning a duplicate; health blips in attach mode never kill the stack (comment at :215-216: MJPEG load can stall the JVM briefly).

## 10. Lighting design
- Interval flash: lights On for the DI2 window (product in position), not per capture (`hold_mode:true`): **[doc]** `LightTriggerClient.runCaptureWithLighting` comment — per-capture HTTP On/Off blocked `camera-flow` threads and destroyed the line-capture barrier (“камеры приходили в разное время”). **[doc]** chronology: flashes made simultaneous in LightServer.v3 (`2fd3e9e8`, 2026‑05‑21) → **`interval_flash` driven by the DI2 pulse and “bank гасит Ethernet+COM”** introduced by `768f650f` (2026‑08‑03; commit body: “interval_flash по импульсу DI2, bank гасит Ethernet+COM”; files `LightServer.v3/Controllers/CameraFlashBankController.cs`, `50-lighting.yaml`) → moved into the JVM (`NativeLightBank`, `e58636e1`, 2026‑10‑08).
- Dark start / dark reconnect (`armStartDark`, `NativeLightBank.connect`): never replay a stale On after reconnect. Brightness changes arriving during an active DI2 pulse are deferred and flushed at Off (`flushDeferredBrightness`) to avoid changing exposure mid-capture.
- One thread + bounded FIFO per light device (`NativeLightBank.Session`): MVS sessions are not thread-safe; bounded queue makes overload explicit (`Light queue full or stopped`).

## 11. Linux/RT specifics
- Build, run and measure are separate scripts on purpose (`rebuild-and-run.sh` builds only; `run-supervised.sh`/`run-rt.sh` run; `-r` only observes). **[doc]** `RUN.md`, `RT-LINUX.md`. Reason: not to perturb RT measurements and not to mix privileges (UI install uses restricted sudo helper, commit `4616cb7a`).
- Affinity via `taskset` on the *supervisor*, so every child inherits it; RT-diagnostics collector runs on housekeeping CPUs with `nice +10`.
- JVM flags `-Xmx1024m -XX:+ExitOnOutOfMemoryError` (`StackSupervisorMain.startOrchestrator`): fail fast and let the supervisor restart rather than run degraded.

## 12. Known design debts (observed, not decided)
- `IntegrationRuntimeContext` as mutable service locator; large controllers (`ClientApiHttpController` 1198, `UiArtifactsSidecar` 1264, `AnalisSurfaceHttpBinaryRpcSupervisor` 1336 lines).
- Legacy trigger variants (`direction_latch*`, UDP `io_input`) remain in code though disabled; `usesAutoDirection()` hard-wired to `false` (`IoInputTriggerTransport.java:569-571`).
- Two parallel reject transports (DO and FINS) with different semantics (plastic handle) — behaviour depends on config combination.
