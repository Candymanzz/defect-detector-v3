# geometry (`java-geometry-service/`)

Back to [global_architecture.md](../global_architecture.md) · [API](./api_contracts.md) · [Why](./architecture_why.md) · `G:` = `java-geometry-service/src/main/java/com/example/iml/geometry/`

**Customer requirement, recorded 2026-10-20:** adjustable seam tolerance in mm (2 mm is an example), independent horizontal/vertical label-placement tolerances, geometry rejection when a required valid measurement exceeds its limit, and desired display of the actual deviations. See [requirements and implementation gaps](./customer_requirements.md). This is not a claim that the existing image-alignment shifts already measure label placement relative to the bucket.

## 1. Purpose
Classical-CV **geometry QC of the labelled bucket** per frame against the reference: pose shift/rotation (when it aligns itself), **concentricity** of cap/label circles, **label seam (joint)** width / parallelism / taper / visibility, **rim skew** and **gap asymmetry** against the yellow rim, and **wrinkles** (abs-diff score). Returns pass flags used by `DefaultInspectionDecisionAggregator`.
Runs as a JVM per slot (`geometry_pool_size 10`) reading frames from SHM; ~3.7 kLOC; fat JAR `target/java-geometry-service-0.1.0-SNAPSHOT.jar` (Maven, Java 17, OpenCV Java bindings loaded by `opencv/OpenCvNativeLoader`, Jackson, Log4j2).
Skipped (replaced by a synthetic `SKIPPED` pass) for cameras with `geometry_enabled:false` (ids 2 and 7: end‑on views) and for client references without a usable joint ROI (`InspectGeometryExecutor.java:102-109`). One joint ROI exists **per bucket**: the joint camera runs `jointMode=full`, the other four cameras of the bucket get the same ROI in `visibility` mode (see [api_contracts](./api_contracts.md)).

