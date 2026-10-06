"""Бенчмарк и регрессия на синтетических кадрах 1224x1024 (без реальных данных).

  python benchmarks/synthetic.py bench              # скорость: медиана/p95
  python benchmarks/synthetic.py record [файл]      # записать эталонные результаты
  python benchmarks/synthetic.py check  [файл]      # сравнить с записанными (побитово)
  python benchmarks/synthetic.py profile            # cProfile: где уходит время
"""

from __future__ import annotations

import json
import pathlib
import sys

import cv2
import numpy as np

import _common as c

DEFAULT_BASELINE = pathlib.Path(__file__).with_name("baseline_synthetic.json")


def make_case(seed: int = 1):
    rng = np.random.default_rng(seed)
    h, w = 1024, 1224
    base = np.full((h, w, 3), 140, np.uint8)
    base += rng.integers(0, 12, base.shape, dtype=np.uint8)
    base = cv2.GaussianBlur(base, (0, 0), 2)
    for i in range(40):
        x, y = int(rng.integers(50, w - 200)), int(rng.integers(50, h - 50))
        cv2.putText(base, "LOT%03d" % i, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (30, 30, 30), 2)
    cv2.rectangle(base, (100, 100), (w - 100, h - 100), (40, 60, 200), 4)
    frames = []
    for k in range(4):
        f = cv2.add(base.copy(), rng.integers(0, 6, base.shape, dtype=np.uint8))
        if k >= 1:
            cv2.line(f, (300 + k * 50, 300), (500 + k * 60, 340 + k * 10), (90, 90, 90), 2)
        if k >= 2:
            cv2.circle(f, (800, 600), 6 + k, (20, 20, 20), -1)
        if k == 3:
            f = cv2.convertScaleAbs(f, alpha=1.0, beta=25)
        frames.append(f)
    return base.copy(), frames


def prepared_service():
    svc = c.build_service()
    ref, frames = make_case()
    svc.set_reference_frame("p", ref)
    svc.set_roi_polygon("p", c.FULL_ROI)
    return svc, frames


def run_matrix():
    svc, frames = prepared_service()
    results, times = {}, []
    for si, s in enumerate(c.SETTINGS):
        ov = c.overrides_for(s)
        for fi, f in enumerate(frames):
            for scale in (None, 0.75):
                r, ms = c.inspect_timed(svc, "p", f, ov, scale)
                times.append(ms)
                results[f"{si}/{fi}/{scale}"] = c.digest(svc, "p", r)
    return results, times


def bench(rounds: int = 12):
    svc, frames = prepared_service()
    ov = c.overrides_for(c.SETTINGS[0])
    for f in frames:  # прогрев (кэши, JIT, аллокатор)
        c.inspect_timed(svc, "p", f, ov, None)
    times = [c.inspect_timed(svc, "p", f, ov, None)[1] for _ in range(rounds) for f in frames]
    print("синтетика, ROI, scale=1.0:", c.summarize(times))


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "bench"
    path = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_BASELINE
    if mode == "bench":
        bench()
    elif mode == "record":
        results, times = run_matrix()
        path.write_text(json.dumps(results, indent=1), encoding="utf-8")
        print(f"записано {len(results)} прогонов -> {path}\n", c.summarize(times))
    elif mode == "check":
        results, times = run_matrix()
        print(c.summarize(times))
        return c.compare(c.load_baseline(path), results)
    elif mode == "profile":
        import cProfile
        import pstats
        pr = cProfile.Profile()
        pr.enable()
        _, times = run_matrix()
        pr.disable()
        print(c.summarize(times))
        pstats.Stats(pr).sort_stats("tottime").print_stats(30)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
