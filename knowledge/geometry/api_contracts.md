# geometry — API contracts

Back to [overview](./overview.md) · `G:` = `java-geometry-service/src/main/java/com/example/iml/geometry/`

Transport: IMLB ([framing](../camera-worker/api_contracts.md#imlb-framing)) on the process stdin/stdout, sequential. Dispatcher: `G:runner/StdioBinaryGeometryLoop.java:44-112`. Unknown op ⇒ ERROR `{error:"unknown op", op}`; exceptions ⇒ ERROR `{error:<message>, op}`; wrong message type ⇒ ERROR `unexpected message type`.

## Operations
| `op` | Purpose | Response |
|---|---|---|
| `health` | liveness | `{status:"ok", service:"java-geometry-service"}` |
| `inspect_shm` | **production**: frames by SHM descriptors | geometry response header (below), payload empty |
| `inspect` | legacy: images in payload/base64 | same |
| `inspect_stub` | integration tests | `{camera_id, frame_id, status:"PASS", …}` |
| `inject_exit` / `inject_timeout_ms` / `inject_broken_response` | fault injection | |

## `inspect_shm` request header
Frame (current): `shm_name`, `shm_offset`, `width`, `height`, `stride`. Reference: `reference_shm_name`, `reference_shm_offset`, `reference_width`, `reference_height`, `reference_stride` (cached by `ReferenceShmMatCache.referenceKey` = camera_id + reference descriptor). If no `reference_shm_name` the current frame is used as reference (self-compare).
Context: `camera_id`, `frame_id`, `includeDebugImage` (bool), `pose_locked|poseLocked|already_aligned`.
ROIs: `mainRoi{x,y,width,height}`, `mainRoiPolygonNorm` (aliases `main_roi_polygon_norm`, `roi_polygon_norm`, `interest_polygon_norm`; list of `{x,y}` or `[x,y]` in 0..1, ≥3 points), `jointRoi`, `jointRoiPolygonNorm` (`joint_roi_polygon_norm`), `wrinklesRoi`.
Mode: `jointMode` (`off` | `full` | `visibility`; also `joint_mode`). Resolved by the orchestrator (`BinaryInspectHeaders.resolveJointMode:352-364`): no joint ROI ⇒ `off`; the camera whose id equals the bucket's `joint_camera_id` ⇒ `full`; every other camera of the bucket ⇒ `visibility`. **The joint ROI is one per bucket** (`joint_roi_norm`, taken from the joint view of the reference bundle and de‑normalised per frame, `resolveJointRoi:317-339`), so sibling cameras receive the *same* ROI but only report seam visibility (`InspectionRequest.jointVisibilityOnly()` ⇔ `jointMode=="visibility"`).
Limits: `pixelsToMm`, `maxShiftMm`, `maxRotationDeg`, `maxConcentricityMm`, `maxJointDefectMm`, `jointMinWidthMm`, `jointMaxWidthMm`, `maxJointParallelismDeg`, `maxJointTaperMm`, `maxJointRimSkewDeg`, `maxJointGapAsymmetryMm`, `maxWrinklesScore` (or `threshold`), `jointSeamSegmentationEnabled`, `jointSeamSegmentationSensitivity`/`joint_seam_segmentation_sensitivity`.
Defaults for absent keys: see [overview §4](./overview.md#4-request-defaults-when-a-key-is-absent--inspectionheadermapperjava20-60).

## Response header (`InspectionResponsePayloadBuilder`)
`status` (`PASS|FAIL`), `overallPass`, pose: `shiftXmm, shiftYmm, rotationDeg, deviationRadiusMm, homographyRefToCurrent` (3×3 row-major list), `alignmentPass`;
concentricity: `concentricityMm, concentricityPass`; joint: `jointCamera` (bool = joint ROI present and not visibility-only), `jointMode`, `jointPass`, `jointDefectMm, jointParallelismDeg, jointWidthMm, jointWidthTopMm, jointWidthBottomMm, jointTaperMm, jointVisibility`;
rim: `jointRimSkewDeg, jointGapLeftMm, jointGapRightMm, jointGapAsymmetryMm, rimSkewPass`; wrinkles: `wrinklesScore, wrinklesPass`;
echo of limits: `pixelsToMm, maxShiftMm, maxRotationDeg, maxConcentricityMm, maxJointGapAsymmetryMm, maxJointRimSkewDeg`;
diagnostics: `stage_ms_{prep,align,warp,joint,rim_skew,wrinkles,concentricity,debug,total}`, `diag_*`/`diagnostics` (`frame_w/h`, `roi_x/y/w/h`, `polygon_mask`, `pose_locked`, `align_skipped_pose_locked`, `has_joint_roi`, `joint_visibility_only`, `joint_ran`, `joint_found`, `debug_written`); optional debug PNG (base64) when `includeDebugImage`.

The orchestrator post-processes this header: `applyPoseQcFromPositioning` (`InspectGeometryExecutor.java:257-310`) when positioning aligned the frame; `GeometryUiPayload.fromGeomResponse` compacts it for the UI (`InspectionDecision.geometry`); `GeometrySnapshotCache` stores the last response per camera for `/api/geometry/camera/{id}/latest.json`.

## Failure semantics
- Alignment failure (<8 matches / no homography) ⇒ shift/rot = 9999 ⇒ `alignmentPass=false` ⇒ FAIL (unless `pose_locked`).
- Exceptions from OpenCV or SHM read ⇒ IMLB ERROR ⇒ orchestrator `command()` retries up to 3× with restart; persistent failure ⇒ health gate unhealthy.
