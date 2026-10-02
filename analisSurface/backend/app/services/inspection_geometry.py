"""Геометрия ROI: нормализованные полигоны [0,1] → маски и обрезка кадров.

Все координаты в API задаются относительно размера изображения (0 = левый/верхний край,
1 = правый/нижний). Пиксельные маски строятся через polygon_mask_from_norm_points().
"""

from functools import lru_cache
from typing import Tuple

import cv2
import numpy as np


def validate_polygon_points(points: list[Tuple[float, float]], label: str) -> list[Tuple[float, float]]:
    if len(points) < 3:
        raise ValueError(f"{label} must contain at least 3 points")
    normalized_points: list[Tuple[float, float]] = []
    for idx, (x, y) in enumerate(points):
        if x < 0 or x > 1 or y < 0 or y > 1:
            raise ValueError(f"{label} point #{idx + 1} must be inside [0, 1]")
        normalized_points.append((float(x), float(y)))
    return normalized_points


def polygon_area(points: list[Tuple[float, float]]) -> float:
    if len(points) < 3:
        return 0.0
    area = 0.0
    for idx, point in enumerate(points):
        nx, ny = points[(idx + 1) % len(points)]
        area += point[0] * ny - nx * point[1]
    return abs(area) * 0.5


def padded_bbox_polygon(
    bbox_norm: Tuple[float, float, float, float],
    width: int,
    height: int,
    pad_px: int | None = None,
    pad_frac: float = 0.02,
) -> list[Tuple[float, float]]:
    """Прямоугольник вокруг bbox дефекта с небольшим отступом от края."""
    x, y, box_w, box_h = bbox_norm
    min_side = max(1, min(width, height))
    pad = int(pad_px) if pad_px is not None else max(8, round(min_side * pad_frac))
    pad_x = pad / max(1, width)
    pad_y = pad / max(1, height)
    x0 = max(0.0, min(1.0, float(x) - pad_x))
    y0 = max(0.0, min(1.0, float(y) - pad_y))
    x1 = max(0.0, min(1.0, float(x) + float(box_w) + pad_x))
    y1 = max(0.0, min(1.0, float(y) + float(box_h) + pad_y))
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def polygon_bbox_from_norm_points(
    width: int,
    height: int,
    points: list[Tuple[float, float]],
    padding: int = 2,
) -> Tuple[int, int, int, int]:
    """Пиксельный bbox (x, y, w, h) нормализованного полигона, с padding и clip."""
    if width <= 0 or height <= 0 or len(points) < 3:
        return 0, 0, 0, 0
    xs = [int(round(x * (width - 1))) for x, _ in points]
    ys = [int(round(y * (height - 1))) for _, y in points]
    x0 = max(0, min(xs) - padding)
    y0 = max(0, min(ys) - padding)
    x1 = min(width, max(xs) + padding + 1)
    y1 = min(height, max(ys) + padding + 1)
    return x0, y0, max(0, x1 - x0), max(0, y1 - y0)


def polygon_mask_from_norm_points(width: int, height: int, points: list[Tuple[float, float]]) -> np.ndarray:
    pts = np.array(
        [[int(round(x * (width - 1))), int(round(y * (height - 1)))] for x, y in points],
        dtype=np.int32,
    )
    mask = np.zeros((height, width), dtype=np.uint8)
    cv2.fillPoly(mask, [pts], 255)
    return mask


def mask_to_polygon(
    aligned: np.ndarray,
    reference: np.ndarray,
    polygon: list[Tuple[float, float]],
) -> Tuple[np.ndarray, np.ndarray]:
    """Обнулить пиксели вне ROI на обоих кадрах перед сравнением."""
    height, width = reference.shape[:2]
    mask = polygon_mask_from_norm_points(width, height, polygon)

    aligned_masked = cv2.bitwise_and(aligned, aligned, mask=mask)
    reference_masked = cv2.bitwise_and(reference, reference, mask=mask)
    return aligned_masked, reference_masked


def point_in_polygon(x: float, y: float, polygon: list[Tuple[float, float]]) -> bool:
    if len(polygon) < 3:
        return False
    inside = False
    j = len(polygon) - 1
    for i in range(len(polygon)):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        intersects = (yi > y) != (yj > y) and x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi
        if intersects:
            inside = not inside
        j = i
    return inside


