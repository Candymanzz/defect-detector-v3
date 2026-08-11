"""Создать и автоматически проверить датасет обучаемой нормы на ведре.

Генеративная модель создаёт только чистый исходный кадр. Все варианты ниже
строятся детерминированно поверх одной копии, поэтому любые отличия от эталона
известны заранее и не маскируются случайной перерисовкой сцены.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import cv2
import numpy as np


BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.services.inspection_service import InspectionService  # noqa: E402


IDENTITY_H = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]


@dataclass(frozen=True)
class DatasetCase:
    filename: str
    expected_status: str
    description: str
    draw: Callable[[np.ndarray], None]


def parse_args() -> argparse.Namespace:
    default_dir = BACKEND_DIR / "test_data" / "bucket_learning"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=default_dir / "source_generated_bucket.png")
    parser.add_argument("--output-dir", type=Path, default=default_dir)
    parser.add_argument("--threshold", type=float, default=0.25)
    parser.add_argument("--size", type=int, default=640)
    return parser.parse_args()


def draw_l_mark(
    image: np.ndarray,
    origin: tuple[int, int],
    *,
    scale: float = 1.0,
    color: tuple[int, int, int] = (35, 58, 92),
    thickness: int = 4,
    fragmented: bool = False,
    horizontal_scale: float = 1.0,
) -> None:
    x, y = origin
    elbow = (round(x - 14 * scale), round(y + 38 * scale))
    end = (round(elbow[0] + 72 * scale * horizontal_scale), round(elbow[1] + 5 * scale))
    width = max(1, round(thickness * max(0.55, scale)))
    halo = tuple(min(255, channel + 28) for channel in color)
    if fragmented:
        split_a = (round(x - 6 * scale), round(y + 17 * scale))
        split_b = (round(x - 10 * scale), round(y + 28 * scale))
        segments = [np.array([(x, y), split_a]), np.array([split_b, elbow, end])]
    else:
        segments = [np.array([(x, y), elbow, end])]
    for points in segments:
        cv2.polylines(image, [points], False, halo, width + 2, cv2.LINE_AA)
        cv2.polylines(image, [points], False, color, width, cv2.LINE_AA)


def draw_x(image: np.ndarray, center: tuple[int, int], radius: int = 20) -> None:
    x, y = center
    color = (28, 48, 78)
    cv2.line(image, (x - radius, y - radius), (x + radius, y + radius), color, 5, cv2.LINE_AA)
    cv2.line(image, (x + radius, y - radius), (x - radius, y + radius), color, 5, cv2.LINE_AA)


def draw_blob(image: np.ndarray, center: tuple[int, int], radius: int = 18) -> None:
    cv2.circle(image, center, radius + 4, (60, 82, 105), -1, cv2.LINE_AA)
    cv2.circle(image, center, radius, (25, 45, 72), -1, cv2.LINE_AA)


def draw_crack(image: np.ndarray, points: list[tuple[int, int]], thickness: int = 4) -> None:
    cv2.polylines(image, [np.asarray(points, dtype=np.int32)], False, (22, 38, 60), thickness, cv2.LINE_AA)


def draw_dots(image: np.ndarray, centers: list[tuple[int, int]]) -> None:
    for index, center in enumerate(centers):
        cv2.circle(image, center, 2 + index % 2, (25, 50, 85), -1, cv2.LINE_AA)


def case_definitions() -> list[DatasetCase]:
    cases: list[DatasetCase] = []

    def add_good(name: str, description: str, painter: Callable[[np.ndarray], None]) -> None:
        cases.append(DatasetCase(name, "ГОДЕН", description, painter))

    def add_bad(name: str, description: str, painter: Callable[[np.ndarray], None]) -> None:
        cases.append(DatasetCase(name, "БРАК", description, painter))

    add_good("good_01_shift_right.png", "Та же L-форма справа", lambda im: draw_l_mark(im, (425, 375)))
    add_good("good_02_shift_low.png", "Та же L-форма ниже", lambda im: draw_l_mark(im, (330, 480)))
    add_good("good_03_small_70.png", "Уменьшение до 70%", lambda im: draw_l_mark(im, (390, 455), scale=0.70))
    add_good("good_04_small_45.png", "Уменьшение до 45%", lambda im: draw_l_mark(im, (275, 500), scale=0.45))
    add_good("good_05_tiny_25.png", "Уменьшение до 25%", lambda im: draw_l_mark(im, (430, 500), scale=0.25))
    add_good("good_06_fragmented.png", "Разорванная порогом L-форма", lambda im: draw_l_mark(im, (385, 425), scale=0.75, fragmented=True))
    add_good("good_07_short_arm.png", "Более короткое горизонтальное плечо", lambda im: draw_l_mark(im, (285, 445), scale=0.80, horizontal_scale=0.65))
    add_good("good_08_thin.png", "Более тонкая отметка", lambda im: draw_l_mark(im, (420, 420), scale=0.85, thickness=2))
    add_good("good_09_lighter.png", "Более светлая отметка", lambda im: draw_l_mark(im, (300, 390), color=(62, 82, 110)))
    add_good("good_10_two_marks.png", "Две похожие отметки", lambda im: (draw_l_mark(im, (265, 405), scale=0.55), draw_l_mark(im, (430, 470), scale=0.40)))
    add_good("good_11_three_marks.png", "Три похожие отметки", lambda im: (draw_l_mark(im, (245, 390), scale=0.45), draw_l_mark(im, (350, 455), scale=0.35), draw_l_mark(im, (455, 420), scale=0.30)))
    add_good("good_12_fragmented_small.png", "Маленькая разорванная отметка", lambda im: draw_l_mark(im, (355, 490), scale=0.42, fragmented=True))

    add_bad("bad_01_large_l.png", "Похожая форма существенно крупнее нормы", lambda im: draw_l_mark(im, (380, 335), scale=1.45, thickness=6))
    add_bad("bad_02_round_blob.png", "Круглое ржавое пятно", lambda im: draw_blob(im, (360, 425), 20))
    add_bad("bad_03_x_scratch.png", "X-образная царапина", lambda im: draw_x(im, (360, 425), 24))
    add_bad("bad_04_vertical_crack.png", "Длинная вертикальная трещина", lambda im: draw_crack(im, [(340, 345), (335, 390), (345, 440), (338, 500)], 5))
    add_bad("bad_05_horizontal_crack.png", "Длинная ломаная трещина", lambda im: draw_crack(im, [(220, 430), (285, 420), (350, 435), (430, 418)], 5))
    add_bad("bad_06_dent.png", "Контрастная вмятина", lambda im: cv2.ellipse(im, (360, 430), (42, 22), -8, 0, 360, (48, 62, 75), -1, cv2.LINE_AA))
    add_bad("bad_07_dot_cluster.png", "Кластер мелких точек", lambda im: draw_dots(im, [(320, 410), (332, 418), (346, 405), (358, 422), (370, 408), (382, 420)]))
    add_bad("bad_08_normal_plus_x.png", "Допустимая L-форма плюс новый X-дефект", lambda im: (draw_l_mark(im, (260, 430), scale=0.55), draw_x(im, (420, 430), 20)))
    return cases


def write_image(path: Path, image: np.ndarray) -> None:
    if not cv2.imwrite(str(path), image):
        raise RuntimeError(f"Could not write {path}")


def build_contact_sheet(output_dir: Path, rows: list[dict]) -> Path:
    thumb_w, thumb_h = 240, 240
    label_h = 54
    cols = 4
    sheet_rows = (len(rows) + cols - 1) // cols
    sheet = np.full((sheet_rows * (thumb_h + label_h), cols * thumb_w, 3), 24, dtype=np.uint8)
    for index, entry in enumerate(rows):
        image = cv2.imread(str(output_dir / entry["filename"]), cv2.IMREAD_COLOR)
        if image is None:
            continue
        thumb = cv2.resize(image, (thumb_w, thumb_h), interpolation=cv2.INTER_AREA)
        row, col = divmod(index, cols)
        x0, y0 = col * thumb_w, row * (thumb_h + label_h)
        sheet[y0 : y0 + thumb_h, x0 : x0 + thumb_w] = thumb
        passed = bool(entry["passed"])
        color = (70, 210, 110) if passed else (60, 80, 245)
        cv2.putText(sheet, entry["filename"][:30], (x0 + 6, y0 + thumb_h + 19), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (225, 225, 225), 1, cv2.LINE_AA)
        summary = f"exp={entry['expected_status']} got={entry['actual_status']} m={entry['matches']}"
        cv2.putText(sheet, summary, (x0 + 6, y0 + thumb_h + 41), cv2.FONT_HERSHEY_SIMPLEX, 0.40, color, 1, cv2.LINE_AA)
    path = output_dir / "contact_sheet.jpg"
    write_image(path, sheet)
    return path


def main() -> int:
    args = parse_args()
    source = args.source.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    original = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if original is None:
        raise SystemExit(f"Source image not found or invalid: {source}")
    reference = cv2.resize(original, (args.size, args.size), interpolation=cv2.INTER_AREA)
    reference_path = output_dir / "reference.png"
    write_image(reference_path, reference)

    seed = reference.copy()
    draw_l_mark(seed, (270, 365), scale=1.0)
    seed_path = output_dir / "learn_seed.png"
    write_image(seed_path, seed)

    definitions = case_definitions()
    for definition in definitions:
        frame = reference.copy()
        definition.draw(frame)
        write_image(output_dir / definition.filename, frame)

    runtime_dir = output_dir / ".runtime_norms"
    if runtime_dir.exists():
        if runtime_dir.parent != output_dir or runtime_dir.name != ".runtime_norms":
            raise RuntimeError(f"Refusing to remove unexpected directory: {runtime_dir}")
        shutil.rmtree(runtime_dir)
    service = InspectionService(learned_normals_dir=runtime_dir, review_limit=50)
    service._anomaly_engine = None
    product_type = "bucket-generated-test"
    service.set_reference_frame(product_type, reference)

    seed_result = service.inspect_frame(
        product_type,
        seed,
        threshold=args.threshold,
        include_visuals=False,
        alignment_h_ref_to_cur=IDENTITY_H,
    )
    if seed_result.inspection_id is None:
        raise RuntimeError(f"Learning seed did not produce a review: {seed_result.status}/{seed_result.anomaly_score}")
    review = service.get_learning_review(seed_result.inspection_id)
    if not review or not review["defects"]:
        raise RuntimeError("Learning seed review has no operator-visible defect")
    seed_defect = max(review["defects"], key=lambda item: item["area"])
    service.accept_review_defect_as_normal(seed_result.inspection_id, seed_defect["id"], note="generated bucket seed")

    rows: list[dict] = []
    baseline = service.inspect_frame(
        product_type,
        reference,
        threshold=args.threshold,
        include_visuals=False,
        alignment_h_ref_to_cur=IDENTITY_H,
    )
    rows.append(
        {
            "filename": "reference.png",
            "description": "Чистый эталон",
            "expected_status": "ГОДЕН",
            "actual_status": baseline.status,
            "raw_score": baseline.raw_anomaly_score,
            "final_score": baseline.anomaly_score,
            "matches": baseline.learned_normal_matches_count,
            "passed": baseline.status == "ГОДЕН",
        }
    )
    for definition in definitions:
        frame = cv2.imread(str(output_dir / definition.filename), cv2.IMREAD_COLOR)
        result = service.inspect_frame(
            product_type,
            frame,
            threshold=args.threshold,
            include_visuals=False,
            alignment_h_ref_to_cur=IDENTITY_H,
        )
        rows.append(
            {
                "filename": definition.filename,
                "description": definition.description,
                "expected_status": definition.expected_status,
                "actual_status": result.status,
                "raw_score": result.raw_anomaly_score,
                "final_score": result.anomaly_score,
                "matches": result.learned_normal_matches_count,
                "passed": result.status == definition.expected_status,
            }
        )

    report_json = output_dir / "report.json"
    report_json.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    report_csv = output_dir / "report.csv"
    with report_csv.open("w", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    passed_count = sum(1 for row in rows if row["passed"])
    report_md = output_dir / "REPORT.md"
    lines = [
        "# Автоматическая проверка обучаемой нормы на ведре",
        "",
        f"Итог: **{passed_count}/{len(rows)}** сценариев совпали с ожиданием.",
        "",
        "| Файл | Ожидание | Факт | Raw | Final | Совпадений | Результат |",
        "|---|---|---|---:|---:|---:|---|",
    ]
    for row in rows:
        lines.append(
            f"| `{row['filename']}` | {row['expected_status']} | {row['actual_status']} | "
            f"{row['raw_score']:.4f} | {row['final_score']:.4f} | {row['matches']} | "
            f"{'OK' if row['passed'] else 'FAIL'} |"
        )
    report_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    contact_sheet = build_contact_sheet(output_dir, rows)

    if runtime_dir.exists():
        shutil.rmtree(runtime_dir)
    print(f"Dataset: {output_dir}")
    print(f"Seed: {seed_path.name} status={seed_result.status} score={seed_result.anomaly_score:.4f}")
    print(f"Report: {report_md}")
    print(f"Contact sheet: {contact_sheet}")
    print(f"Passed: {passed_count}/{len(rows)}")
    for row in rows:
        if not row["passed"]:
            print(
                f"FAIL {row['filename']}: expected={row['expected_status']} "
                f"actual={row['actual_status']} raw={row['raw_score']:.4f} "
                f"final={row['final_score']:.4f} matches={row['matches']}"
            )
    return 0 if passed_count == len(rows) else 2


if __name__ == "__main__":
    raise SystemExit(main())
