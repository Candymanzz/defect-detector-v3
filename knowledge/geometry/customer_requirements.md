# Customer requirements — label seam and placement tolerances

Back to [geometry overview](./overview.md) · [current API](./api_contracts.md) · [recent context](../recent_context.md) · [global architecture](../global_architecture.md)

**Status: customer requirement recorded on 2026-10-20; documentation only.** Current implementation was inspected on `testlinux`, source HEAD `dc98c9be`. This note does not implement or certify these checks, change settings/defaults, or introduce an API contract. Source paths below describe existing building blocks, not proof that the full requirement is satisfied.

## 1. Inspection purpose

The product is a bucket with a label. Besides surface-defect detection, the customer needs a geometry verdict that checks whether the label seam and label placement fall within adjustable production tolerances.

The operator must be able to tune the tolerances for the product/inspection setup. A confirmed out-of-tolerance required geometry measurement must produce **БРАК по геометрии**, even when the surface detector reports ГОДЕН. The desired result presentation should explain which measurement failed and by how much.

## 2. Required adjustable checks

| Check | Customer requirement | Limits not yet specified |
|---|---|---|
| Label seam | Flexible tolerance in **millimetres**; **2 mm** is an example of a setting, not a fixed default | Confirm whether this means maximum seam gap/width, overlap, or deviation from a nominal seam; whether a permitted min/max range is needed |
| Horizontal label placement | Measured horizontal label displacement must be inside its configured tolerance; otherwise geometry reject | Numerical tolerance, reference/anchor, coordinate direction and whether positive/negative limits differ |
| Vertical label placement | Measured vertical label displacement must be inside its configured tolerance; otherwise geometry reject | Numerical tolerance, reference/anchor, coordinate direction and whether positive/negative limits differ |

Horizontal and vertical limits must be independently adjustable. Do not copy the example seam tolerance of 2 mm into either placement limit, and do not treat a seam width of 2 mm as a default already accepted for every bucket.

For an agreed symmetric displacement tolerance, the acceptance model would be `abs(delta_x_mm) <= tolerance_x_mm` and `abs(delta_y_mm) <= tolerance_y_mm`. Asymmetric tolerances would instead use independent lower/upper bounds. Choose the actual range/boundary semantics with the customer before implementation.

All required, valid geometry checks must be within tolerance for the geometry result to pass. Other existing geometry checks remain separate; this request does not remove them or request rotation compensation.

## 3. Desired measured result and explanation

**Desired UI/report capability:** show the measured horizontal and vertical displacement, preferably signed, in millimetres; also show the seam measurement, configured limits and per-check pass/reject reason. Showing only a generic БРАК or an anomaly percentage does not explain a dimensional geometry failure.

Illustrative display only, **not configured defaults or measured production data**:

| Measurement | Actual | Allowed | Result |
|---|---:|---:|---|
| Seam gap, if that is the agreed seam metric | 2.3 mm | maximum 2.0 mm | БРАК: exceeds by 0.3 mm |
| Horizontal label offset | +1.2 mm | ±1.0 mm | БРАК: exceeds upper limit by 0.2 mm |
| Vertical label offset | -0.4 mm | ±1.0 mm | Within tolerance |

An eventual result should identify camera/view and inspection sequence, measurement validity, the actual values/units, the applied tolerance version and the failed checks. These are reporting requirements, **not newly implemented response-field names**.

An unavailable or unreliable measurement must be distinguishable from a measured zero. Agree the production policy for technical/unmeasurable cases before implementation; do not silently present a skipped check as proof that the label is correctly placed.

## 4. Bucket positioning versus label placement

The existing Python X/Y positioner compensates small motion of the bucket in the image to support surface comparison. The customer's requested geometry quantity is **label placement relative to the bucket or another agreed physical reference**, not merely global image motion.

- A correctly labelled bucket that moves slightly as a whole should not fail label-placement QC solely because it changed pixel coordinates.
- A label genuinely displaced relative to the bucket must not become acceptable merely because an alignment step moved the label texture onto the reference.
- The physical anchor and measurement stage must therefore be defined explicitly. Bucket boundaries/rim or an agreed fixture datum are possible anchors to evaluate, not a selected implementation in this note.
- Label rotation is not part of the new requested X/Y placement scope. Do not reintroduce rotation fitting or exclusion strips under the guise of this requirement.

## 5. Millimetres and calibration

Dimensional tolerances require a validated pixel-to-millimetre calibration for the camera/view and relevant measurement area. Bucket curvature and perspective may make a single global scale insufficient; the appropriate calibration/reference plane must be established before claiming accurate millimetres.

Existing `CalibrationService` multiplies pixels by `pixelsToMm` and can derive that factor from a known pixel/physical distance. A configured conversion factor is not by itself evidence of metrological accuracy. The UI must not label uncalibrated pixel measurements as verified millimetres.

## 6. Existing implementation and gaps to verify

**Existing building blocks [code]:**

- `InspectionRequest`/`InspectionHeaderMapper` accept `jointMinWidthMm`, `jointMaxWidthMm`, `maxJointDefectMm`, `maxShiftMm` and `pixelsToMm`.
- `OpenCvGeometryAnalysisService` evaluates seam measurements and reports `shiftXmm`/`shiftYmm` derived from its reference-to-current image alignment. The same `maxShiftMm` is currently applied to both axes in the alignment gate; `poseLocked` bypasses that gate.
- `InspectionResponsePayloadBuilder` already exposes shift and seam values, pass flags and some applied limits. Runtime geometry overrides and UI plumbing exist; see the [current API](./api_contracts.md).

**Not established as satisfying the customer requirement:**

- The reported alignment shifts are not proven measurements of label displacement relative to the bucket. The presence of fields named `shiftXmm` and `shiftYmm` is not sufficient.
- Independent customer-facing X/Y label-placement tolerances and their dimensional calibration need an end-to-end check of measurement, configuration persistence, request mapping, verdict aggregation and UI reporting.
- The seam metric and all active seam gates, including the orchestrator's strict seam policy, must agree with the operator's chosen tolerance. Existing noise-floor/min-width waivers must not be assumed equivalent to the customer's acceptance rules.
- Geometry may be disabled or SKIPPED for some cameras or missing joint ROI. Decide which views/checks are required for the product; a skipped stage does not demonstrate compliance.

## 7. Confirmation and acceptance checklist for later implementation

1. Agree the meaning of the seam tolerance (including the 2 mm example), placement datum, X/Y directions, required views and numerical limits.
2. Establish and verify calibration with known dimensions at the relevant image locations.
3. Test each metric below, at and above its agreed limit, and confirm that changing a setting changes the geometry verdict without hidden contradictory thresholds.
4. Test whole-bucket motion separately from actual label displacement; the former should be compensated while the latter remains measurable.
5. Confirm that an out-of-tolerance required geometry check reaches the final camera/group reject even when surface inspection passes, and that the UI shows the actual deviation and reason.
6. Test missing seam, uncertain measurements, missing calibration/ROI and disabled views under the explicitly agreed technical-failure policy.
7. Measure the added ten-camera latency and output timing; the existing timing target remains relevant. No new latency or accuracy guarantee is made by recording these requirements.