## 2. Code map
| File (lines) | Role |
|---|---|
| `runner/GeometryRunnerMain` (29) | entry: loads OpenCV, starts `StdioBinaryGeometryLoop` on stdin/stdout |
| `runner/StdioBinaryGeometryLoop.java` (154) | op dispatch (`:66-112`), `handleInspectShm:118-152` — reads current & reference Mats from SHM, builds `InspectionRequest`, `poseLocked = pose_locked ∨ poseLocked ∨ already_aligned`, calls `inspectMats`, writes response header; releases Mats in `finally` |
| `protocol/BinaryProtocol` (80) | IMLB read/write ([framing](../camera-worker/api_contracts.md#imlb-framing)) |
| `shm/ShmMatReader` (125) | resolve `/dev/shm/<name>` (Windows: `%LOCALAPPDATA%\iml_shm`), `RandomAccessFile` + reusable `byte[]` row/frame buffers → `Mat` (comment: no `MappedByteBuffer`, to keep RSS flat for multi-day runs) |
| `shm/ReferenceShmMatCache` (84) | LRU of **2** reference Mats keyed by `camera_id|shm|offset|dims…` (`referenceKey`) |
| `wire/InspectionHeaderMapper` (193) | header → `InspectionRequest` with **defaults** (see §4); key aliases (`mainRoiPolygonNorm`/`main_roi_polygon_norm`/`roi_polygon_norm`/`interest_polygon_norm`) |
| `wire/InspectionResponsePayloadBuilder` (93) | response header assembly (`diag_*` keys, `stage_ms_*`, `jointCamera`, …) |
| `analysis/OpenCvGeometryAnalysisService.java` (1315) | pipeline `inspectMats:104-354` (§3) and helpers |
| `analysis/LabelSeamAnalyzer` (538) | seam: `analyze` → first `LabelSeamBandSegmenter.analyze`, fallback `analyzeHough` (Canny 40/120 → close 3×3 → mask → `HoughLinesP`, pair selection by axis & distance) |
| `analysis/LabelSeamBandSegmenter` (385) | optional band segmentation inside joint ROI: threshold → largest elongated blob → two edges (`EDGE_SAMPLE_COUNT 12`) → `fitLine` → parallelism/width/taper; sensitivity lowers area/edge thresholds |
| `analysis/LabelRimSkewAnalyzer` (247) | yellow rim HSV `[15,70,90]‥[45,255,255]`, close+open ellipse 5×5; requires yellow area ≥1.5 % of ROI, box ≥20×8, `minY = 25 %`; 48 gap samples, left gap = mean x∈[0,.35], right = x∈[.65,1] → asymmetry mm and skew deg (`estimateSkewDeg`) |
| `analysis/RoiPolygonMask` (99) | polygon → 8-bit mask for a rect (`maskForRect`, `applyMask`) |
| `calibration/CalibrationService` (15) | `pixelsToMillimeters(px, pixelsToMm)` |
| `codec/*` | legacy `inspect` path with PNG/base64 debug image |
| `gate/GeometryCorrectnessGateMain` (174) | offline gate tool |
| `dto/*` | `InspectionRequest` (22 fields), `InspectionResponse`, `RoiRect`, `NormPoint` |

## 3. Pipeline `inspectMats` (`OpenCvGeometryAnalysisService.java:104-354`)
1. `validateInputFrames`; `mainRect` = `mainRoi` clipped to frame; clone ROI of reference & current; if polygon: mask both (outside ⇒ 0).
2. **Alignment** (unless `poseLocked`): `alignByHomography` (:425) — grayscale, resize to ≤640 px (`MAX_ALIGNMENT_DIM`), ORB (1500 features), Hamming ratio-test matches, need ≥8 good matches, `findHomography(RANSAC, 3.0)`; shift px from H[0,2], H[1,2] (after un-scaling), rotation = `atan2(H10,H00)`; converted to mm with `pixelsToMm`; failure ⇒ `failedAlignment` = 9999 mm / 9999° (forces FAIL). The reference descriptors are cached (`PreparedReference`, key = `referenceCacheKey`).
3. **Warp** `current → reference` (`warpCurrentToReference`, global H from local+ROI offset) unless pose-locked (then `current.clone()` and `alignment = identity`).
4. **Joint** `inspectJoint` (:856): only if `jointRoi != null`; mask by joint polygon; axis from polygon long side (`estimateAxisDegFromPolygon`); `LabelSeamAnalyzer.analyze(...)`.
5. **Rim skew** `inspectRimSkew` on the aligned ROI (main polygon mask).
6. **Wrinkles** `inspectWrinkles` (:1092): `|gray(ref)−gray(cur)|` → blur 5×5 → threshold 25 → fraction of non-zero pixels in `wrinklesRoi` (defaults to main ROI, synced by orchestrator). `score = nonZero/area` (0..1).
7. **Concentricity** `estimateConcentricity` (:627): `HoughCircles` at ≤320 px (`MAX_CIRCLE_DIM`, param1=100, param2=24, radius ratio 0.08‥0.52 of ROI min side); anchor = lowest `1.2·centerDistance − 2·radius`; inner circle radius ratio 0.35‥0.90 and center distance ≤ max(6, 0.25·R); deviation = center distance in mm (`normalizeDeviationMm`: ≤2 px treated as 0). 0 or 1 circles ⇒ deviation 0 (no reject).
8. Optional debug PNG (`includeDebugImage`).
9. **Decisions** (:~270-296):
   - `alignmentPass = poseLocked ∨ (|shiftX|,|shiftY| ≤ maxShiftMm ∧ |rot| ≤ maxRotationDeg)`;
   - `siblingView = request.jointVisibilityOnly()` (`jointMode=="visibility"`); `concentricityPass = siblingView ∨ deviationMm ≤ maxConcentricityMm`;
   - `jointPass = evaluateJointPass` (:936-981) – see §5;
   - `wrinklesPass = poseLocked ∨ score ≤ maxWrinklesScore` (**when pose-locked wrinkles never fail**: “geometry absdiff wrinkles are too light-sensitive and caused false rejects on matched frames”);
   - `rimSkewPass = evaluateRimSkewPass` (:911) — only on the joint camera (`isJointCameraYellowInspect`: has joint ROI/polygon and not visibility-only), only if rim detector is `active`, requires `skewDeg ≤ maxJointRimSkewDeg ∧ gapAsymmetryMm ≤ maxJointGapAsymmetryMm`;
   - `overallPass = alignment ∧ concentricity ∧ joint ∧ wrinkles ∧ rimSkew`.
10. Log line `geometry_usage …` with stage timings (`prep, align, warp, joint, rim_skew, wrinkles, concentricity, debug`).

## 4. Request defaults (when a key is absent) — `InspectionHeaderMapper.java:20-60`
`pixelsToMm 0.01` (orchestrator always sends YAML `0.02`), `maxShiftMm 0.5`, `maxRotationDeg 1.0`, **`maxConcentricityMm 0.2` (not configurable in YAML — always default)**, `maxJointDefectMm 0.3`, wrinkles threshold = `maxWrinklesScore` else `threshold` else 0.25, `jointMode "full"`, `jointMinWidthMm 0.5`, `jointMaxWidthMm 1.6`, `maxJointParallelismDeg 5.0`, `maxJointTaperMm 0.8`, `jointSeamSegmentationEnabled true`, sensitivity 0.5, `maxJointRimSkewDeg 2.5`, `maxJointGapAsymmetryMm 0.8`, `mainRoi` default 0,0,1224,1024.

## 5. Joint pass logic (`evaluateJointPass:936-981`)
1. no joint ROI ⇒ pass; `jointVisibilityOnly` (sibling camera) ⇒ pass (visibility reported only).
2. joint ROI drawn but seam **not found** ⇒ **FAIL** (missing/empty label).
3. `widthMm < max(0.10, 0.4·jointMinWidthMm)` ⇒ **inconclusive PASS** (Canny double edge noise).
4. parallelism limit scaled by sensitivity `s`: `s ≤ 0.5 → ×(2−2s)`, else `×(1−1.2(s−0.5))` (s=0 → 2×, 0.5 → 1×, 1 → 0.4×).
5. `maxWidthOk = width ≤ jointMaxWidthMm`; `minWidthOk = parallelismOk ∨ width ≥ jointMinWidthMm` (min waived when edges are parallel); `defectOk = defectMm ≤ maxJointDefectMm` (defect forced 0 when the min gate is waived); `taperOk = taper ≤ maxJointTaperMm`.
6. `jointPass = parallelismOk ∧ widthOk ∧ defectOk ∧ taperOk`.
The orchestrator's `JointSeamPolicy` (strict gate) works on the *reported* `jointParallelismDeg/jointWidthMm/jointVisibility`.

## 6. Production thresholds (`config.exemple/blocks/20-detectors.yaml`, `java_geometry`)
`pixels_to_mm 0.02`, `max_shift_mm 0.5`, `max_rotation_deg 1.0`, `max_joint_defect_mm 0.5`, `joint_min_width_mm 0.25`, `joint_max_width_mm 1.6`, `max_joint_parallelism_deg 2.5` (profiles `bench-lan6..10`: 5.0), `max_joint_taper_mm 0.8`, `max_joint_rim_skew_deg 2.5`, `max_joint_gap_asymmetry_mm 0.8`, `joint_seam_segmentation_enabled true`, `…_sensitivity 0.5`, `max_wrinkles_score 0.45`; strict (orchestrator-side) `joint_sibling_min_visibility 0.25, max_joint_parallelism_deg_strict 2.5, joint_min_width_mm_strict 0.4, joint_max_width_mm_strict 1.5`.
Overrides: per-profile (`profiles`), then runtime store (`PUT /api/client/geometry-runtime`), then UI test overrides.

## 7. Tests (`src/test/java/.../geometry/`)
analysis (6, including `RimSkewPolicyTest`, joint gating via package-visible `evaluateJointPassForTest`/`evaluateRimSkewPassForTest`), calibration, gate, protocol, shm, wire. Total 46 (audit 2026‑10‑08).
