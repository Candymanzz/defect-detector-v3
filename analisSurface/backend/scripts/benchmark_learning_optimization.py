"""Read-only baseline comparison for learning optimisations.

Historical Python source is read with git show, without switching the worktree.
Generated learning stores/reports stay under .verification. This measures Python
on the local host, not trigger/capture/Java/PLC latency or production RT timing.
"""
from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import time
import types

BACKEND = Path(__file__).resolve().parents[1]
PROJECT = BACKEND.parents[1]
sys.path.insert(0, str(BACKEND))

import cv2
import numpy as np

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_service import InspectionService
from app.services.learned_normals import AcceptedNormalMemory, extract_defect_candidates

_owned_snapshots = []


def historical(name, revision, relative):
    source = subprocess.check_output(["git", "show", f"{revision}:{relative}"],
                                     cwd=PROJECT, text=True, encoding="utf-8")
    module = types.ModuleType(name)
    module.__file__ = str(PROJECT / relative)
    sys.modules[name] = module
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    return module


def summary(samples):
    return {"mean_ms": round(statistics.mean(samples), 3),
            "p95_ms": round(float(np.percentile(samples, 95)), 3),
            "max_ms": round(max(samples), 3)}


def measure(function, runs):
    samples = []
    result = None
    for _ in range(runs):
        start = time.perf_counter()
        result = function()
        samples.append((time.perf_counter() - start) * 1000)
    return summary(samples), result


def service(cls, root, shared_normals=None):
    instance = cls(learned_normals_dir=shared_normals or root / "normals",
                   reviews_dir=root / "reviews", session_wipe=False)
    instance._fp_zones_file = root / "fp.json"
    instance._fp_crops_dir = root / "fp-crops"
    instance.fp_zones = {}
    instance._fp_zones_generation = None
    instance._analysis_settings_file = root / "settings.json"
    if hasattr(instance, "_fp_snapshot"):
        _owned_snapshots.extend([instance._fp_snapshot, instance._accepted_normals._prepared])
    return instance


def check_equal(old, new):
    assert old.status == new.status, (old.status, new.status)
    assert old.anomaly_score == new.anomaly_score, (old.anomaly_score, new.anomaly_score)
    assert old.matched_accepted_case_ids == new.matched_accepted_case_ids
    np.testing.assert_array_equal(old.deferred_heatmap_data[0], new.deferred_heatmap_data[0])
    np.testing.assert_array_equal(old.deferred_heatmap_data[1], new.deferred_heatmap_data[1])


def inspect(instance, product, image, settings):
    return instance.inspect_frame(product, image, settings=settings, threshold=.45,
                                  include_visuals=False, include_heatmap_u8=False,
                                  store_learning_review=False, inspect_scale_after_align=.75,
                                  defer_heatmap=True)


