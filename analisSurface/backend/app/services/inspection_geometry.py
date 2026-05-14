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
    height, width = reference.shape[:2]
    mask = polygon_mask_from_norm_points(width, height, polygon)

    aligned_masked = cv2.bitwise_and(aligned, aligned, mask=mask)
    reference_masked = cv2.bitwise_and(reference, reference, mask=mask)
    return aligned_masked, reference_masked
