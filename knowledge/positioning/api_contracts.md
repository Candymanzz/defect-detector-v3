# positioning — API contracts

Back to [overview](./overview.md) · Transport: IMLB over stdio ([framing](../camera-worker/api_contracts.md#imlb-framing)); dispatcher `P:runner/StdioBinaryPositioningLoop.java:71-135`.

| `op` | Purpose |
|---|---|
| `health` | `{status:"ok", service:"java-positioning-service"}` |
| `position_shm` | align current→reference, write aligned frame |
Unknown op ⇒ ERROR `{error:"unknown op", op}`; exceptions ⇒ ERROR with `op`.

## `position_shm` request (built by `BinaryInspectHeaders.positioningHeader:115-166`)
SHM descriptors of current frame (`shm_name, shm_offset, width, height, stride`) and reference (`reference_shm_name, reference_shm_offset, reference_width, reference_height, reference_stride`); `camera_id`, `frame_id`; `output_shm_name` (default `iml_pos_cam_<camera_id>`); `write_aligned` (default true); ROI: `mainRoi`, `mainRoiPolygonNorm`; `pixelsToMm`, `maxShiftMm`, `maxRotationDeg`; optional per-profile tuning overrides (`applyPositioningProfileOverrides:220` → `alignFailAbsdiff`, `alignFailAbsdiffHard`, `alignFailResidualPx`, `eccSkipNcc`, `eccSkipAbsdiff`, `eccSkipResidualPx`).

## Response header (`PositioningHeaderMapper.toResponseHeader:48-99`)
`status`, `shiftXmm`, `shiftYmm`, `rotationDeg`, `homographyRefToCurrent`, `alignmentPass`, `overallPass`, `alignedWritten`, `output_shm_name`/`shm_name`, `shm_offset:0`, `width`, `height`, `stride`, `stage_ms_{orb,warp,ecc,write,total}` (+ coarse/polish), flags `coarse_used, coarse_rejected, coarse_residual_fallback, orb_applied, orb_fullframe_fallback, orb_rejected_quality, orb_failed, residual_polish, ecc_skipped, ecc_applied, ecc_rejected, post_ecc_residual_polish`, `diagnostics{…}` and every key again as `diag_<key>` (e.g. `diag_final_mean_absdiff`, `diag_final_ncc`, `diag_final_residual_dx/dy`, `diag_orb_inliers`, `diag_ecc_cc`), plus `camera_id`, `frame_id`.
Failure value for no alignment: shift/rotation 9999 (`FALLBACK_FAIL_MM/ROTATION_DEG`).

## Config (orchestrator, `java_positioning`)
`enabled:false`, `fail_on_reject:false`, `write_aligned:true`, `pixels_to_mm:0.02`, `max_shift_mm:9999`, `max_rotation_deg:9999`, optional `profiles.<analysis_profile>` tuning; pool: `integration.positioning_pool_size`, `positioning_command_{windows,linux}` (`java -jar …/java-positioning-service-0.1.0-SNAPSHOT.jar`).
