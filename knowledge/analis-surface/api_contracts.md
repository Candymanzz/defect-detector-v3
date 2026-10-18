# analis-surface — API contracts

Back to [overview](./overview.md) · Base URL `http://127.0.0.1:8000` (+ pool ports 8001‥8005). Every JSON **object** response gets `detector_id` injected by `A:main.py` middleware. Validation errors (422) are logged without payload bytes. File refs: `A:api/*.py`.

## 1. Production (called by the orchestrator through `AnalisSurfaceHttpBinaryRpcSupervisor`)
| Method & path (file:line) | Request | Response | Notes |
|---|---|---|---|
| `GET /health` (`main.py:80`) | – | `{status, service, detector_id}` | |
| `GET /detector/health` (`inspection_routes.py:149`) | – | `DetectorHealthResponse{status, service:"analisSurface", detector_id}` | used by supervisor probe `:8000/detector/health` |
| `POST /upload-ref-shm` (:159) | `ShmFrameRequest` (`product_type` = scoped `…#cam=N`) | `{message, product_type}` | frame copied from SHM; `set_reference_frame` computes hash/invalidations; `OSError/ValueError → 400` |
| `POST /roi-polygon` (`roi_routes.py:12`) | `{product_type, points[{x,y}≥3], perspective_line?[2], algorithm_params?}` | `{product_type, points, perspective_line}` | requires reference (400 otherwise); sets perspective line **before** polygon |
| `POST /inspect-shm` (:332) | `ShmFrameRequest` | `InspectResponse` | hot path, no visuals; with `heatmap_u8_output_path` also writes heat-map; with `defer_heatmap` returns `deferred_heatmap_token`; heat-map write failure never fails the verdict |
| `POST /render-heatmap` (:363) | `{token, heatmap_u8_output_path, heatmap_max_width?}` | `ShmVisualsResponse` | **410** if token expired; **429** if background queue busy (inspection has priority); renders gray‑u8 from frozen `(mask, diff_map, polygon)` |
| `POST /inspect-shm-visuals` (:394) | `ShmVisualsRequest` (+`aligned_image_u8_output_path`, `diff_map_u8_…`, `heatmap_u8_…`, `segmentation_mask_u8_…`, `heatmap_max_width`) | `ShmVisualsResponse` | repeats inspection with `skip learning review`; export failure → **500** (must not be silent) |
| `POST /inspect-test-frame` (:482) | `TestFrameInspectRequest{cache_key, file_path, image_url?, product_type, analysis_profile?, roi_polygon_norm?, perspective_line_norm?, simple|detailed|pro knobs, heatmap_u8_output_path?, heatmap_max_width?, inspect_scale?, *_u8_output_path}` | `ShmVisualsResponse` | JPEG from disk (BGR cache of 4 by `(cache_key,file,url)`), resized to reference size if different, ephemeral settings, `store_learning_review=False`, nothing persisted; exactly one of simple/pro |
| `POST /clear-inspection-context` (:508) | – | `{ok, cleared:{…}}` | drops references, ROI, FP zones (full stop) |
| `POST /fp-zones` / `GET /fp-zones/{product}` / `GET /fp-zones/{id}/crop` / `DELETE /fp-zones[/{id}]` | `{product_type, points[{x,y}], heatmap_w, heatmap_h, note}` | `FPZoneResponse{id, points_norm_heatmap, points_norm_ref, has_crop, reference_hash,…}` | creation needs a **previous inspection** of that product (crop is cut from the last aligned frame) ; the orchestrator's `replace_fp_zones` = DELETE per product + POST each |

### `ShmFrameRequest` (`schemas.py:224-252`)
`product_type, shm_name, width, height, stride?=w·3, shm_offset=0, threshold?, detector_id?, algorithm_params?, analysis_profile?, analysis_test_settings?{mode: simple|pro|detailed, knobs/simple/pro…}, alignment_h_ref_to_cur? (3×3 or 9 floats), roi_polygon_norm? [{x,y}], perspective_line_norm? [{x,y}×2: near→far], skip_learning_review, defer_learning_review, defer_heatmap, test_analyze, inspect_scale? ∈(0,1], heatmap_u8_output_path?, heatmap_max_width?`.
Behaviour (`_inspect_shm_sync:~210-290`): `_sync_request_roi` installs ROI/perspective line for the product (ROI is **per request**, no reliance on RAM state; absent line ⇒ default “top is far”); frame is copied out of SHM (`np.copy`); `analysis_test_settings` → `expand_merged(...)` temporary overrides (pro mode uses the saved sensitivity as anchor); `inspect_scale` validated; learning review stored unless `skip_learning_review ∨ test_analyze`.
### `InspectResponse` (`schemas.py:36-66`, mapper `mappers.py:136+`)
`product_type, status ("ГОДЕН"|"БРАК"), anomaly_score, threshold, detector_id, raw_anomaly_score, rechecked_zones_count, recheck_adjustment, rechecked_zone_ids[], main_roi_score, sub_zone_scores[{zone_id,label,anomaly_score,threshold,status}], fp_zone_scores[{zone_id,triggered_vs_reference,applied_fp_etalon,residual_score,status,note}], inspection_id (review uuid | null), learned_normal_matches_count, learned_normal_adjustment, matched_accepted_case_ids[], excluded_normal_zones[{kind: accepted_normal|fp_zone, case_id, similarity, polygon, polygon_px?, coordinate_width/height?, excluded_from_score}], py_align_ms, py_diff_ms, py_anomaly_ms, py_fp_recheck_ms, py_heatmap_ms, py_total_ms, deferred_heatmap_token?`.
`ShmVisualsResponse` adds `aligned_image_u8 | diff_map_u8 | heatmap_u8 | segmentation_mask_u8: {path,width,height,stride,channels,dtype:"uint8"}`.

