"""Бенчмарк и регрессия на РЕАЛЬНЫХ кадрах рабочего компьютера.

Нужны эталон и папка с кадрами (png/jpg/bmp) того же размера, что эталон
(кадры уже после позиционирования, как приходят в analisSurface).

  python benchmarks/real_frames.py bench  --reference ref.png --frames C:\\frames
  python benchmarks/real_frames.py record --reference ref.png --frames C:\\frames --out before.json
  python benchmarks/real_frames.py check  --reference ref.png --frames C:\\frames --out before.json

Опционально: --roi "0.05,0.05;0.95,0.05;0.95,0.95;0.05,0.95" (нормированный полигон),
--scale 0.75 (как inspect_scale), --max-frames 200, --sens 0.5 --threshold 0.45.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import cv2

import _common as c

EXT = {".png", ".jpg", ".jpeg", ".bmp"}


def parse_roi(text: str | None):
    if not text:
        return c.FULL_ROI
    return [tuple(float(v) for v in pair.split(",")) for pair in text.split(";")]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("mode", choices=["bench", "record", "check"])
    ap.add_argument("--reference", required=True)
    ap.add_argument("--frames", required=True)
    ap.add_argument("--out", default="real_frames_baseline.json")
    ap.add_argument("--roi")
    ap.add_argument("--scale", type=float, default=None)
    ap.add_argument("--sens", type=float, default=0.5)
    ap.add_argument("--threshold", type=float, default=0.45)
    ap.add_argument("--max-frames", type=int, default=200)
    ap.add_argument("--rounds", type=int, default=3, help="сколько раз прогнать набор в bench")
    a = ap.parse_args()

    ref = cv2.imread(a.reference)
    if ref is None:
        print("не удалось прочитать эталон:", a.reference)
        return 2
    files = sorted(p for p in pathlib.Path(a.frames).iterdir() if p.suffix.lower() in EXT)[: a.max_frames]
    if not files:
        print("в папке нет кадров:", a.frames)
        return 2
    frames = [(p.name, cv2.imread(str(p))) for p in files]
    frames = [(n, f) for n, f in frames if f is not None]

    svc = c.build_service()
    svc.set_reference_frame("p", ref)
    svc.set_roi_polygon("p", parse_roi(a.roi))
    ov = c.overrides_for(dict(sens=a.sens), a.threshold)

    for _, f in frames[:3]:  # прогрев
        c.inspect_timed(svc, "p", f, ov, a.scale)

    results, times = {}, []
    rounds = a.rounds if a.mode == "bench" else 1
    for _ in range(rounds):
        for name, f in frames:
            r, ms = c.inspect_timed(svc, "p", f, ov, a.scale)
            times.append(ms)
            results[name] = c.digest(svc, "p", r)
    verdicts = [v["status"] for v in results.values()]
    print(f"кадров: {len(frames)}  вердикты: " + ", ".join(f"{s}={verdicts.count(s)}" for s in sorted(set(verdicts))))
    print("время inspect:", c.summarize(times))

    out = pathlib.Path(a.out)
    if a.mode == "record":
        out.write_text(json.dumps(results, indent=1), encoding="utf-8")
        print("записано ->", out)
    elif a.mode == "check":
        return c.compare(c.load_baseline(out), results)
    return 0


if __name__ == "__main__":
    sys.exit(main())
