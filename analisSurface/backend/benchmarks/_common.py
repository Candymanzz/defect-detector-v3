"""Общие функции бенчмарков: сборка сервиса в изолированных временных папках."""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys
import tempfile
import time

import numpy as np

BACKEND_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.services.analysis_settings_presets import expand_merged  # noqa: E402
from app.services.inspection_service import InspectionService  # noqa: E402

# (sensitivity 0..1, noise, scratch, edge, text, preprocess, far_edge) — как с фронта (0..100)
SETTINGS = [
    dict(sens=0.5),
    dict(sens=0.9, ns=80, sc=90, ed=20, tx=60, pre=90, fe=90),
    dict(sens=0.1, ns=10, sc=10, ed=90, tx=10, pre=10, fe=10),
]
FULL_ROI = [(0.05, 0.05), (0.95, 0.05), (0.95, 0.95), (0.05, 0.95)]


def build_service() -> InspectionService:
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="analis-bench-"))
    svc = InspectionService(
        learned_normals_dir=tmp / "normals", reviews_dir=tmp / "reviews", session_wipe=True
    )
    svc._anomaly_engine = None  # бенчмарк меряет эвристику; PatchCore не нужен
    svc._fp_zones_file = tmp / "fp_zones.json"
    svc._fp_crops_dir = tmp / "fp_crops"
    svc._fp_zones_generation = None
    svc._load_fp_zones()
    svc._analysis_settings_file = tmp / "analysis_settings.json"
    return svc


def overrides_for(s: dict, threshold: float = 0.45) -> dict:
    return expand_merged(
        threshold, s["sens"], s.get("ns", 50), s.get("sc", 50), s.get("ed", 50),
        s.get("tx", 50), s.get("pre", 50), s.get("fe", 50),
    )


def digest(svc: InspectionService, product: str, result) -> dict:
    diff = svc._last_diff_maps.get(product)
    mask = svc._last_segmentation_masks.get(product)
    return {
        "status": str(result.status),
        "score": repr(float(result.anomaly_score)),
        "main_roi_score": repr(float(result.main_roi_score)),
        "diff": hashlib.md5(diff.tobytes()).hexdigest() if diff is not None else None,
        "mask": hashlib.md5(mask.tobytes()).hexdigest() if mask is not None else None,
    }


def inspect_timed(svc, product, frame, overrides, scale):
    t = time.perf_counter()
    result = svc.inspect_frame(
        product, frame.copy(), include_visuals=False,
        temporary_analysis_overrides=overrides,
        inspect_scale_after_align=scale, store_learning_review=False,
    )
    return result, (time.perf_counter() - t) * 1000.0


def summarize(times: list[float]) -> str:
    a = np.asarray(times)
    return (f"n={a.size}  median={np.median(a):.1f}  mean={a.mean():.1f}  "
            f"p95={np.percentile(a, 95):.1f}  min={a.min():.1f} ms")


def load_baseline(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def compare(baseline: dict, current: dict) -> int:
    bad = [k for k in baseline if baseline[k] != current.get(k)]
    if bad:
        print(f"РАЗЛИЧИЯ в {len(bad)} из {len(baseline)}:")
        for k in bad[:10]:
            print(" ", k, "\n    было:", baseline[k], "\n    стало:", current.get(k))
        return 1
    print(f"ИДЕНТИЧНО: {len(baseline)} прогонов совпали (статус, score, diff, маска)")
    return 0
