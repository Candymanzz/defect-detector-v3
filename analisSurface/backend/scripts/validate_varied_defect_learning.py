"""Проверить обучаемые исключения на разных формах и размерах дефектов.

Скрипт не меняет настройки инспекции. Он рисует детерминированные дефекты на
одном чистом изображении изделия, по очереди сохраняет каждую форму как норму
и строит матрицу: та же форма должна исключаться, другая — оставлять БРАК.
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
CORE_COLOR = (25, 48, 78)
LIGHT_COLOR = (72, 88, 105)


@dataclass(frozen=True)
class DefectFamily:
    name: str
    label: str
    draw: Callable[[np.ndarray, tuple[int, int], float, tuple[int, int, int]], None]


def _scaled_points(
    center: tuple[int, int],
    points: list[tuple[float, float]],
    scale: float,
) -> np.ndarray:
    cx, cy = center
    return np.asarray(
        [(round(cx + x * scale), round(cy + y * scale)) for x, y in points],
        dtype=np.int32,
    )


def _stroke(
    image: np.ndarray,
    points: np.ndarray,
    scale: float,
    color: tuple[int, int, int],
    *,
    closed: bool = False,
    thickness: int = 4,
) -> None:
    width = max(1, round(thickness * max(0.55, scale)))
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.polylines(image, [points], closed, halo, width + 3, cv2.LINE_AA)
    cv2.polylines(image, [points], closed, color, width, cv2.LINE_AA)


def draw_circle(image: np.ndarray, center: tuple[int, int], scale: float, color: tuple[int, int, int]) -> None:
    radius = max(2, round(18 * scale))
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.circle(image, center, radius + 3, halo, -1, cv2.LINE_AA)
    cv2.circle(image, center, radius, color, -1, cv2.LINE_AA)


def draw_ellipse(image: np.ndarray, center: tuple[int, int], scale: float, color: tuple[int, int, int]) -> None:
    axes = (max(3, round(31 * scale)), max(2, round(13 * scale)))
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.ellipse(image, center, (axes[0] + 3, axes[1] + 3), -18, 0, 360, halo, -1, cv2.LINE_AA)
    cv2.ellipse(image, center, axes, -18, 0, 360, color, -1, cv2.LINE_AA)


def draw_rectangle_chip(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    box = cv2.boxPoints((center, (44 * scale, 23 * scale), 12)).astype(np.int32)
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.polylines(image, [box], True, halo, max(5, round(8 * scale)), cv2.LINE_AA)
    cv2.fillPoly(image, [box], color, cv2.LINE_AA)


def draw_triangle_chip(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(center, [(-25, 18), (2, -24), (28, 20)], scale)
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.polylines(image, [points], True, halo, max(5, round(8 * scale)), cv2.LINE_AA)
    cv2.fillPoly(image, [points], color, cv2.LINE_AA)


def draw_irregular_blob(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(
        center,
        [(-28, -4), (-15, -22), (4, -17), (25, -25), (31, -2), (18, 19), (-2, 25), (-23, 15)],
        scale,
    )
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.polylines(image, [points], True, halo, max(5, round(8 * scale)), cv2.LINE_AA)
    cv2.fillPoly(image, [points], color, cv2.LINE_AA)


def draw_horizontal_scratch(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(center, [(-48, 2), (-18, -2), (12, 3), (49, -1)], scale)
    _stroke(image, points, scale, color, thickness=4)


def draw_vertical_scratch(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(center, [(1, -50), (-2, -20), (3, 12), (-1, 51)], scale)
    _stroke(image, points, scale, color, thickness=4)


def draw_diagonal_scratch(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(center, [(-42, -35), (-17, -13), (13, 9), (43, 36)], scale)
    _stroke(image, points, scale, color, thickness=4)


def draw_zigzag_crack(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    points = _scaled_points(center, [(-42, -35), (-18, -17), (-30, 1), (2, 11), (-8, 32), (38, 39)], scale)
    _stroke(image, points, scale, color, thickness=4)


def draw_arc(image: np.ndarray, center: tuple[int, int], scale: float, color: tuple[int, int, int]) -> None:
    axes = (max(5, round(35 * scale)), max(4, round(24 * scale)))
    width = max(1, round(4 * max(0.55, scale)))
    halo = tuple(min(255, channel + 28) for channel in color)
    cv2.ellipse(image, center, axes, 10, 25, 285, halo, width + 3, cv2.LINE_AA)
    cv2.ellipse(image, center, axes, 10, 25, 285, color, width, cv2.LINE_AA)


def draw_x_scratch(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    first = _scaled_points(center, [(-31, -31), (31, 31)], scale)
    second = _scaled_points(center, [(31, -31), (-31, 31)], scale)
    _stroke(image, first, scale, color, thickness=4)
    _stroke(image, second, scale, color, thickness=4)


def draw_dot_cluster(
    image: np.ndarray,
    center: tuple[int, int],
    scale: float,
    color: tuple[int, int, int],
) -> None:
    offsets = [(-24, -12), (-10, 7), (2, -10), (14, 9), (27, -5), (1, 20)]
    halo = tuple(min(255, channel + 28) for channel in color)
    for index, (dx, dy) in enumerate(offsets):
        point = (round(center[0] + dx * scale), round(center[1] + dy * scale))
        radius = max(1, round((3 + index % 2) * max(0.6, scale)))
        cv2.circle(image, point, radius + 2, halo, -1, cv2.LINE_AA)
        cv2.circle(image, point, radius, color, -1, cv2.LINE_AA)


FAMILIES = [
    DefectFamily("circle", "Круглое пятно", draw_circle),
    DefectFamily("ellipse", "Овальная вмятина", draw_ellipse),
    DefectFamily("rectangle_chip", "Прямоугольный скол", draw_rectangle_chip),
    DefectFamily("triangle_chip", "Треугольный скол", draw_triangle_chip),
    DefectFamily("irregular_blob", "Неровное пятно", draw_irregular_blob),
    DefectFamily("horizontal_scratch", "Горизонтальная царапина", draw_horizontal_scratch),
    DefectFamily("vertical_scratch", "Вертикальная царапина", draw_vertical_scratch),
    DefectFamily("diagonal_scratch", "Диагональная царапина", draw_diagonal_scratch),
    DefectFamily("zigzag_crack", "Ломаная трещина", draw_zigzag_crack),
    DefectFamily("arc", "Дугообразная царапина", draw_arc),
    DefectFamily("x_scratch", "X-образная царапина", draw_x_scratch),
    DefectFamily("dot_cluster", "Россыпь точек", draw_dot_cluster),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=BACKEND_DIR / "test_data" / "bucket_learning" / "source_generated_bucket.png",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=BACKEND_DIR / "test_data" / "varied_defect_learning",
    )
    parser.add_argument("--threshold", type=float, default=0.25)
    parser.add_argument("--size", type=int, default=640)
    return parser.parse_args()


def make_frame(
    reference: np.ndarray,
    family: DefectFamily,
    *,
    center: tuple[int, int],
    scale: float = 1.0,
    color: tuple[int, int, int] = CORE_COLOR,
    second_center: tuple[int, int] | None = None,
) -> np.ndarray:
    frame = reference.copy()
    family.draw(frame, center, scale, color)
    if second_center is not None:
        family.draw(frame, second_center, scale * 0.82, color)
    return frame


def write_image(path: Path, image: np.ndarray) -> None:
    if not cv2.imwrite(str(path), image):
        raise RuntimeError(f"Could not write image: {path}")


def build_contact_sheet(output_dir: Path, image_entries: list[tuple[str, str]]) -> Path:
    thumb = 150
    label_height = 28
    columns = 5
    rows = (len(image_entries) + columns - 1) // columns
    sheet = np.full((rows * (thumb + label_height), columns * thumb, 3), 24, dtype=np.uint8)
    for index, (filename, label) in enumerate(image_entries):
        image = cv2.imread(str(output_dir / filename), cv2.IMREAD_COLOR)
        if image is None:
            continue
        preview = cv2.resize(image, (thumb, thumb), interpolation=cv2.INTER_AREA)
        row, column = divmod(index, columns)
        x = column * thumb
        y = row * (thumb + label_height)
        sheet[y : y + thumb, x : x + thumb] = preview
        cv2.putText(
            sheet,
            label[:23],
            (x + 4, y + thumb + 19),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.38,
            (225, 225, 225),
            1,
            cv2.LINE_AA,
        )
    path = output_dir / "contact_sheet.jpg"
    write_image(path, sheet)
    return path


def inspect_with_diagnostics(
    service: InspectionService,
    product_type: str,
    frame: np.ndarray,
    threshold: float,
) -> tuple[object, list[float]]:
    result = service.inspect_frame(
        product_type,
        frame,
        threshold=threshold,
        include_visuals=False,
        alignment_h_ref_to_cur=IDENTITY_H,
    )
    learned = service._accepted_normals.apply(
        product_type=product_type,
        reference_hash=service._reference_hashes[product_type],
        aligned=frame,
        diff_map=service._last_diff_maps[product_type],
        segmentation_mask=service._last_segmentation_masks[product_type],
    )
    similarities = [
        float(candidate.similarity)
        for candidate in learned.candidates
        if candidate.similarity is not None
    ]
    return result, similarities


def main() -> int:
    args = parse_args()
    source = args.source.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    original = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if original is None:
        raise SystemExit(f"Source image not found or invalid: {source}")
    reference = cv2.resize(original, (args.size, args.size), interpolation=cv2.INTER_AREA)
    write_image(output_dir / "reference.png", reference)

    center_seed = (285, 420)
    # Похожий дефект остаётся в локальной окрестности сохранённого места.
    # Более дальний перенос теперь намеренно не является допустимой нормой.
    center_shift = (350, 450)
    image_entries: list[tuple[str, str]] = [("reference.png", "reference")]
    variants_by_family: dict[str, dict[str, np.ndarray]] = {}
    for family in FAMILIES:
        variants = {
            "seed": make_frame(reference, family, center=center_seed),
            "shift": make_frame(reference, family, center=center_shift),
            "small_70": make_frame(reference, family, center=center_shift, scale=0.70),
            "small_45": make_frame(reference, family, center=center_shift, scale=0.45),
            "lighter": make_frame(reference, family, center=center_shift, scale=0.90, color=LIGHT_COLOR),
            "two": make_frame(
                reference,
                family,
                center=(245, 395),
                second_center=(335, 455),
                scale=0.68,
            ),
            "large_145": make_frame(reference, family, center=center_shift, scale=1.45),
        }
        variants_by_family[family.name] = variants
        for variant_name, frame in variants.items():
            filename = f"{family.name}__{variant_name}.png"
            write_image(output_dir / filename, frame)
            image_entries.append((filename, f"{family.name}/{variant_name}"))

    runtime_dir = output_dir / ".runtime_norms"
    if runtime_dir.exists():
        if runtime_dir.parent != output_dir or runtime_dir.name != ".runtime_norms":
            raise RuntimeError(f"Refusing to remove unexpected directory: {runtime_dir}")
        shutil.rmtree(runtime_dir)

    service = InspectionService(learned_normals_dir=runtime_dir, review_limit=100)
    service._anomaly_engine = None
    rows: list[dict] = []
    learning_rows: list[dict] = []

    for family in FAMILIES:
        product_type = f"varied-{family.name}"
        service.set_reference_frame(product_type, reference)
        seed = service.inspect_frame(
            product_type,
            variants_by_family[family.name]["seed"],
            threshold=args.threshold,
            include_visuals=False,
            alignment_h_ref_to_cur=IDENTITY_H,
        )
        review = service.get_learning_review(seed.inspection_id) if seed.inspection_id else None
        learned_ok = bool(review and review["defects"])
        learning_entry = {
            "family": family.name,
            "label": family.label,
            "seed_status": seed.status,
            "seed_score": seed.anomaly_score,
            "review_defects": len(review["defects"]) if review else 0,
            "learned": learned_ok,
        }
        learning_rows.append(learning_entry)
        if not learned_ok:
            continue

        defect = max(review["defects"], key=lambda item: item["area"])
        service.accept_review_defect_as_normal(seed.inspection_id, defect["id"], note=family.label)

        positive_variants = ("seed", "shift", "small_70", "small_45", "lighter", "two")
        for variant_name in positive_variants:
            result, similarities = inspect_with_diagnostics(
                service,
                product_type,
                variants_by_family[family.name][variant_name],
                args.threshold,
            )
            rows.append(
                {
                    "learned_family": family.name,
                    "probe_family": family.name,
                    "variant": variant_name,
                    "expected_status": "ГОДЕН",
                    "actual_status": result.status,
                    "raw_score": result.raw_anomaly_score,
                    "final_score": result.anomaly_score,
                    "matches": result.learned_normal_matches_count,
                    "max_similarity": max(similarities) if similarities else None,
                    "passed": result.status == "ГОДЕН",
                }
            )

        result, similarities = inspect_with_diagnostics(
            service,
            product_type,
            variants_by_family[family.name]["large_145"],
            args.threshold,
        )
        rows.append(
            {
                "learned_family": family.name,
                "probe_family": family.name,
                "variant": "large_145",
                "expected_status": "БРАК",
                "actual_status": result.status,
                "raw_score": result.raw_anomaly_score,
                "final_score": result.anomaly_score,
                "matches": result.learned_normal_matches_count,
                "max_similarity": max(similarities) if similarities else None,
                "passed": result.status == "БРАК",
            }
        )

        for probe_family in FAMILIES:
            if probe_family.name == family.name:
                continue
            result, similarities = inspect_with_diagnostics(
                service,
                product_type,
                variants_by_family[probe_family.name]["shift"],
                args.threshold,
            )
            rows.append(
                {
                    "learned_family": family.name,
                    "probe_family": probe_family.name,
                    "variant": "foreign_shape",
                    "expected_status": "БРАК",
                    "actual_status": result.status,
                    "raw_score": result.raw_anomaly_score,
                    "final_score": result.anomaly_score,
                    "matches": result.learned_normal_matches_count,
                    "max_similarity": max(similarities) if similarities else None,
                    "passed": result.status == "БРАК",
                }
            )

    report_json = output_dir / "report.json"
    report_json.write_text(
        json.dumps({"learning": learning_rows, "results": rows}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    report_csv = output_dir / "report.csv"
    with report_csv.open("w", newline="", encoding="utf-8-sig") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    matrix_rows = [row for row in rows if row["variant"] == "foreign_shape"]
    matrix_csv = output_dir / "cross_shape_matrix.csv"
    with matrix_csv.open("w", newline="", encoding="utf-8-sig") as output:
        fieldnames = ["learned_family", *(family.name for family in FAMILIES)]
        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()
        for learned_family in FAMILIES:
            entry: dict[str, str] = {"learned_family": learned_family.name}
            for probe_family in FAMILIES:
                if learned_family.name == probe_family.name:
                    entry[probe_family.name] = "SAME"
                    continue
                match = next(
                    row
                    for row in matrix_rows
                    if row["learned_family"] == learned_family.name
                    and row["probe_family"] == probe_family.name
                )
                entry[probe_family.name] = match["actual_status"]
            writer.writerow(entry)

    learned_count = sum(1 for row in learning_rows if row["learned"])
    positive_rows = [row for row in rows if row["expected_status"] == "ГОДЕН"]
    negative_rows = [row for row in rows if row["expected_status"] == "БРАК"]
    positive_passed = sum(1 for row in positive_rows if row["passed"])
    negative_passed = sum(1 for row in negative_rows if row["passed"])
    false_rejects = [row for row in positive_rows if not row["passed"]]
    false_accepts = [row for row in negative_rows if not row["passed"]]

    lines = [
        "# Проверка обучения на разных формах и размерах",
        "",
        f"- Обучено семейств: **{learned_count}/{len(FAMILIES)}**.",
        f"- Допустимые варианты: **{positive_passed}/{len(positive_rows)}** распознаны как ГОДЕН.",
        f"- Новые/увеличенные формы: **{negative_passed}/{len(negative_rows)}** сохранены как БРАК.",
        f"- Ложных БРАК: **{len(false_rejects)}**.",
        f"- Опасных ложных ГОДЕН: **{len(false_accepts)}**.",
        "",
        "## Обучение",
        "",
        "| Семейство | Seed | Score | Дефектов в review | Сохранено |",
        "|---|---|---:|---:|---|",
    ]
    for row in learning_rows:
        lines.append(
            f"| `{row['family']}` | {row['seed_status']} | {row['seed_score']:.4f} | "
            f"{row['review_defects']} | {'да' if row['learned'] else 'нет'} |"
        )
    lines.extend(["", "## Ошибки", ""])
    failures = [row for row in rows if not row["passed"]]
    if failures:
        lines.extend(
            [
                "| Сохранённая норма | Проверяемая форма | Вариант | Ожидание | Факт | Similarity |",
                "|---|---|---|---|---|---:|",
            ]
        )
        for row in failures:
            similarity = "—" if row["max_similarity"] is None else f"{row['max_similarity']:.4f}"
            lines.append(
                f"| `{row['learned_family']}` | `{row['probe_family']}` | `{row['variant']}` | "
                f"{row['expected_status']} | {row['actual_status']} | {similarity} |"
            )
    else:
        lines.append("Ошибок не найдено.")
    report_md = output_dir / "REPORT.md"
    report_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    readme = output_dir / "README.md"
    readme.write_text(
        "# Набор разных дефектов\n\n"
        "Каждое семейство независимо сохраняется как допустимая норма. "
        "Проверяются перенос, масштабы 70% и 45%, ослабленный контраст, две копии, "
        "увеличение до 145% и все перекрёстные пары разных форм.\n\n"
        "Запуск из `analisSurface/backend`:\n\n"
        "```powershell\n"
        ".\\.venv\\Scripts\\python.exe scripts\\validate_varied_defect_learning.py\n"
        "```\n",
        encoding="utf-8",
    )
    contact_sheet = build_contact_sheet(output_dir, image_entries)

    if runtime_dir.exists():
        shutil.rmtree(runtime_dir)

    print(f"Dataset: {output_dir}")
    print(f"Contact sheet: {contact_sheet}")
    print(f"Learned families: {learned_count}/{len(FAMILIES)}")
    print(f"Accepted variants: {positive_passed}/{len(positive_rows)}")
    print(f"Rejected foreign/large variants: {negative_passed}/{len(negative_rows)}")
    print(f"False rejects: {len(false_rejects)}")
    print(f"False accepts: {len(false_accepts)}")
    return 0 if learned_count == len(FAMILIES) and not failures else 2


if __name__ == "__main__":
    raise SystemExit(main())