def validate_polygon_inside_parent(
    child_points: list[Tuple[float, float]],
    parent_points: list[Tuple[float, float]],
    label: str,
) -> list[Tuple[float, float]]:
    normalized = validate_polygon_points(child_points, label)
    if len(parent_points) < 3:
        raise ValueError("Parent ROI polygon must be set before adding sub-zones")
    for idx, (x, y) in enumerate(normalized):
        if not point_in_polygon(x, y, parent_points):
            raise ValueError(f"{label} point #{idx + 1} must be inside the parent ROI")
    return normalized


def combine_region_masks(
    width: int,
    height: int,
    include_polygon: list[Tuple[float, float]] | None,
    exclude_polygons: list[list[Tuple[float, float]]],
) -> np.ndarray:
    """Маска main ROI: внутри include_polygon, минус дырки sub-zones (exclude_polygons)."""
    if include_polygon is not None and len(include_polygon) >= 3:
        region_mask = polygon_mask_from_norm_points(width, height, include_polygon) > 0
    else:
        region_mask = np.ones((height, width), dtype=bool)

    for hole in exclude_polygons:
        if len(hole) < 3:
            continue
        region_mask &= ~(polygon_mask_from_norm_points(width, height, hole) > 0)
    return region_mask


PerspectiveLine = Tuple[Tuple[float, float], Tuple[float, float]]

_PERSPECTIVE_LINE_MIN_LENGTH = 0.05


def validate_perspective_line(points: list[Tuple[float, float]]) -> PerspectiveLine:
    """Линия перспективы: две точки [0, 1], от ближнего края изделия к дальнему."""
    if len(points) != 2:
        raise ValueError("Perspective line must contain exactly 2 points")
    for idx, (x, y) in enumerate(points):
        if x < 0 or x > 1 or y < 0 or y > 1:
            raise ValueError(f"Perspective line point #{idx + 1} must be inside [0, 1]")
    (x0, y0), (x1, y1) = points
    if ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5 < _PERSPECTIVE_LINE_MIN_LENGTH:
        raise ValueError("Perspective line is too short")
    return (float(x0), float(y0)), (float(x1), float(y1))


@lru_cache(maxsize=8)
def perspective_far_weights(
    frame_width: int,
    frame_height: int,
    line: PerspectiveLine,
    bbox: Tuple[int, int, int, int],
    far_edge_fraction: float,
) -> Tuple[np.ndarray, np.ndarray]:
    """Веса «удалённости» для области bbox=(x, y, w, h) кадра.

    Проекция пикселя на линию даёт t: 0 у ближнего конца, 1 у дальнего (с клипом).
    Возвращает (smooth, far_edge): smoothstep(t) для всего изделия и вес только
    последней доли far_edge_fraction линии, растущий от 0 до 1 к дальнему концу.
    Массивы только для чтения и кэшируются между кадрами.
    """
    x, y, width, height = bbox
    (nx0, ny0), (nx1, ny1) = line
    # Проекцию считаем в пикселях, иначе при не квадратном кадре угол линии исказится.
    px0, py0 = nx0 * (frame_width - 1), ny0 * (frame_height - 1)
    dx = nx1 * (frame_width - 1) - px0
    dy = ny1 * (frame_height - 1) - py0
    length_sq = max(dx * dx + dy * dy, 1e-6)
    xs = (np.arange(x, x + width, dtype=np.float32) - np.float32(px0)) * np.float32(dx / length_sq)
    ys = (np.arange(y, y + height, dtype=np.float32) - np.float32(py0)) * np.float32(dy / length_sq)
    t = np.clip(ys[:, np.newaxis] + xs[np.newaxis, :], 0.0, 1.0)
    smooth = t * t * (3.0 - 2.0 * t)
    far = np.clip((t - (1.0 - far_edge_fraction)) / far_edge_fraction, 0.0, 1.0)
    far = far * far * (3.0 - 2.0 * far)
    smooth = smooth.astype(np.float32, copy=False)
    far = far.astype(np.float32, copy=False)
    smooth.setflags(write=False)
    far.setflags(write=False)
    return smooth, far