def synthetic(root, legacy, legacy_service, counts, runs):
    from benchmark_learned_normals import synthetic_reference, case_rectangles
    settings = AnalysisSettings.from_overrides({"use_patchcore": False})
    rows = []
    for count in counts:
        folder = root / f"synthetic-{count}"
        reference = synthetic_reference(640, 480)
        writer = AcceptedNormalMemory(folder / "normals", session_wipe=False)
        _owned_snapshots.append(writer._prepared)
        rectangles = case_rectangles(640, 480, count)
        # Reuse one crop shape while distributing cases across the image.
        frame = reference.copy()
        x, y, w, h = rectangles[0]
        frame[y:y+h, x:x+w] = 245
        diff = np.zeros_like(frame)
        diff[y:y+h, x:x+w] = 120
        mask = np.zeros_like(frame)
        mask[y:y+h, x:x+w] = 255
        candidate = extract_defect_candidates(frame, diff, mask)[0]
        from dataclasses import replace
        for i, (x, y, w, h) in enumerate(rectangles):
            current = reference.copy()
            current[y:y+h, x:x+w] = 245
            shifted = replace(candidate, id=str(i), bbox=(x, y, w, h),
                              bbox_norm=(x/640, y/480, w/640, h/480),
                              polygon_norm=[(x/640, y/480), ((x+w)/640, y/480),
                                            ((x+w)/640, (y+h)/480), (x/640, (y+h)/480)],
                              _spatial_cache={})
            writer.add_from_candidate(product_type="bench#cam=0", reference_hash="ref",
                                      inspection_id=str(i), candidate=shifted, source_frame=current)
        old = legacy.AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
        new = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
        _owned_snapshots.append(new._prepared)
        refresh = new._prepared.refresh
        new._prepared.refresh = lambda: None  # deterministic update boundary
        def apply(memory):
            return memory.apply(product_type="bench#cam=0", reference_hash="ref", aligned=frame,
                                diff_map=diff, segmentation_mask=mask)
        apply(old)
        apply(new)
        old_time, before = measure(lambda: apply(old), runs)
        new_time, after = measure(lambda: apply(new), runs)
        assert before.matched_case_ids == after.matched_case_ids
        np.testing.assert_array_equal(before.filtered_diff_map, after.filtered_diff_map)
        np.testing.assert_array_equal(before.filtered_mask, after.filtered_mask)
        # Measure multiple committed additions, not one cold/outlier sample.
        updates_old, updates_new = [], []
        for update in range(5):
            writer.add_from_candidate(product_type="bench#cam=0", reference_hash="ref",
                                      inspection_id=f"update-{update}", candidate=candidate, source_frame=frame)
            old_update, updated_old = measure(lambda: apply(old), 1)
            new_update, pending_new = measure(lambda: apply(new), 1)
            updates_old.append(old_update["mean_ms"])
            updates_new.append(new_update["mean_ms"])
            refresh()
            ready_new = apply(new)
            np.testing.assert_array_equal(updated_old.filtered_diff_map, ready_new.filtered_diff_map)
        rows.append({"cases": count, "steady_before": old_time, "steady_after": new_time,
                     "update_samples": 5,
                     "first_inspection_after_update_before": summary(updates_old),
                     "first_inspection_after_update_after": summary(updates_new),
                     "pending_matches": pending_new.matched_case_ids,
                     "ready_equivalent": True})
    # Isolate repeated full-frame conversions in FP activity measurement.
    old_service = service(legacy_service, root / "fp-old")
    new_service = service(InspectionService, root / "fp-new")
    rng = np.random.default_rng(11)
    image = rng.integers(0, 256, (1024, 1224, 3), dtype=np.uint8)
    mask = rng.integers(0, 2, image.shape, dtype=np.uint8) * 255
    points = [(.1, .1), (.3, .1), (.3, .4), (.1, .4)]
    def activities(instance, reuse):
        maps = (cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)) if reuse else None
        return [instance._measure_zone_activity(image, mask, points, maps) if reuse
                else instance._measure_zone_activity(image, mask, points) for _ in range(20)]
    old_fp, values_old = measure(lambda: activities(old_service, False), runs)
    new_fp, values_new = measure(lambda: activities(new_service, True), runs)
    assert values_old == values_new
    return {"normal_matching": rows, "fp_activity_20_zones_before": old_fp,
            "fp_activity_20_zones_after": new_fp}