## 2. File based (manual / test)
`POST /upload-ref` (multipart `product_type`, `file`) · `GET /reference/{product_type}` (base64) · `POST /inspect` (multipart, optional `threshold`) → `InspectWithVisualsResponse` (base64 images). HTML pages: `/local-inspection-test`, `/fp-zone-test`, `/learning-review`.

## 3. Analysis settings (`analysis_settings_routes.py`, `analysis_settings_preset_routes.py`)
| Route | Meaning |
|---|---|
| `GET /analysis-settings/defaults` | stock `AnalysisSettings` |
| `GET|PUT|DELETE /analysis-settings/{profile}` | effective settings + overrides; PUT partial (validated), DELETE resets (clears knobs too) |
| `GET|PUT /analysis-settings/{profile}/simple` | `{threshold∈(0,1], sensitivity∈[0,1]}` ⇒ `expand_merged(threshold, sensitivity, **saved strengths)` ⇒ stored overrides |
| `GET|PUT /analysis-settings/{profile}/strengths` and `/detailed` (alias) | six strengths 0..100 (50 = stock): `noise_tolerance, scratch_sensitivity, edge_suppression, text_handling, preprocess_strength, far_edge_boost`; stored, overrides recomputed with the current simple sensitivity |
| `GET|PUT /analysis-settings/{profile}/pro` | compatibility: threshold + strengths |
`{profile}` is the camera **analysis_profile** (`bench-lanN`), *not* the scoped product. The orchestrator proxy rewrites `/api/orchestrator/analysis-settings/camera/{id}…` to this form. 400 on validation errors.

## 4. ROI sub-zones (`roi_sub_zone_routes.py`)
`POST /roi-sub-zones {product_type, points≥3, threshold?, label}` · `GET /roi-sub-zones/{product_type}` · `PATCH /roi-sub-zones/{zone_id} {threshold?, label?, points?}` · `DELETE /roi-sub-zones/{zone_id}`. Points must lie inside the parent ROI (`validate_polygon_inside_parent`). Stored in `data/roi_sub_zones.json`. Sub-zone area is **cut out** of the main ROI score and scored separately with its own threshold.

## 5. Learning (`learned_normal_routes.py`)
`GET /learning/reviews?product_type=` · `GET /learning/reviews/{id}` (detail incl. defects) · `GET /learning/reviews/{id}/image/{kind}` (frame|heatmap|diff|mask|defect crops; `Cache-Control: no-store`) · `POST /learning/reviews/{id}/defects/{defect_id}/accept-as-normal {note}` · `POST /learning/reviews/{id}/accept-all-as-normal {note}` · `GET|DELETE /learning/accepted-cases` · `GET /learning/accepted-cases/{case_id}/image` · `DELETE /learning/accepted-cases/{case_id}`. 404 = review/defect expired (reviews are limited to 200), 409 = domain conflict (already accepted, mismatched reference…).

## 6. Shared memory contract
- Input frames: BGR8 `uint8`, row `stride` bytes, offset `shm_offset` inside file `/dev/shm/<shm_name without leading slash>` (`resolve_shm_path`); reading via `mmap` + `np.ndarray` strided view (`open_bgr_shm_frame`), then **copied** (`np.copy`) so the ring slot can be reused.
- Output images: written by `write_u8_image_to_shm(path, image)`: raw `uint8`, rows contiguous; descriptor returned (`channels` 1 or 3). Heat-map: gray‑u8, ROI‑masked, width ≤ `heatmap_max_width` (orchestrator uses `ui.heatmap_preview_max_width 384`).
- The orchestrator converts SHM names to logical names (`iml_…`) for HTTP (Windows paths broke uvicorn).
