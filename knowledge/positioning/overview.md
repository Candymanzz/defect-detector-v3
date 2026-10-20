# positioning (`java-positioning-service/`) — retained, template-disabled

Back to [global_architecture.md](../global_architecture.md) · [API](./api_contracts.md) · [Why](./architecture_why.md) · `P:` = `java-positioning-service/src/main/java/com/example/iml/positioning/`

## 1. Purpose & status
Normalises the pose of the bucket against the reference so that geometry and the Python detector see a registered frame: ORB feature matching + RANSAC homography on a downscaled ROI, optional coarse/ECC/polish stages (**all disabled by constants**), then warps the full frame and writes it into a new SHM `iml_pos_cam_<id>` (BGR8). `overallPass` = alignment found and frame written; shift magnitude does **not** reject (“Большая разбежка положений ожидаема” — README).
**Status:** `config.exemple/blocks/20-detectors.yaml` → `java_positioning.enabled: false`; comment: *“Позиционирование выполняется внутри Python analisSurface (PY_POSITIONING=1). Java-сервис позиционирования … отключены.”* The Python equivalent is `analisSurface/backend/app/services/inspection_positioning.py` (`FramePositioner`, phase correlation, translation only) — see [analis-surface](../analis-surface/overview.md#pipeline-_inspect_frame_unlocked).
The module, its pool (`positioning_pool_size 10`, `positioning_command_*`) and the orchestrator stage `InspectPositioningExecutor` are kept so the stage can be re-enabled by config.

**Verified 2026-10-20, `testlinux` / `e466367f`:** `DefaultIntegrationServicePoolFactory.createPositioningPool` returns an empty pool when `enabled: false`; this is a configuration decision, not removed source. An omitted flag defaults to **true** in the factory, so old/machine-specific configs must be checked separately. Python translation happens inside surface inspection **after** the geometry stage, not as a shared replacement for Java's pre-geometry transform. [Current service boundaries](../current_branch_architecture.md).

## 2. Code map
| File | Role |
|---|---|
| `runner/PositioningRunnerMain` (23), `runner/StdioBinaryPositioningLoop` (136) | entry + IMLB dispatch: `health`, `position_shm` (`:71-135`); `ensureOutputName` defaults `output_shm_name="iml_pos_cam_<camera_id>"` |
| `protocol/BinaryProtocol` | same IMLB codec as geometry |
| `shm/ShmMatReader` (124), `shm/ReferenceShmMatCache` (76), `shm/ShmMatWriter` (89) | read current/reference; write aligned BGR frame to output SHM |
| `wire/PositioningHeaderMapper` (217) | request ⇄ header; defaults `pixelsToMm 0.02`, `maxShiftMm 0.5`, `maxRotationDeg 1.0`, `write_aligned true`; tuning `alignFailAbsdiff`, `alignFailAbsdiffHard`, `alignFailResidualPx`, `eccSkipNcc`, `eccSkipAbsdiff`, `eccSkipResidualPx` (camel or snake) |
| `analysis/BucketPositioningService.java` (1830) | the algorithm; constants `:50-101` |
| `analysis/RoiPolygonMask` (118), `dto/*` | polygon mask, DTOs |

## 3. Algorithm (`BucketPositioningService.position:140+`)
Constants (`:56-101`): `ENABLE_COARSE=false`, `ENABLE_RESIDUAL_POLISH=false`, `ENABLE_ECC=false`, `ENABLE_POST_ECC_POLISH=false`; `MAX_ORB_DIM 384`, `ORB_FEATURES 900`, `ORB_PYRAMID_LEVELS 4`, `RANSAC_REPROJ_THRESHOLD 5.0`, `RANSAC_MAX_ITERS 200`, `MIN_MATCHES 10`, `ORB_MAX_ANGLE_DEG 0.35`, `ORB_MIN_REF_KEYPOINTS 48`, quality grid `QUALITY_MAX_DIM 320`, fail thresholds `ALIGN_FAIL_RESIDUAL_PX 10`, `ALIGN_FAIL_ABSDiff 10`, `…_HARD 16`.
Flow with current flags: `measureQuality(raw)` on ROI expanded by 5 % (mean abs diff, NCC, residual shift via phase correlation) → ORB at ≤384 px → homography (angle clipped to ≤0.35°, i.e. effectively translation + tiny rotation) → quality re-measure; reject ORB if quality did not improve (`orb_rejected_quality`) → warp full frame → write output SHM → response with diagnostics (`diag_*`).
Timings promoted to top level: `stage_ms_orb, _warp, _ecc, _write, _total` (+ coarse/polish when enabled).

## 4. Orchestrator side (`O:pipeline/stages/InspectPositioningExecutor.java`)
- `enabled = cfg.enabled (default: pool non-empty)`; `failOnReject = cfg.fail_on_reject (default true)` — but YAML sets `fail_on_reject:false`, `max_shift_mm/max_rotation_deg 9999` (“мягкие метрики, не гейтят PASS”).
- On success (`ok && alignedWritten`): rewrites the **capture header** to point at the aligned buffer (`shm_name = iml_pos_cam_N`, `original_shm_name`, `positioning_aligned=true`, `ui_preview_shm_name`), releases the line pin early, copies all `positioning_*` diagnostics (`positioning_shift_x_mm`, `…_rotation_deg`, `…_homography_ref_to_cur`, `…_final_absdiff`, `…_final_ncc`, `…_orb_inliers`, …).
- On failure with `failOnReject`: sets `positioning_hard_fail=true` ⇒ geometry returns synthetic FAIL (`positioningRejectMessage`) and Python returns `status FAIL "positioning reject"`; otherwise **falls back to the raw frame** and does *not* mark it aligned (so Python/geometry won't assume a locked pose) — comment `:~228`.
- Geometry then receives `pose_locked` and `InspectGeometryExecutor.applyPoseQcFromPositioning` recomputes alignment QC from `positioning_shift/rotation` vs `java_geometry.max_shift_mm`.

## 5. Build/test
`mvn -f java-positioning-service/pom.xml -DskipTests package`; no unit tests in module (audit 2026‑10‑08). README: `java-positioning-service/README.md`.