def real_frames(root, dataset, legacy_service, frames):
    settings = AnalysisSettings.from_overrides({"use_patchcore": False})
    samples_old, samples_new, results = [], [], []
    matches, control_matches = 0, 0
    for camera in range(10):
        product = f"bench#cam={camera}"
        folder = root / f"camera-{camera}"
        old = service(legacy_service, folder / "old", folder / "normals")
        reference = cv2.imread(str(dataset / f"camera_{camera}/f_0000387/frame.jpg"))
        if reference is None:
            raise FileNotFoundError(f"missing camera {camera} reference/training frame")
        # Explicit synthetic control on a real background, not a labelled
        # machine defect. It ensures the equivalence run exercises suppression.
        current = reference.copy()
        height, width = current.shape[:2]
        current[height//3:height//3+28, width//3:width//3+36] = (255, 0, 255)
        old.set_reference_frame(product, reference)
        training = inspect(old, product, current, settings)
        candidates = extract_defect_candidates(old._last_aligned[product], old._last_diff_maps[product],
                                               old._last_segmentation_masks[product])
        for candidate in sorted(candidates, key=lambda c: c.area, reverse=True)[:5]:
            old._accepted_normals.add_from_candidate(
                product_type=product, reference_hash=old._reference_hashes[product],
                inspection_id="training", candidate=candidate, source_frame=old._last_aligned[product])
        new = service(InspectionService, folder / "new", folder / "normals")
        new.set_reference_frame(product, reference)
        control_old = inspect(old, product, current, settings)
        control_new = inspect(new, product, current, settings)
        check_equal(control_old, control_new)
        assert control_new.learned_normal_matches_count > 0, f"control did not exercise learning, camera={camera}"
        control_matches += control_new.learned_normal_matches_count
        for number in frames:
            image = cv2.imread(str(dataset / f"camera_{camera}/f_{number:07d}/frame.jpg"))
            if image is None:
                raise FileNotFoundError(f"camera {camera}, frame {number}")
            before_time, before = measure(lambda: inspect(old, product, image, settings), 1)
            after_time, after = measure(lambda: inspect(new, product, image, settings), 1)
            check_equal(before, after)
            samples_old.append(before_time["mean_ms"])
            samples_new.append(after_time["mean_ms"])
            matches += after.learned_normal_matches_count
            results.append({"camera": camera, "frame": number, "status": after.status,
                            "score": after.anomaly_score, "matches": after.learned_normal_matches_count,
                            "before_ms": before_time["mean_ms"], "after_ms": after_time["mean_ms"]})
    return {"comparisons": len(results), "exact_maps_scores_verdicts": True,
            "additional_control_pairs": 10, "control_matches": control_matches,
            "training": "synthetic magenta mark on real reference; not ground-truth machine labels",
            "normal_matches": matches, "before": summary(samples_old),
            "after": summary(samples_new), "results": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="e466367f")
    parser.add_argument("--counts", type=int, nargs="+", default=[20, 100])
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--frames", type=int, nargs="+", default=[390, 391, 392, 393, 394, 395])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.runs < 1 or not args.counts or min(args.counts) < 1:
        parser.error("runs and counts must be positive")
    output = args.output.resolve()
    if not output.is_relative_to(PROJECT):
        parser.error("output must stay in the project workspace")
    output.parent.mkdir(parents=True, exist_ok=True)
    logging.disable(logging.CRITICAL)
    cv2.setNumThreads(1)
    cv2.ocl.setUseOpenCL(False)
    legacy = historical("baseline_learning", args.baseline,
                        "analisSurface/backend/app/services/learned_normals.py")
    legacy_service = historical("baseline_learning_service", args.baseline,
                                "analisSurface/backend/app/services/inspection_service.py")
    legacy_service.AcceptedNormalMemory = legacy.AcceptedNormalMemory
    report = {"baseline": args.baseline, "scope": "local Python only; no hardware SLA claim"}
    with tempfile.TemporaryDirectory(prefix="learning-benchmark-", dir=output.parent) as temporary:
        root = Path(temporary)
        try:
            report["synthetic"] = synthetic(root, legacy, legacy_service.InspectionService, args.counts, args.runs)
            if args.dataset:
                report["frames"] = real_frames(root, args.dataset, legacy_service.InspectionService, args.frames)
            output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        finally:
            for snapshot in _owned_snapshots:
                snapshot.close()
            _owned_snapshots.clear()
    print(json.dumps({key: value for key, value in report.items() if key != "frames"}, indent=2))
    if "frames" in report:
        print(json.dumps({key: value for key, value in report["frames"].items() if key != "results"}, indent=2))


if __name__ == "__main__":
    main()
