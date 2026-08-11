"""Проверка реальных изображений из папки palochki без изменения данных приложения.

Скрипт разделяет полноразмерные кадры вёдер и прежние маленькие тестовые
изображения. Временные обученные нормы хранятся только внутри каталога отчёта.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import time
import uuid
from pathlib import Path
from typing import Any

import cv2
import numpy as np


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.services.inspection_service import InspectionService  # noqa: E402


IDENTITY_H = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=Path(r"C:\Users\ShablinskiyM\Desktop\palochki"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=BACKEND_DIR / "test_data" / "palochki_validation",
    )
    parser.add_argument("--threshold", type=float, default=0.25)
    return parser.parse_args()


def load_images(input_dir: Path) -> dict[str, np.ndarray]:
    images: dict[str, np.ndarray] = {}
    for path in sorted(input_dir.glob("*.jpg")):
        image = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Не удалось прочитать {path}")
        images[path.name] = image
    return images


def make_service(runtime_root: Path) -> InspectionService:
    runtime_dir = runtime_root / uuid.uuid4().hex
    service = InspectionService(learned_normals_dir=runtime_dir, review_limit=100)
    # Проверяем тот же классический CV fallback, который используется без PatchCore-модели.
    service._anomaly_engine = None
    return service


def inspect_one(
    service: InspectionService,
    product_type: str,
    filename: str,
    image: np.ndarray,
    threshold: float,
    alignment: str,
) -> tuple[dict[str, Any], Any]:
    started = time.perf_counter()
    result = service.inspect_frame(
        product_type,
        image,
        threshold=threshold,
        include_visuals=False,
        alignment_h_ref_to_cur=IDENTITY_H if alignment == "identity" else None,
    )
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    review = service.get_learning_review(result.inspection_id) if result.inspection_id else None
    row = {
        "filename": filename,
        "status": result.status,
        "score": round(float(result.anomaly_score), 6),
        "raw_score": round(float(result.raw_anomaly_score), 6),
        "matches": int(result.learned_normal_matches_count),
        "adjustment": round(float(result.learned_normal_adjustment), 6),
        "review_defects": len(review["defects"]) if review else 0,
        "elapsed_ms": round(elapsed_ms, 2),
    }
    return row, result


def run_bucket_scenario(
    images: dict[str, np.ndarray],
    runtime_root: Path,
    reference_name: str,
    alignment: str,
    threshold: float,
    learn_other_good: bool,
) -> dict[str, Any]:
    service = make_service(runtime_root)
    product_type = f"palochki-bucket-{reference_name}-{alignment}-{learn_other_good}"
    service.set_reference_frame(product_type, images[reference_name])
    learned_from: str | None = None
    learned_cases = 0

    if learn_other_good:
        other_good = "good2.jpg" if reference_name == "good1.jpg" else "good1.jpg"
        _, seed_result = inspect_one(
            service,
            product_type,
            other_good,
            images[other_good],
            threshold,
            alignment,
        )
        if seed_result.inspection_id:
            review = service.get_learning_review(seed_result.inspection_id)
            for defect in (review or {}).get("defects", []):
                service.accept_review_defect_as_normal(
                    seed_result.inspection_id,
                    defect["id"],
                    note=f"Автопроверка допустимого кадра {other_good}",
                )
                learned_cases += 1
            if learned_cases:
                learned_from = other_good

    rows = []
    for filename in ("good1.jpg", "good2.jpg", "bad1.jpg", "bad2.jpg", "bad21.jpg"):
        row, _ = inspect_one(
            service,
            product_type,
            filename,
            images[filename],
            threshold,
            alignment,
        )
        row["expected"] = "ГОДЕН" if filename.startswith("good") else "БРАК"
        row["correct"] = row["status"] == row["expected"]
        rows.append(row)

    return {
        "reference": reference_name,
        "alignment": alignment,
        "learning_requested": learn_other_good,
        "learned_from": learned_from,
        "learned_cases": learned_cases,
        "correct": sum(bool(row["correct"]) for row in rows),
        "total": len(rows),
        "rows": rows,
    }


def run_legacy_scenario(
    images: dict[str, np.ndarray],
    runtime_root: Path,
    threshold: float,
) -> dict[str, Any]:
    filenames = [
        name
        for name in images
        if name.startswith("Untitled") or name.startswith("light_def")
    ]
    service = make_service(runtime_root)
    product_type = "palochki-legacy"
    service.set_reference_frame(product_type, images["Untitled.jpg"])

    seed_row, seed_result = inspect_one(
        service,
        product_type,
        "Untitled1.jpg",
        images["Untitled1.jpg"],
        threshold,
        "identity",
    )
    learned_cases = 0
    if seed_result.inspection_id:
        review = service.get_learning_review(seed_result.inspection_id)
        for defect in (review or {}).get("defects", []):
            service.accept_review_defect_as_normal(
                seed_result.inspection_id,
                defect["id"],
                note="Автопроверка старого допустимого фрагмента",
            )
            learned_cases += 1

    rows = []
    for filename in sorted(filenames):
        row, _ = inspect_one(
            service,
            product_type,
            filename,
            images[filename],
            threshold,
            "identity",
        )
        rows.append(row)
    return {
        "reference": "Untitled.jpg",
        "learned_from": "Untitled1.jpg",
        "seed_before_learning": seed_row,
        "learned_cases": learned_cases,
        "rows": rows,
    }


def write_bucket_csv(path: Path, scenarios: list[dict[str, Any]]) -> None:
    fieldnames = [
        "reference",
        "alignment",
        "learning_requested",
        "learned_cases",
        "filename",
        "expected",
        "status",
        "correct",
        "score",
        "raw_score",
        "matches",
        "adjustment",
        "review_defects",
        "elapsed_ms",
    ]
    with path.open("w", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        for scenario in scenarios:
            for row in scenario["rows"]:
                writer.writerow(
                    {
                        "reference": scenario["reference"],
                        "alignment": scenario["alignment"],
                        "learning_requested": scenario["learning_requested"],
                        "learned_cases": scenario["learned_cases"],
                        **row,
                    }
                )


def main() -> int:
    args = parse_args()
    input_dir = args.input_dir.resolve()
    output_dir = args.output_dir.resolve()
    if not input_dir.is_dir():
        raise SystemExit(f"Папка не найдена: {input_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    runtime_root = output_dir / ".runtime_norms"
    runtime_root.mkdir(parents=True, exist_ok=True)
    images = load_images(input_dir)

    required = {"good1.jpg", "good2.jpg", "bad1.jpg", "bad2.jpg", "bad21.jpg"}
    missing = sorted(required - images.keys())
    if missing:
        raise SystemExit(f"Не хватает файлов вёдер: {', '.join(missing)}")

    bucket_scenarios = [
        run_bucket_scenario(images, runtime_root, reference, alignment, args.threshold, learn)
        for alignment in ("identity", "auto")
        for reference in ("good1.jpg", "good2.jpg")
        for learn in (False, True)
    ]
    legacy = run_legacy_scenario(images, runtime_root, args.threshold)
    payload = {
        "input_dir": str(input_dir),
        "threshold": args.threshold,
        "image_dimensions": {
            name: {"width": int(image.shape[1]), "height": int(image.shape[0])}
            for name, image in images.items()
        },
        "bucket_scenarios": bucket_scenarios,
        "legacy": legacy,
    }
    (output_dir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    write_bucket_csv(output_dir / "bucket_results.csv", bucket_scenarios)

    # Временные нормы принадлежат только этому прогону; постоянную память приложения не трогаем.
    if runtime_root.parent == output_dir and runtime_root.name == ".runtime_norms":
        shutil.rmtree(runtime_root, ignore_errors=True)

    print(f"Отчёт: {output_dir / 'report.json'}")
    for scenario in bucket_scenarios:
        print(
            scenario["reference"],
            scenario["alignment"],
            "learning=" + str(scenario["learning_requested"]),
            f"{scenario['correct']}/{scenario['total']}",
            "cases=" + str(scenario["learned_cases"]),
        )
        for row in scenario["rows"]:
            print(
                " ",
                row["filename"],
                row["status"],
                f"score={row['score']:.3f}",
                f"matches={row['matches']}",
                f"ms={row['elapsed_ms']:.1f}",
            )
    print("legacy learned cases:", legacy["learned_cases"])
    for row in legacy["rows"]:
        print(
            " ",
            row["filename"],
            row["status"],
            f"score={row['score']:.3f}",
            f"matches={row['matches']}",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
