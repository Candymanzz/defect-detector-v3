"""Offline numerical equivalence and small-translation checks on real frames.

Loads only the baseline difference method from git, without switching branches,
starting services, changing operator settings or loading a detector model.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import types

import cv2
import numpy as np

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_positioning import FramePositioner
from app.services.inspection_service import InspectionService


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path(r"C:\Users\ShablinskiyM\Desktop\frames36"))
    parser.add_argument("--baseline", default="4616cb7a")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cv2.setNumThreads(1)
    source = subprocess.check_output([
        "git", "show", f"{args.baseline}:analisSurface/backend/app/services/inspection_service.py",
    ], cwd=BACKEND, text=True, encoding="utf-8")
    module = types.ModuleType("baseline_inspection_service")
    module.__file__ = str(BACKEND / "app/services/inspection_service.py")
    sys.modules[module.__name__] = module
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    old = module.InspectionService.__new__(module.InspectionService)
    new = InspectionService.__new__(InspectionService)
    settings = AnalysisSettings(use_patchcore=False)
    positioner = FramePositioner()
    report = {"baseline": args.baseline, "difference_checks": [], "known_shifts": []}
    for camera in range(10):
        root = args.dataset / f"camera_{camera}"
        ref = cv2.imread(str(root / "f_0000387/frame.jpg"))
        if ref is None:
            raise FileNotFoundError(root)
        for dx, dy in [(2, -1), (-5, 3), (.8, -1.4), (10, 0)]:
            current = cv2.warpAffine(ref, np.float32([[1, 0, dx], [0, 1, dy]]),
                                     (ref.shape[1], ref.shape[0]), borderMode=cv2.BORDER_REPLICATE)
            result = positioner.position(current, ref, product_type=str(camera))
            estimated = [0, 0] if result.matrix is None else result.matrix[:, 2].tolist()
            error = float(np.max(np.abs(np.array(estimated) + [dx, dy])))
            inner = (slice(40, -40), slice(40, -40))
            before = float(cv2.absdiff(current, ref)[inner].mean())
            after = float(cv2.absdiff(result.aligned, ref)[inner].mean())
            report["known_shifts"].append({"camera": camera, "shift": [dx, dy], "estimated": estimated,
                                           "max_error_px": error, "before_mae": before, "after_mae": after,
                                           "diagnostics": result.diagnostics})
        for frame in range(391, 396):
            current = cv2.imread(str(root / f"f_{frame:07d}/frame.jpg"))
            aligned = positioner.position(current, ref, product_type=str(camera)).aligned
            # Both algorithms receive exactly the same aligned pixels; this
            # isolates reference caching from the intentional positioning change.
            size = (round(ref.shape[1] * .75), round(ref.shape[0] * .75))
            aligned = cv2.resize(aligned, size, interpolation=cv2.INTER_LINEAR)
            reference = cv2.resize(ref, size, interpolation=cv2.INTER_LINEAR)
            before = old._compute_advanced_difference(aligned, reference, settings, vertical_compensation=True)
            for warm in [False, True]:
                after = new._compute_advanced_difference(aligned, reference, settings, vertical_compensation=True)
                delta = cv2.absdiff(before, after)
                report["difference_checks"].append({"camera": camera, "frame": frame, "warm": warm,
                                                    "changed_values": int(np.count_nonzero(delta)),
                                                    "max_difference": int(delta.max())})
    report["summary"] = {
        "difference_maps_checked": len(report["difference_checks"]),
        "difference_maps_identical": sum(row["changed_values"] == 0 for row in report["difference_checks"]),
        "known_translations_checked": len(report["known_shifts"]),
        "worst_shift_error_px": max(row["max_error_px"] for row in report["known_shifts"]),
        "all_translations_improved": all(row["after_mae"] < row["before_mae"] for row in report["known_shifts"]),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"]), flush=True)
    if report["summary"]["difference_maps_identical"] != 100 or not report["summary"]["all_translations_improved"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
