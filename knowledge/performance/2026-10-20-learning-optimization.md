# Learned-normal and FP optimisation — implementation and local validation

Back to [project context](../PROJECT_CONTEXT.md) · [optimisation plan](../analis-surface/optimization_plan.md) · [500 ms requirement](./latency_requirements.md)

**2026-10-20; implemented in working branch `fix-time-fp-zone`, baseline `e466367f`.** Scope: Python learning-state refresh, normal matching and repeated FP activity work. Process/thread counts, Java scheduling, geometry and positioning algorithms were not changed. This is local Windows validation, not deployment or a production RT deadline test.

## 1. Prepared state instead of synchronous image reloads

`services/learning_snapshot.py` maintains prepared state with one weak-registry watcher per process. It checks versions at roughly 50 ms intervals and also wakes on pending reads. Preparation shares the existing auxiliary-work gate, yielding to queued/running inspections. Normal/FP arrays are loaded in that preparation path; unchanged files/crops are reused.

An inspection pins ready normal/FP data once, under the existing camera configuration lock. Later case lookups used for result overlays use that same pinned state, without reacquiring the normal-store lock or triggering a reload. Full source frames are loaded on demand for the case-image UI; matching retains its required templates and colour crop.

On a version change, a lightweight committed-membership/metadata check retains previously prepared, unchanged cases/zones. Removed members stop being used by the next inspection; additions become usable after preparation. If membership cannot be verified, exclusions are conservatively unavailable, rather than silently using a potentially deleted exception. A running inspection keeps its already-pinned version.

Important limits:

- Application is per inspection, **not a distributed barrier across all ten cameras/processes**. The existing `saved` API response means persisted, not acknowledged ready by every Python server.
- New examples can take effect later on other processes; continuous foreground load can delay preparation. Unavailable new exceptions can temporarily increase rejects; preparation does not weaken defect checks.
- Small marker reads and, once per changed generation, membership/metadata filtering remain on the request path. Bulk NPZ/PNG reload and feature preparation move off it.
- Existing operator learning actions still use their product/learning locks. Saving a large review on the process serving that camera can still delay it; this work does not establish that live learning always meets 500 ms.

Normal `.snapshot` membership and FP `*.snapshot.json` publications are installed before their generation markers. Readers validate versions before publishing prepared data. Normal JSON/NPZ and the legacy FP JSON list remain readable; no migration of saved examples is required. Cross-process OS file locks serialise writers/preparation, and do not participate in the inspection read path. `LearningSnapshot.close()` stops watching and waits for a load before temporary-store teardown.

Python runtime logs use `learning_snapshot event=pending|ready|prepare_failed`, `kind=normals|fp`, generation IDs for pending/ready and `prepare_ms` on ready (including waiting for the auxiliary slot). On Windows, short-lived marker-reader sharing violations are retried only by publishers; inspections do not wait for that retry.

## 2. Exact normal preselection and reused FP maps

`learned_normals.py::_NormalScope` groups enabled cases by camera-scoped product and reference, indexes normalized centroids by sorted X, and applies a vectorized conservative position gate. It retains every case that could pass the existing spatial tolerance. Final `candidate_similarity`, best-match selection, colour residuals, unmatched components and overlap safeguards remain in use; there is no approximate ANN search or top-k truncation.

Spatial geometry is cached by analysis coordinate scale. Rejection diagnostics still examine the nearest discarded position case when needed. FP activity checks reuse one pair of grayscale maps across zones; triggered mini-reference checks and quiet-zone safeguards are unchanged. This does not remove all remaining FP recomputation or optimise the entire `py_diff` stage.

Blocking learning/FP HTTP handlers now use synchronous FastAPI handlers so their image compression and file I/O execute in the framework's worker pool instead of blocking the HTTP event loop. Their request/response contracts are unchanged.

## 3. Reproducible validation

`tests/test_learning_optimization.py` covers pending additions/deletions, pinned reads, generation races, incremental NPZ reuse, lazy preview loading, simultaneous writers, exact indexed/exhaustive matches and diagnostics, FP updates and a blocked learning handler alongside a responsive HTTP health request. Existing residual/overlap regressions are retained.

Full local Python suite: **401 passed, 16 skipped** (24.72 s). Skipped checks are not production hardware evidence. The new regression coverage also exercises an actual Windows marker-sharing conflict and exhaustive fallback for malformed unrelated geometry, so eager index preparation does not make that data break service startup.

`scripts/benchmark_learning_optimization.py` reads baseline source through `git show`; it does not switch the worktree. Generated stores/reports stay inside `.verification`; `frames36` is read only. It compares steady matching, repeated update boundaries and FP activity conversions. The camera test uses reference frame 387, original frames 390–395 from all ten cameras, and separate synthetic marks on real reference backgrounds to exercise actual normal matches. Those marks are controls, not labelled real machine defects.

Example from `analisSurface/backend`:

```powershell
.\.venv\Scripts\python.exe scripts/benchmark_learning_optimization.py --baseline e466367f --counts 20 100 --runs 15 --dataset "C:\Users\ShablinskiyM\Desktop\frames36" --output "../../.verification/learning-optimization-20261020.json"
```

The standalone summary below is maintained with the final benchmark, so this note does not require another developer's ignored JSON files. Local single-process component timings cannot be added together or substituted for a ten-camera trigger-to-verdict time; the [500 ms requirement](./latency_requirements.md) still needs a machine run.

## 4. Local measured results

Baseline `e466367f` versus the modified Python, Windows, OpenCV internal threads = 1. Steady/component samples: 15 per scenario. Update samples: five committed additions, growing stores from 20 to 25 and 100 to 105 examples. Means in milliseconds:

| Measured operation | Before | After |
|---|---:|---:|
| `AcceptedNormalMemory.apply`, 20 saved examples, steady | 9.200 | 4.941 |
| `AcceptedNormalMemory.apply`, 100 saved examples, steady | 25.860 | 3.851 |
| First `apply` after an addition, starting with 20 examples | 76.817 | 15.768 |
| First `apply` after an addition, starting with 100 examples | 247.939 | 16.384 |
| Activity metrics for 20 FP polygons, **not** the complete FP mini-reference stage | 40.485 | 13.338 |

For the 100-example update scenario, observed P95/max changed from 289.696/295.023 ms to 17.719/17.761 ms. Five samples are not a production tail-latency guarantee. Ready-state output maps matched exactly; pending new examples were deliberately not used until preparation, while prior valid examples remained available.

Original `frames36` inputs: **60 before/after pairs** (ten cameras, frames 390–395), plus **ten control pairs** with synthetic marks on real reference backgrounds. All compared final verdicts, scores, matched-case IDs and retained diff/segmentation arrays were equal. The ten controls exercised ten actual normal matches; the 60 original frames had no matches to those controlled training examples. This is equivalence evidence, not labelled false-reject/recall evaluation.

On the 60 original frames, mean Python time was **127.700 → 128.846 ms**, P95 **165.019 → 163.533 ms**, maximum **189.371 → 210.061 ms**. This run does **not** show a general full-pipeline speedup on that sample. It demonstrates the targeted reduction with growing normal stores/updates; alignment and diff costs remain. Before/after calls were sequential on the local host, not a randomized production A/B test.

Generated details: `.verification/learning-optimization-20261020.json` (optional local artifact; essential values are reproduced here). Restart the Python service processes to activate the changed source. No process was restarted on the production machine by this work.
