"""Compare four-process inspection before/after optimization on saved frames.

The timer covers Python processing of ten decoded camera frames, not hardware
capture, Java, HTTP, UI rendering or the reject output. Each child has three
inspection threads; all processes share four logical CPUs on Windows.
"""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time
import types

BACKEND = Path(__file__).resolve().parents[1]
PROJECT = BACKEND.parents[1]
sys.path.insert(0, str(BACKEND))


def limit_cpu():
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.SetProcessAffinityMask.argtypes = (ctypes.c_void_p, ctypes.c_size_t)
        if not kernel.SetProcessAffinityMask(kernel.GetCurrentProcess(), 15):
            raise ctypes.WinError(ctypes.get_last_error())


def worker(args):
    from concurrent.futures import ThreadPoolExecutor
    import cv2
    from app.services.inspection_service import InspectionService
    if args.baseline:
        # Read the old implementation without switching branches or writing
        # source files. Its positioner must also be the old implementation.
        def historical_module(name, relative_path):
            source = subprocess.check_output(["git", "show", f"{args.baseline}:{relative_path}"],
                                             cwd=PROJECT, text=True, encoding="utf-8")
            module = types.ModuleType(name)
            module.__file__ = str(PROJECT / relative_path)
            sys.modules[name] = module
            exec(compile(source, module.__file__, "exec"), module.__dict__)
            return module
        position = historical_module("baseline_positioning", "analisSurface/backend/app/services/inspection_positioning.py")
        service_module = historical_module("baseline_service", "analisSurface/backend/app/services/inspection_service.py")
        service_module.FramePositioner = position.FramePositioner
        InspectionService = service_module.InspectionService
    cv2.setNumThreads(1)
    cv2.ocl.setUseOpenCL(False)
    cameras = list(range(args.worker, 10, 4))
    root = args.output / f"worker{args.worker}"
    root.mkdir(parents=True, exist_ok=True)
    with contextlib.redirect_stdout(sys.stderr):
        service = InspectionService(learned_normals_dir=root / "normals", reviews_dir=root / "reviews", session_wipe=False)
        images = {}
        for cam in cameras:
            for frame in [387, 390, *args.frames]:
                path = args.dataset / f"camera_{cam}/f_{frame:07d}/frame.jpg"
                image = cv2.imread(str(path))
                if image is None:
                    raise FileNotFoundError(path)
                if frame == 387:
                    service.set_reference_frame(f"optimization-camera-{cam}", image)
                else:
                    images[cam, frame] = image
    print(json.dumps({"ready": True}), flush=True)

    def inspect(cam, frame):
        result = service.inspect_frame(
            f"optimization-camera-{cam}", images[cam, frame].copy(), threshold=.45,
            include_visuals=False, include_heatmap_u8=False, inspect_scale_after_align=.75,
            store_learning_review=True, defer_learning_review=args.defer_history,
        )
        return {"camera": cam, "frame": frame, "status": result.status, "score": float(result.anomaly_score),
                **{key: float(getattr(result, key)) for key in
                   ("py_align_ms", "py_diff_ms", "py_anomaly_ms", "py_fp_recheck_ms", "py_total_ms")}}

    with ThreadPoolExecutor(max_workers=3) as executor:
        for line in sys.stdin:
            command = json.loads(line)
            if command.get("quit"):
                service._deferred_learning_reviews._queue.join()
                break
            with contextlib.redirect_stdout(sys.stderr):
                futures = [executor.submit(inspect, cam, command["frame"]) for cam in cameras]
                results = [future.result() for future in futures]
            print(json.dumps(results, ensure_ascii=True), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path(r"C:\Users\ShablinskiyM\Desktop\frames36"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--worker", type=int)
    parser.add_argument("--frames", type=int, nargs="+", default=[391, 392, 393, 394, 395])
    parser.add_argument("--defer-history", action="store_true")
    parser.add_argument("--baseline", help="read baseline service/positioning from this git revision")
    args = parser.parse_args()
    args.output = args.output.resolve()
    os.environ["ANALIS_SURFACE_CONFIG"] = str(PROJECT / "config.exemple/config.yaml")
    os.environ["ANALIS_SURFACE_FILE_LOGGING"] = "0"
    limit_cpu()
    if args.worker is not None:
        worker(args)
        return
    args.output.mkdir(parents=True, exist_ok=True)
    processes, logs = [], []
    try:
        for index in range(4):
            log = (args.output / f"worker{index}.log").open("w", encoding="utf-8")
            logs.append(log)
            command = [sys.executable, str(Path(__file__).resolve()), "--worker", str(index),
                       "--dataset", str(args.dataset), "--output", str(args.output),
                       "--frames", *map(str, args.frames)]
            if args.defer_history:
                command.append("--defer-history")
            if args.baseline:
                command.extend(["--baseline", args.baseline])
            processes.append(subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log,
                                               text=True, cwd=BACKEND,
                                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))
        for index, process in enumerate(processes):
            if not json.loads(process.stdout.readline()).get("ready"):
                raise RuntimeError(f"worker {index} did not start")
        print("Four workers ready", flush=True)
        report = {"revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=PROJECT, text=True).strip(),
                  "deferred_history": args.defer_history, "reference_frame": 387, "cycles": []}
        report["baseline"] = args.baseline
        for frame in [390, *args.frames]:
            started = time.perf_counter()
            for process in processes:
                process.stdin.write(json.dumps({"frame": frame}) + "\n")
                process.stdin.flush()
            calls = []
            for process in processes:
                calls.extend(json.loads(process.stdout.readline()))
            elapsed = round((time.perf_counter() - started) * 1000, 2)
            if frame != 390:
                report["cycles"].append({"frame": frame, "elapsed_ms": elapsed, "calls": calls})
            print(f"frame={frame} ten_camera_python_ms={elapsed}", flush=True)
        times = [row["elapsed_ms"] for row in report["cycles"]]
        report["summary"] = {"mean_ms": round(statistics.mean(times), 2), "max_ms": max(times)}
        report["stages_per_camera_mean_ms"] = {
            key: round(statistics.mean(call[key] for row in report["cycles"] for call in row["calls"]), 2)
            for key in ("py_align_ms", "py_diff_ms", "py_anomaly_ms", "py_fp_recheck_ms", "py_total_ms")}
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"summary": report["summary"], "stages": report["stages_per_camera_mean_ms"]}), flush=True)
    finally:
        for process in processes:
            if process.poll() is None:
                try:
                    process.stdin.write('{"quit":true}\n')
                    process.stdin.flush()
                    process.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired):
                    process.terminate()
                    process.wait(timeout=10)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
