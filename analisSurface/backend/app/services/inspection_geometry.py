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


def far_tip_weights(
    far_weight: np.ndarray,
    far_edge_fraction: float = 0.35,
    tip_fraction: float = 0.10,
) -> np.ndarray:
    """Smooth ramp over the last 10% of the existing near-to-far axis.

    Recover axis position from its smoothstep weight, so full frames, crops,
    padded FP mini-etalons and rotated cameras use exactly the same band.
    Zero weights outside the ROI remain zero; points beyond a hand-drawn
    endpoint retain maximum sensitivity. No extra per-camera state is stored.
    """
    if not 0.0 < tip_fraction <= far_edge_fraction <= 1.0:
        raise ValueError("Tip fraction must be in (0, far_edge_fraction]")
    tip = np.zeros_like(far_weight, dtype=np.float32)
    start = 1.0 - tip_fraction / far_edge_fraction
    active = far_weight > start * start * (3.0 - 2.0 * start)
    # Inverse smoothstep: x = 1/2 - sin(asin(1 - 2y) / 3).
    axis = 0.5 - np.sin(np.arcsin(np.clip(1.0 - 2.0 * far_weight[active], -1.0, 1.0)) / 3.0)
    ramp = np.clip((axis - start) / (1.0 - start), 0.0, 1.0)
    tip[active] = ramp * ramp * (3.0 - 2.0 * ramp)
    tip.setflags(write=False)
    return tip


@lru_cache(maxsize=8)
def perspective_roi_far_weights(
    frame_width: int,
    frame_height: int,
    line: PerspectiveLine,
    roi_polygon: tuple[Tuple[float, float], ...],
    bbox: Tuple[int, int, int, int],
    far_edge_fraction: float,
    width_bins: int = 24,
) -> Tuple[np.ndarray, np.ndarray]:
    """Return the far-zone ramp and perspective scale inferred from ROI width.

    Only the last ``far_edge_fraction`` of the near-to-far axis is active.
    Scale follows the long side edges, extrapolated through rounded end caps.
    Values outside the ROI and before the far zone are
    neutral, so the rest of the inspection keeps its normal sensitivity.
    """
    x, y, width, height = bbox
    if width <= 0 or height <= 0:
        empty = np.zeros((max(0, height), max(0, width)), dtype=np.float32)
        empty.setflags(write=False)
        return empty, empty

    (nx0, ny0), (nx1, ny1) = line
    px0, py0 = nx0 * (frame_width - 1), ny0 * (frame_height - 1)
    dx = nx1 * (frame_width - 1) - px0
    dy = ny1 * (frame_height - 1) - py0
    length_sq = max(dx * dx + dy * dy, 1e-6)
    xs = (np.arange(x, x + width, dtype=np.float32) - np.float32(px0)) * np.float32(dx / length_sq)
    ys = (np.arange(y, y + height, dtype=np.float32) - np.float32(py0)) * np.float32(dy / length_sq)
    t = ys[:, np.newaxis] + xs[np.newaxis, :]

    roi_full = polygon_mask_from_norm_points(frame_width, frame_height, list(roi_polygon)) > 0
    roi = roi_full[y : y + height, x : x + width]
    start = 1.0 - float(far_edge_fraction)
    # A manually placed far endpoint can sit inside the surface. Saturate the
    # compensation beyond it, as in perspective_far_weights, instead of
    # abruptly disabling sensitivity for the farthest part of the ROI.
    active = roi & (t >= start)
    ramp = np.clip((t - start) / max(float(far_edge_fraction), 1e-6), 0.0, 1.0)
    ramp = ramp * ramp * (3.0 - 2.0 * ramp)
    ramp[~active] = 0.0

    bin_count = max(8, int(width_bins))
    edges = np.linspace(start, 1.0, bin_count + 1, dtype=np.float32)
    # End-cap pixel counts measure the rounded outline, not surface scale.
    # Infer taper only from two long opposite sides crossing the ROI interior.
    # The transverse-cap gate also prevents a diagonal line through a rectangle
    # from interpreting its corners as perspective.
    vertices = np.asarray(roi_polygon, dtype=np.float64) * (frame_width - 1, frame_height - 1)
    edge_vectors = np.roll(vertices, -1, axis=0) - vertices
    edge_lengths = np.linalg.norm(edge_vectors, axis=1)
    axis = np.array((dx, dy)) / np.sqrt(length_sq)
    cap_edges = np.abs(edge_vectors @ axis) <= 0.15 * edge_lengths
    scale_by_bin = np.ones(bin_count, dtype=np.float32)
    if np.count_nonzero(cap_edges & (edge_lengths > 0)) >= 2:
        vertex_t = (vertices - (px0, py0)) @ np.array((dx, dy)) / length_sq
        vertex_u = vertices @ np.array((-axis[1], axis[0]))
        next_t, next_u = np.roll(vertex_t, -1), np.roll(vertex_u, -1)
        span = float(np.ptp(vertex_t))
        middle = float((vertex_t.min() + vertex_t.max()) * 0.5)
        side_indices = np.flatnonzero(
            (np.minimum(vertex_t, next_t) < middle)
            & (np.maximum(vertex_t, next_t) > middle)
        )
        if len(side_indices) == 2 and np.all(
            np.abs(next_t[side_indices] - vertex_t[side_indices]) >= 0.4 * span
        ):
            slopes = (next_u[side_indices] - vertex_u[side_indices]) / (
                next_t[side_indices] - vertex_t[side_indices]
            )
            intercepts = vertex_u[side_indices] - slopes * vertex_t[side_indices]
            # Preserve side ordering: crossing extrapolated sides are not a
            # plausible scale estimate and must leave automatic gain neutral.
            order = np.argsort(slopes * middle + intercepts)
            width_slope = float(slopes[order[1]] - slopes[order[0]])
            width_intercept = float(intercepts[order[1]] - intercepts[order[0]])
            baseline = width_intercept + width_slope * start
            far_width = width_intercept + width_slope
            if baseline > 0.0 and far_width > 0.0:
                centers = (edges[:-1] + edges[1:]) * 0.5
                widths = width_intercept + width_slope * centers
                scale_by_bin = np.clip(baseline / widths, 1.0, 2.5).astype(np.float32)

    indices = np.clip(np.searchsorted(edges, t, side="right") - 1, 0, bin_count - 1)
    width_scale = scale_by_bin[indices]
    width_scale[~active] = 1.0
    ramp = ramp.astype(np.float32, copy=False)
    width_scale = width_scale.astype(np.float32, copy=False)
    ramp.setflags(write=False)
    width_scale.setflags(write=False)
    return ramp, width_scale
