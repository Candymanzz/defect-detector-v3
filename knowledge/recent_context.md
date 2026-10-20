# Recent decisions and operating context — 2026-10-20

Back to [global architecture](./global_architecture.md) · [performance evidence](./performance/2026-10-16-log-review.md) · [optimisation plan](./analis-surface/optimization_plan.md) · [deployment commands](./deploy-ops/overview.md#7-current-update-and-launch-commands)

For a new developer/ChatGPT conversation start with [README](./README.md) and [PROJECT_CONTEXT](./PROJECT_CONTEXT.md). Current owner context as of 2026-10-20: **six cores, RT environment, 500 ms trigger-to-verdict requirement**.

Implementation notes were reviewed against `testlinux` source `c9944af8`; the shared-context revision uses documentation baseline `9d8a4b66`. These documentation revisions do not establish the deployed version or implement a C++ detector.

Evidence labels: **[code]** verified implementation; **[logs: 2026-10-16]** observations from the exported machine journal; **[proposal]** discussed work, not implemented or benchmarked. Historical observations are not a claim about the machine's current configuration.

## 0. Camera acquisition inside the JVM

**Status: implemented in source, 2026-10-20; deployment/hardware validation pending.** Cameras now run through JNI inside the orchestrator, reusing C capture/settings/streaming and the existing SHM ring. MVS lifetime is shared with IO/lights, and cancellation precedes native close/reopen. The build script produces `libcamera_jni.so`; old worker IPC settings no longer select the working transport. See [CAMERA-JNI](../orchestrator-java/CAMERA-JNI.md). This change alone does not establish a new latency measurement.

## 1. Small bucket movements: translation only

**Status: implemented [code].** `analisSurface/backend/app/services/inspection_positioning.py`, `FramePositioner.position`; invoked from `InspectionService._inspect_frame_unlocked` in `inspection_service.py`.

- Compensate small left/right and up/down movements against the reference. Label rotation, rotation fitting and scale estimation are outside the current requested scope.
- Phase correlation uses a cached reference, a working dimension capped at 640 and a default displacement-magnitude limit of 32 px. The estimate must pass confidence and overlap-improvement checks.
- No ORB/RANSAC/ECC in this positioning path, no reference-pixel replacement, no exclusion strips or suppression masks.
- An unreliable estimate leaves the current image unchanged. It does **not** by itself produce a technical positioning FAIL, but the subsequent detector can still return БРАК for image differences. This is not a guarantee of zero positioning-related false detections.
- Python positioning defaults on (`PY_POSITIONING=1`). The checked-in template disables the separate Java positioning service (`config.exemple/blocks/20-detectors.yaml`, `java_positioning.enabled: false`).

See [positioning overview](./positioning/overview.md) and [Python pipeline](./analis-surface/overview.md#4-pipeline-_inspect_frame_unlocked).

## 2. Verdict/output before optional UI artifacts

**Status: implemented [code], conditional on configuration.** The checked-in detector template enables `defer_heatmap: true` and `defer_learning_review: true`.

The critical path produces the verdict without rendering the UI heatmap. `FanOutCoordinator.publishBuckets` delivers the output before deferred UI work; Python exposes `/render-heatmap` for the frozen result. `InspectionWorkload` gives queued/running inspections priority over auxiliary history/heatmap tasks.

- Deferral does not remove learned-normal matching or FP rechecks needed to compute the verdict. Those remain synchronous inspection work.
- `py_heatmap_ms` near zero measures the critical-path work, **not** total deferred rendering or browser display latency.
- Auxiliary work already running cannot be preempted. A bounded/deferred queue is not a hard real-time guarantee.
- Reference preparation is cached by `ReferencePreparationCache`; the positioner also caches prepared reference data. Caches already exist and should not be described as an unimplemented optimisation.

Evidence: `inspection_workload.py`, `reference_preparation.py`, `deferred_heatmaps.py`, `api/inspection_routes.py`, `O:fanout/FanOutCoordinator.java`, `O:pipeline/session/AsyncInspectionCycleRunner.java`.

## 3. Replace reference frames while keeping ROI

**Status: implemented [code], commit `43ec2a9a` (“save roi”), retained by merge `3fcb91c3`.** This is a dedicated refresh operation, not merely persistence of ROI edits.

In the reference dialog, select a group that has a reference and press **«Новый эталон с прежним ROI»**. `ReferenceController.ts::handleRefreshFramesKeepingRoi`:

1. Requires a current reference, valid ROI for every target camera and an open WebSocket.
2. Captures fresh frames for the selected group; retains the existing normalised ROI, joint ROI, perspective line and FP geometry.
3. Waits for the updated React frame/image state, then automatically submits the new bundle. It uses the existing non-full-replacement learned-case inheritance policy.
4. Does not submit a partial capture. Capture failure restores the current reference view; duplicate/competing actions are guarded during capture.
5. Treats the reference as confirmed only after the server ACK succeeds. A rejected submission is not reported as confirmed.

The separate **«Новый эталон с новым контуром»** operation starts a new contour workflow. Keeping ROI preserves its normalised coordinates; it does not automatically redraw the contour around a differently placed object.

Evidence: `front-end/src/components/ReferenceSetup/{ReferenceController.ts,ReferenceSetup.tsx,useReferenceRoi.ts,useReferenceFrames.ts,referenceBundle.ts}` and `shared/referenceImages.ts`.

Historical local validation: 13 controller/frame/bundle scenarios passed using mocked cameras/API, including partial capture, connection loss and rejected ACK. This was not a full browser, production-camera or hardware deployment test. The button requires the rebuilt frontend; restarting Python alone cannot add it to an old kiosk build.

## 4. Timing target and remaining bottleneck

The current owner requirement is **500 ms from trigger to final verdict** (2026-10-20), superseding the earlier 600 ms target. Every measurement must state its start/end events. Time from image availability is not time from DI2↑ or from the camera's physical trigger. See [current latency requirement](./performance/latency_requirements.md).

**[logs: 2026-10-16]** The latest two reviewed exports measured 268.113 ms and 448.900 ms mean last-frame-to-both-outputs time. The second contained five 837–1341 ms outliers during the learning-update period. See the [standalone log review](./performance/2026-10-16-log-review.md) for counts, boundaries and limits.

The observed heavy interval is learned-normal application plus FP rechecks, followed by Java-side waiting for the busy Python server. Positioning stayed near 28–29 ms per camera. Full knowledge-base reload after a generation change is a verified code path, but the logs do not separately measure its contribution to each spike.

**[proposal]** Priorities: finer timings; prepare/publish consistent learning snapshots outside the critical path; optimise candidate matching and repeated image work; only then prototype selected C++ kernels. No C++ implementation or measured C++ speedup exists in this update.

## 5. Configuration versus observed deployment

- The checked-in template requests six Python servers and `analis_surface.inspect_workers: 3`. `AnalisSurfaceLauncher` passes the worker count through `ANALIS_INSPECT_WORKERS`.
- `api/dependencies.py` defaults to **10** inspection threads when that environment variable is absent, clamped to 1–32. “Always three threads” is incorrect. OpenCV's internal thread count is explicitly one.
- The reviewed logs show six Python processes and scales `0.2` for zero-based camera IDs 2/7, `0.7` for the other cameras. The checked-in detector template instead has `0.5` for 2/7 and global `0.75`.
- Production `config/` is not versioned. The owner now confirms **six cores and RT**; actual CPU affinity, SMT and per-thread policy still require host checks. The older default `2-5,8-11` is not proof of six cores allocated to the app. See [production environment](./deploy-ops/production_environment.md).

## 6. Operator commands and portable notes

`rebuild-and-run.sh` now builds/installs only. Backend startup and kiosk restart are separate operations; use the [current command runbook](./deploy-ops/overview.md#7-current-update-and-launch-commands).

The performance figures and limitations are included inside `knowledge/`, not only in the ignored `.verification/` directory. Internal Markdown links work when this folder is copied to an Obsidian vault. Links to repository-root documentation still require the corresponding files; source-code paths are navigation hints, not bundled source code. Keep these notes updated alongside future code/config changes.

## 7. Customer geometry requirements — recorded, not implemented

On 2026-10-20 the customer requirement was added for flexible label-seam tolerance in millimetres (2 mm as an example), independently adjustable horizontal/vertical label-placement limits, and geometry rejection when a required valid measurement is outside its limit. Showing the actual displacement and the applied tolerance is desired. The seam metric, numerical X/Y limits and physical reference still need agreement. Whole-bucket jitter compensation must not erase actual label-to-bucket displacement. See [geometry requirements and current gaps](./geometry/customer_requirements.md); recording this request changed no application code or settings.
