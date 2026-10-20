# Machine log review — 2026-10-16

Back to [global architecture](../global_architecture.md) · [recent context](../recent_context.md) · [optimisation plan](../analis-surface/optimization_plan.md) · [log collection](../deploy-ops/overview.md#8-export-machine-logs-to-windows)

**Status: historical measurements, documented on 2026-10-20.** No new machine run was performed for this knowledge-base update. Times are Europe/Minsk, UTC+03:00. These are observed software events, not oscilloscope measurements or confirmation of mechanical reject actuation.

## 1. Exports and sample boundaries

Both exports were downloaded to the same local filename, `Downloads/defect_detector.log`, which was overwritten between analyses. File size, time interval and cycle ranges distinguish them:

| Export | Journal interval | Bytes / lines | Complete cycles used for end-to-end timing |
|---|---|---|---|
| A, downloaded 15:43 | 15:31:18.117933–15:41:19.586183 | 5,297,271 / 30,558 | 62, sequences 244–305 |
| B, downloaded 16:38 | 16:31:57.748424–16:36:58.249345 | 2,653,536 / 15,324 | 30, sequences 620–649 |

Export A contains 630 camera decisions and 126 group verdicts; its first cycle 243 has no start event in the file. Export B contains 310 camera decisions and 62 group verdicts. Its first cycle 619 has no start, and its last cycle 650 has received frames but ends outside the export. These boundaries are not evidence of an inspection hang.

Local analysis artifacts, not guaranteed to exist in another checkout: `.verification/log-analysis-20261016-{1543,1638}.md`, `log-timings-20261016-{1543,1638}.json`, `di2-deadlines-20261016-{1543,1638}.json`, `log-exceptions-20261016-1638.json`. Essential findings are reproduced below so this note does not depend on those ignored files.

## 2. Correlation rules

- Key cycles by **orchestrator PID and inspection sequence**, not sequence alone across restarts.
- Do **not** assume `frame_id == inspection_id`. In export B, camera frame 623 belongs to inspection sequence 633. Resolve the association using `server.inspect_result` (`camera_id`, `frame_id`, `inspection_id`) and verify against `inspection started` followed by that camera's `capture_ok`.
- A complete measured cycle requires ten distinct camera captures, ten stage results, both group verdicts and completed writes for both output groups.
- Match each request to its observed DI2 rising/falling window. Use Java's embedded event timestamp consistently; journal timestamps supply metadata for lines without it.
- `reject_output` can appear both for early rejection and for the final batch. For final completion use the final write per group; measure the first `active=true` write separately when discussing early rejection.
- Do not use `stage_timing.total_ms` as the current cycle's duration: it is based on the older camera-loop start. Reconstruct cycle boundaries from events. A late `stage_timing`/UI publication is not the output completion time.

## 3. End-to-end timings

All entries are **mean / maximum**, milliseconds, over the complete-cycle samples above.

| Interval | Export A, 62 cycles | Export B, 30 cycles |
|---|---:|---:|
| Hardware-frame request → all ten frames received | 2277.145 / 2313 | 2277.433 / 2299 |
| Last received frame → both group verdicts | 258.387 / 387 | 438.833 / 1332 |
| Last received frame → completed final writes for both DO groups | **268.113 / 402** | **448.900 / 1341** |
| First received frame → both final DO writes | 287.855 / 415 | 467.800 / 1369 |
| Last group verdict → both final DO writes | 9.726 / 26 | 10.067 / 53 |
| `di_received` DI2↑ → both final DO writes | 2547.355 / 2690 | 2728.267 / 3615 |

Last-frame-to-output P95: A **287.950 ms**, B **1117.700 ms**. After receiving all frames, A had **0/62** cycles over 600 ms; B had **5/30 (16.67%)**.

The roughly 2.277 s before all frames includes waiting for the externally triggered frame. It is not pure image-computation time or a separately measured exposure. The exact physical camera-trigger edge inside this window is not separately logged. Therefore a 268 ms processing result does **not** prove DI2-to-output completion within 600 ms.

## 4. Deadline relative to DI2 falling

| Metric | Export A | Export B |
|---|---:|---:|
| Both verdicts and final writes before recorded DI2↓ | 62/62, **100%** | 25/30, **83.33%** |
| Late cycles | 0 | 5 |
| Late-only lateness: mean / maximum | — | 620.4 / 922 ms |
| On-time output margin: mean / minimum | 179.226 / 34 ms | 117.960 / 26 ms |
| Time available after the last frame until DI2↓, mean | 447.339 ms | 443.800 ms |

Export B outliers:

| Sequence | Last frame → final outputs | Lateness relative to DI2↓ |
|---|---:|---:|
| 627 | 889 ms | 453 ms |
| 630 | 837 ms | 377 ms |
| 631 | 1077 ms | 649 ms |
| 632 | 1151 ms | 701 ms |
| 633 | 1341 ms | 922 ms |

Both groups returned **ГОДЕН** in all five outliers; early rejection could not provide their final accept result sooner.

DI/DO scan-cycle means were approximately 42 ms, with reported scan maxima up to 96 ms. Recorded edge times can lag physical edges; the minimum software margins of 26/34 ms are not a guarantee relative to the physical signal. Completed SDK writes also do not confirm mechanical actuation. A repeated write of an unchanged level need not create an electrical edge.

## 5. Early rejection, verdicts and quality limits

- A: all 630 camera decisions ACCEPT and all 126 group verdicts pass. All output records have `active=false`; this export did not exercise reject activation.
- B: 279/310 camera decisions ACCEPT, 31 REJECT; 52/62 group verdicts pass, ten reject. Both groups reject in five cycles including the start-truncated sequence 619. In the four fully timed reject cycles 620–623, the first reject writes occur **48–268 ms after the last frame**, with **152–402 ms** before recorded DI2↓.
- B has 72 output records: 62 final writes and ten early reject writes, not 72 independent inspections.
- These are detector decisions, not labelled ground truth. Neither the false-reject rate nor defect recall can be established without reference/current frames and confirmed defect labels.
- Java positioning was skipped; Python positioning calculations ran. No positioning execution error is observed, but `align_pass=true` alone does not prove Python alignment accuracy.

## 6. Computation and learning-update spikes

Mean / maximum per camera, milliseconds; do not add these as sequential whole-cycle costs because camera work overlaps and some intervals are nested.

| Stage | Export A | Export B |
|---|---:|---:|
| Python positioning | 28.244 / 67.464 | 29.200 / 55.000 |
| `py_diff` | 61.539 / 125.736 | 73.389 / 131.695 |
| `py_fp_recheck` | 7.776 / 16.034 | **74.636 / 1019.541** |
| Java-side Python queue | 44.946 / 236 | **93.406 / 1155** |
| Python RPC excluding that queue | 117.094 / 238 | 202.732 / 1164 |
| Python internal `py_total` | 112.848 / 233.067 | 197.875 / 1160.548 |

`py_fp_recheck` spans accepted-normal matching, any subsequent anomaly recalculation and FP mini-reference checks. It is not an isolated measurement of FP zones alone (`inspection_service.py`: `anomaly_finished` → `fp_recheck_finished`).

Export B has **12 successful** `accept-all-as-normal` requests from 16:33:13 to 16:34:14. The five outliers occur in this period. In sequence 633, cam=7 spends **1019.541 ms** in the learned-normal/FP interval; cam=9 subsequently waits **1155 ms** in the Java queue before a 177 ms RPC.

The confirmed cost is this compound interval and the queue it creates. The existing logs do not separately quantify disk reload, index preparation, cache rebuild and matching, or prove that every learning POST caused a particular spike.

After the last successful update, all **16 cycles 634–649** finish before recorded DI2↓: mean **321.375 ms**, maximum **376 ms** after the last frame. If all five outliers are excluded, the remaining 25 cycles average 326.880 ms, maximum 394 ms; this conditional statistic must not replace the overall 448.900 ms mean.

The Python internal executor queue is small (B mean 0.121 ms, maximum 1.681 ms), as is measured product-lock waiting (0.012 / 0.036 ms). These measurements do not support attributing the spikes to prolonged waits on those locks.

Both exports show six Python processes and unchanged `inspect_scale`: `0.2` for zero-based cam=2/7, `0.7` for the other cameras. These are observed runtime values, **not** the repository template's `0.5` / `0.75`. Reduced resolution may affect small-defect detection; these logs cannot quantify that loss.

## 7. Artifacts, errors and lighting

- A: 630 inspect and 630 deferred heatmap HTTP 200 responses; all 8617 logged HTTP responses were 200. Deferred history was enabled in all 630 timing records. Background save mean 49.577 ms, maximum 121.389 ms.
- B: all 310 inspect and 310 heatmap requests returned 200. One learning request returned **409** when repeating a review already successfully accepted earlier; the response body was not exported. The implementation maps an already-accepted review to 409, consistent with the repeated request ID.
- Neither export contains an observed inspection timeout, capture failure, traceback or learning-history indexing failure. This does not establish startup health outside the exported windows.
- `Unsupported upgrade request` repeats 7236 times in A and 3616 in B while health requests continue to succeed. Protocol/client investigation is pending; its latency contribution was not measured.
- Hold-mode warnings repeat 620 times in A and 310 in B. In B there are also `interval_flash On/Off` and native-device `state=on/off` events. The warnings do not establish that the physical light was off; no light-command failure was found in B.
- Critical-path `py_heatmap_ms` is about 0.016 ms. This is not total deferred rendering or the time until a heatmap appears on the UI.

## 8. Interpretation

The latest export is worse in tail latency, not evidence of a continuously slow positioner. Learning/FP work and subsequent Java queuing are the first optimisation targets. The sessions are not a controlled code-version A/B test: image content/verdicts and operator learning activity differ. Keep proposals separate from implemented changes in the [optimisation plan](../analis-surface/optimization_plan.md).
