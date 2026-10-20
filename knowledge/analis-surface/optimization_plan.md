# Optimisation plan — learned normals, FP and selective C++

Back to [Python overview](./overview.md) · [recent decisions](../recent_context.md) · [measured evidence](../performance/2026-10-16-log-review.md)

**Status: partially implemented, 2026-10-20.** Original analysis used `c9944af8`; Python learning optimisations now use baseline `e466367f` on `fix-time-fp-zone`. Prepared normal/FP versions, exact spatial preselection, spatial-feature caching, reused FP grayscale maps and nonblocking HTTP handling are implemented; see [local validation and remaining limits](../performance/2026-10-20-learning-optimization.md). No C++ module or full Python replacement has been implemented. Historical machine timings below remain dated 2026-10-16.

Current owner context: **six CPU cores, RT environment and a 500 ms trigger-to-verdict requirement**. See [machine/allocation distinctions](../deploy-ops/production_environment.md) and [acceptance boundaries](../performance/latency_requirements.md); these supersede the earlier four-core assumption and 600 ms target.

## 1. What is already implemented

- Reference preprocessing and positioner caches (`reference_preparation.py`, `inspection_positioning.py`).
- Translation-only positioning; no rotation fit or exclusion strips in that path.
- Configurable inspection workers, OpenCV internal threads limited to one, deferred heatmaps/history with a foreground-priority workload gate.
- Generation-marked learning stores now prepare bulk updates off the inspection path. Ready data is pinned for one frame; a lightweight membership filter removes deleted exceptions while additions are prepared. See [publication semantics](../performance/2026-10-20-learning-optimization.md#1-prepared-state-instead-of-synchronous-image-reloads).
- Exact spatial normal preselection and cached spatial geometry; shared grayscale maps for FP activity. Further FP image recomputation remains a profiling target.

Do not propose these existing features as if missing. The next work concerns measured tail latency and how state is prepared/published.

## 2. Verified hot paths and remaining uncertainty

The following full-reload/per-case baseline observations describe the source before the new learning optimisation. They explain the motivation; they must not be read as an unchanged description of `apply()` today.

**[code]** `learned_normals.py::AcceptedNormalMemory.apply` calls `_refresh_from_disk_if_changed` under its lock. A changed generation calls `_load`, which clears `_cases`, walks all case JSON files and reads NPZ arrays, including saved source frames/crops. This is a full accepted-normal store refresh before filtering by product/reference, not just an incremental update for the current camera.

**[code]** Candidate matching loops over candidates and saved cases (`apply`, `_best_matching_case`, `candidate_similarity`). The polygon-gap fallback has nested loops over points and segments (`_polygon_gap`). Multiple small NumPy/Python operations are performed during each comparison.

**[code]** `inspection_service.py::_recheck_fp_zones` loops through FP zones, measures their activity and recomputes local differences for triggered mini-references. Several helpers convert maps to grayscale and allocate/copy arrays; determine which can be shared/reused without changing semantics.

**[logs]** The compound learned-normal/FP interval reached 1019.541 ms per camera, and Java-side queuing reached 1155 ms. Positioning averaged 29.200 ms; `py_diff` 73.389 ms. Existing timings do not isolate how much of the compound interval is disk loading versus matching versus FP recomputation.

## 3. Proposed sequence of work

1. **Measure separately.** Add bounded stage timings for generation check/reload, candidate extraction, case preselection/matching, residual verification and FP mini-reference comparison. Test before/after learning updates and during simultaneous ten-camera requests.
2. **Prepared state: implemented; verify on the machine.** Bulk arrays prepare in the auxiliary lane, unchanged files are reused and frame reads are pinned. Validated membership keeps unchanged old exceptions and drops deleted ones. New versions are not synchronously acknowledged by all processes; continuous load and existing operator product locks still need production timing checks.
3. **Comparison/allocation reduction: partially implemented.** Exact spatial preselection, spatial caches and shared FP activity grayscale maps preserve matching gates and residual safeguards. Profile remaining FP mini-reference recomputation and full-frame allocations before changing crop/context semantics.
4. **Prototype one native kernel.** Start with batched candidate/template/geometry matching if profiling confirms Python orchestration dominates there. Keep the API and the rest of the detector unchanged for a controlled comparison.
5. **Consider larger image kernels only after measurement.** Combining successive operations and reusing buffers may help; translating each existing OpenCV call one-for-one is not itself an algorithmic speedup.

## 4. Why selective C++ instead of an immediate full rewrite

OpenCV's Python interface already calls C++ algorithms; many NumPy operations are native and release the GIL. A whole-service rewrite would also require reproducing settings expansion, ROI/FP contracts, learned-case persistence, SHM lifetime, reference replay, history and deferred UI behavior. It creates more compatibility work than replacing a measured CPU kernel.

**[proposal]** A C++ extension exposed through `pybind11` could accept existing image/template buffers in batches, minimise copying and explicitly release the GIL while running code that does not access Python objects. GIL release is not automatic. Array shape, dtype, stride and ownership/lifetime need validation. Independent buffers/state or synchronisation are required for concurrent calls.

Reference documentation: [OpenCV Python bindings](https://docs.opencv.org/4.x/da/d49/tutorial_py_bindings_basics.html), [NumPy thread safety](https://numpy.org/doc/stable/reference/thread_safety.html), [pybind11 buffers/NumPy](https://pybind11.readthedocs.io/en/stable/advanced/pycpp/numpy.html), [pybind11 GIL management](https://pybind11.readthedocs.io/en/stable/advanced/misc.html#global-interpreter-lock-gil).

A native module would need explicit Linux/Windows build and deployment support; the current `rebuild-and-run.sh` does not build such an extension. More native threads do not create more CPU cores. Recheck process/thread limits against the effective CPU allocation on the six-core RT machine, and avoid nested OpenCV/native worker oversubscription.

## 5. Acceptance gates before enabling any optimisation

- Use the same pinned images, references, settings, ROI/FP geometry and learning-store snapshot for baseline/candidate runs. Separate cold-cache, warm-cache and update-during-inspection tests.
- Compare intermediate maps/masks, candidate matches, main/sub-zone scores and final verdicts; define numerical tolerances before accepting differences near thresholds.
- Include real unknown defects inside/overlapping learned normals and FP zones, small X/Y shifts, glare/text, missing data and concurrent state updates. Faster suppression must not hide new defects.
- Measure ten-camera wall time, queues, CPU/RAM and P95/P99/max, not only average per-function time. Verify software output timing versus the relevant DI2 window; assess physical output timing separately.
- Record host CPU, process/thread settings and analysis scales. A fast isolated kernel does not establish the current 500 ms full-pipeline requirement; 600 ms belongs to historical comparisons.

No percentage speedup or guarantee of the 500 ms trigger-to-verdict deadline can be claimed before those comparisons. Track output timing separately. C++ does not by itself remove the observed pre-frame waiting interval.
