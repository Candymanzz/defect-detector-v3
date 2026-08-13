"""Benchmark влияния обучаемой памяти нормы на время инспекции.

Синтетический режим не заменяет измерение на кадрах камеры, но позволяет
воспроизводимо сравнить один и тот же pipeline без примеров и с N примерами.

Запуск из ``analisSurface/backend``::

    python scripts/benchmark_learned_normals.py --cases 20 --runs 30
"""

from __future__ import annotations

import argparse
import shutil
import statistics
import sys
import time
import uuid
from pathlib import Path

import cv2
import numpy as np


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.services.inspection_service import InspectionService  # noqa: E402
from app.services.learned_normals import extract_defect_candidates, reference_fingerprint  # noqa: E402


IDENTITY_H = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=int, default=20, help="Количество сохранённых допустимых фрагментов")
    parser.add_argument("--runs", type=int, default=30, help="Количество измеряемых инспекций каждого режима")
    parser.add_argument("--warmup", type=int, default=5, help="Количество прогревочных инспекций")
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--threshold", type=float, default=0.1)
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=BACKEND_DIR / ".benchmark-learned-normals",
        help="Временная рабочая директория внутри workspace",
    )
    parser.add_argument("--keep-work-dir", action="store_true")
    return parser.parse_args()


def synthetic_reference(width: int, height: int) -> np.ndarray:
    x = np.linspace(55, 185, width, dtype=np.float32)
    y = np.linspace(-12, 12, height, dtype=np.float32)[:, None]
    gray = np.clip(x[None, :] + y, 0, 255).astype(np.uint8)
    image = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    cv2.rectangle(image, (width // 12, height // 10), (width * 11 // 12, height * 9 // 10), (110, 110, 110), 2)
    cv2.putText(
        image,
        "DEFECT DETECTOR",
        (width // 7, height // 2),
        cv2.FONT_HERSHEY_SIMPLEX,
        max(0.5, width / 1100.0),
        (145, 145, 145),
        max(1, width // 500),
        cv2.LINE_AA,
    )
    return image


def case_rectangles(width: int, height: int, count: int) -> list[tuple[int, int, int, int]]:
    cols = max(1, int(np.ceil(np.sqrt(count * width / max(1, height)))))
    rows = max(1, int(np.ceil(count / cols)))
    patch_w = max(8, width // 45)
    patch_h = max(6, height // 45)
    rectangles: list[tuple[int, int, int, int]] = []
    for index in range(count):
        col = index % cols
        row = index // cols
        x = int(width * (0.10 + 0.65 * (col + 0.5) / cols))
        y = int(height * (0.10 + 0.65 * (row + 0.5) / rows))
        rectangles.append((x, y, patch_w, patch_h))
    return rectangles


def add_patch(frame: np.ndarray, rect: tuple[int, int, int, int], value: int = 250) -> None:
    x, y, width, height = rect
    frame[y : y + height, x : x + width] = value


def closest_candidate(candidates, rect: tuple[int, int, int, int]):
    target_x = rect[0] + rect[2] * 0.5
    target_y = rect[1] + rect[3] * 0.5
    return min(
        candidates,
        key=lambda candidate: (
            candidate.bbox[0] + candidate.bbox[2] * 0.5 - target_x
        ) ** 2
        + (
            candidate.bbox[1] + candidate.bbox[3] * 0.5 - target_y
        ) ** 2,
    )


def populate_cases(
    service: InspectionService,
    reference: np.ndarray,
    product_type: str,
    rectangles: list[tuple[int, int, int, int]],
) -> None:
    settings = service.get_analysis_settings(product_type)
    ref_hash = reference_fingerprint(reference)
    for index, rect in enumerate(rectangles):
        frame = reference.copy()
        add_patch(frame, rect, value=245 + index % 10)
        diff_map = service._compute_advanced_difference(frame, reference, settings)
        _, mask = service._run_anomaly_model(diff_map, settings)
        candidates = extract_defect_candidates(frame, diff_map, mask)
        if not candidates:
            raise RuntimeError(f"Synthetic accepted case #{index + 1} produced no defect candidate")
        candidate = closest_candidate(candidates, rect)
        service._accepted_normals.add_from_candidate(
            product_type=product_type,
            reference_hash=ref_hash,
            inspection_id=f"benchmark-{index + 1}",
            candidate=candidate,
            note="synthetic benchmark case",
        )


def inspect_once(
    service: InspectionService,
    product_type: str,
    frame: np.ndarray,
    threshold: float,
):
    return service.inspect_frame(
        product_type,
        frame,
        threshold=threshold,
        include_visuals=False,
        include_heatmap_u8=False,
        alignment_h_ref_to_cur=IDENTITY_H,
    )


def measure(
    service: InspectionService,
    product_type: str,
    frame: np.ndarray,
    threshold: float,
    warmup: int,
    runs: int,
) -> tuple[list[float], object]:
    result = None
    for _ in range(max(0, warmup)):
        result = inspect_once(service, product_type, frame, threshold)
    samples: list[float] = []
    for _ in range(max(1, runs)):
        started = time.perf_counter()
        result = inspect_once(service, product_type, frame, threshold)
        samples.append((time.perf_counter() - started) * 1000.0)
    return samples, result


def percentile(samples: list[float], value: float) -> float:
    return float(np.percentile(np.asarray(samples, dtype=np.float64), value))


def summarize(label: str, samples: list[float]) -> None:
    print(
        f"{label:18} mean={statistics.mean(samples):8.2f} ms "
        f"p50={percentile(samples, 50):8.2f} ms "
        f"p95={percentile(samples, 95):8.2f} ms "
        f"min={min(samples):8.2f} ms max={max(samples):8.2f} ms"
    )


def main() -> int:
    args = parse_args()
    if args.cases < 1 or args.runs < 1 or args.width < 80 or args.height < 80:
        raise SystemExit("cases/runs must be positive; width/height must be >= 80")

    work_root = args.work_dir.resolve()
    work_dir = work_root / f"run-{uuid.uuid4()}"
    empty_dir = work_dir / "empty"
    learned_dir = work_dir / "learned"
    empty_dir.mkdir(parents=True)
    learned_dir.mkdir(parents=True)

    product_type = f"benchmark-{uuid.uuid4()}#cam=0"
    reference = synthetic_reference(args.width, args.height)
    rectangles = case_rectangles(args.width, args.height, args.cases)

    baseline = InspectionService(
        learned_normals_dir=empty_dir,
        reviews_dir=empty_dir / "reviews",
        review_limit=1,
    )
    learned = InspectionService(
        learned_normals_dir=learned_dir,
        reviews_dir=learned_dir / "reviews",
        review_limit=1,
    )
    baseline._anomaly_engine = None
    learned._anomaly_engine = None
    baseline.set_reference_frame(product_type, reference)
    learned.set_reference_frame(product_type, reference)
    populate_cases(learned, reference, product_type, rectangles)

    benchmark_frame = reference.copy()
    add_patch(benchmark_frame, rectangles[0], value=245)
    unknown_rect = (int(args.width * 0.82), int(args.height * 0.78), max(12, args.width // 28), max(10, args.height // 28))
    add_patch(benchmark_frame, unknown_rect, value=255)

    baseline_samples, baseline_result = measure(
        baseline, product_type, benchmark_frame, args.threshold, args.warmup, args.runs
    )
    learned_samples, learned_result = measure(
        learned, product_type, benchmark_frame, args.threshold, args.warmup, args.runs
    )

    summarize("Без памяти нормы", baseline_samples)
    summarize(f"С {args.cases} фрагментами", learned_samples)
    baseline_mean = statistics.mean(baseline_samples)
    learned_mean = statistics.mean(learned_samples)
    delta = learned_mean - baseline_mean
    percent = delta / baseline_mean * 100.0 if baseline_mean else 0.0
    print(f"Добавка:           {delta:.2f} ms ({percent:+.1f}%)")
    print(
        "Проверка результата: "
        f"baseline={baseline_result.status}/{baseline_result.anomaly_score:.4f}, "
        f"learned={learned_result.status}/{learned_result.anomaly_score:.4f}, "
        f"matches={learned_result.learned_normal_matches_count}"
    )
    if learned_result.learned_normal_matches_count < 1:
        print("ОШИБКА: известный синтетический фрагмент не распознан")
        return 2
    if learned_result.status != "БРАК":
        print("ОШИБКА: новый неизвестный дефект должен сохранить итог БРАК")
        return 3

    if not args.keep_work_dir:
        shutil.rmtree(work_dir, ignore_errors=True)
        try:
            work_root.rmdir()
        except OSError:
            pass
    else:
        print(f"Временные данные оставлены: {work_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
