"""Bounded, translation-only alignment of a bucket to its reference image."""

from __future__ import annotations

from dataclasses import dataclass
import math
import threading
from typing import Optional

import cv2
import numpy as np


_WORKING_MAX_DIM = 640
_MAX_SHIFT_PX = 48.0
_MIN_SHIFT_PX = 0.75


@dataclass(frozen=True)
class TranslationAlignment:
    image: np.ndarray
    dx: float = 0.0
    dy: float = 0.0
    method: str = "unchanged"
    matches: int = 0
    inliers: int = 0
    edge_before: float = math.nan
    edge_after: float = math.nan


class TranslationAligner:
    """Track the printed bucket/contour, not the stationary machine background."""

    def __init__(self) -> None:
        self._orb = cv2.ORB_create(nfeatures=1200, fastThreshold=10)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING)
        self._lock = threading.RLock()
        self._reference_cache: dict[tuple, tuple[np.ndarray, list, Optional[np.ndarray]]] = {}

    def invalidate(self, product_type: str) -> None:
        with self._lock:
            for key in list(self._reference_cache):
                if key[0] == product_type:
                    del self._reference_cache[key]

    def clear(self) -> None:
        with self._lock:
            self._reference_cache.clear()

    def align(
        self,
        current: np.ndarray,
        reference: np.ndarray,
        *,
        product_type: str,
        reference_token: str,
        roi_polygon: Optional[list[tuple[float, float]]] = None,
    ) -> TranslationAlignment:
        height, width = reference.shape[:2]
        if current.shape[:2] != (height, width):
            current = cv2.resize(current, (width, height), interpolation=cv2.INTER_AREA)
        if height < 32 or width < 32:
            return TranslationAlignment(current)

        scale = min(1.0, _WORKING_MAX_DIM / max(width, height))
        size = (max(1, round(width * scale)), max(1, round(height * scale)))
        mask = self._subject_mask(size, roi_polygon)
        polygon_key = tuple((float(x), float(y)) for x, y in (roi_polygon or ()))
        cache_key = (product_type, reference_token, size, polygon_key)
        with self._lock:
            cached = self._reference_cache.get(cache_key)
            if cached is None:
                ref_gray = cv2.cvtColor(cv2.resize(reference, size), cv2.COLOR_BGR2GRAY)
                ref_kp, ref_des = self._orb.detectAndCompute(ref_gray, mask)
                cached = (ref_gray, ref_kp, ref_des)
                self._reference_cache[cache_key] = cached
            ref_gray, ref_kp, ref_des = cached
            cur_gray = cv2.cvtColor(cv2.resize(current, size), cv2.COLOR_BGR2GRAY)
            cur_kp, cur_des = self._orb.detectAndCompute(cur_gray, mask)

            matches = 0
            inliers = 0
            coarse: Optional[tuple[float, float]] = None
            if ref_des is not None and cur_des is not None and len(ref_kp) >= 12 and len(cur_kp) >= 12:
                pairs = self._matcher.knnMatch(cur_des, ref_des, k=2)
                good = [a for pair in pairs if len(pair) == 2 for a, b in [pair] if a.distance < 0.75 * b.distance]
                matches = len(good)
                if matches >= 12:
                    offsets = np.asarray(
                        [np.subtract(ref_kp[m.trainIdx].pt, cur_kp[m.queryIdx].pt) for m in good],
                        dtype=np.float32,
                    )
                    median = np.median(offsets, axis=0)
                    consensus = np.linalg.norm(offsets - median, axis=1) <= 2.5
                    inliers = int(np.count_nonzero(consensus))
                    if inliers >= 10 and inliers / matches >= 0.55:
                        refined_median = np.median(offsets[consensus], axis=0)
                        coarse = (float(refined_median[0]), float(refined_median[1]))

        ref_edges = self._edges(ref_gray, mask)
        cur_edges = self._edges(cur_gray, mask)
        if min(cv2.countNonZero(ref_edges), cv2.countNonZero(cur_edges)) < 80:
            return TranslationAlignment(current, matches=matches, inliers=inliers)

        edge_before = self._edge_distance(ref_edges, cur_edges)
        candidates: list[tuple[float, float, str]] = []
        if coarse is not None:
            candidates.append((*coarse, "orb"))
            coarse_edges = self._translated_edges(cur_edges, *coarse)
            residual = self._phase_correction(ref_edges, coarse_edges, mask)
            if residual is not None:
                rx, ry, response = residual
                if response >= 0.20 and math.hypot(rx, ry) <= 3.0:
                    candidates.append((coarse[0] + rx, coarse[1] + ry, "orb_phase"))
        else:
            phase = self._phase_correction(ref_edges, cur_edges, mask)
            if phase is not None and phase[2] >= 0.30:
                candidates.append((phase[0], phase[1], "edge_phase"))

        best: Optional[tuple[float, float, str, float]] = None
        for dx, dy, method in candidates:
            shift_px = math.hypot(dx / scale, dy / scale)
            if not _MIN_SHIFT_PX <= shift_px <= _MAX_SHIFT_PX:
                continue
            moved_edges = self._translated_edges(cur_edges, dx, dy)
            edge_after = self._edge_distance(ref_edges, moved_edges)
            if not math.isfinite(edge_after):
                continue
            if best is None or edge_after < best[3]:
                best = (dx, dy, method, edge_after)

        if best is None or not math.isfinite(edge_before) or best[3] > edge_before - max(0.03, 0.06 * edge_before):
            return TranslationAlignment(current, matches=matches, inliers=inliers, edge_before=edge_before)

        dx = best[0] / scale
        dy = best[1] / scale
        transform = np.float32([[1.0, 0.0, dx], [0.0, 1.0, dy]])
        aligned = cv2.warpAffine(
            current,
            transform,
            (width, height),
            flags=cv2.INTER_LINEAR,
            borderMode=cv2.BORDER_REPLICATE,
        )
        return TranslationAlignment(aligned, dx, dy, best[2], matches, inliers, edge_before, best[3])

    @staticmethod
    def _subject_mask(size: tuple[int, int], polygon: Optional[list[tuple[float, float]]]) -> np.ndarray:
        width, height = size
        mask = np.zeros((height, width), dtype=np.uint8)
        if polygon and len(polygon) >= 3:
            points = np.asarray(
                [[round(x * (width - 1)), round(y * (height - 1))] for x, y in polygon],
                dtype=np.int32,
            )
            cv2.fillPoly(mask, [points], 255)
            mask = cv2.erode(mask, np.ones((5, 5), np.uint8))
        else:
            mask[round(height * 0.08):round(height * 0.85),
                 round(width * 0.12):round(width * 0.88)] = 255
        return mask

    @staticmethod
    def _edges(gray: np.ndarray, mask: np.ndarray) -> np.ndarray:
        edges = cv2.Canny(cv2.GaussianBlur(gray, (3, 3), 0), 40, 100)
        return cv2.bitwise_and(edges, mask)

    @staticmethod
    def _translated_edges(edges: np.ndarray, dx: float, dy: float) -> np.ndarray:
        height, width = edges.shape
        matrix = np.float32([[1.0, 0.0, dx], [0.0, 1.0, dy]])
        return cv2.warpAffine(edges, matrix, (width, height), flags=cv2.INTER_NEAREST)

    @staticmethod
    def _phase_correction(reference: np.ndarray, current: np.ndarray, mask: np.ndarray) -> Optional[tuple[float, float, float]]:
        ref = reference.astype(np.float32)
        cur = current.astype(np.float32)
        # Blur mask boundaries so the stationary ROI border cannot dominate the spectrum.
        weight = cv2.GaussianBlur(mask.astype(np.float32) / 255.0, (0, 0), 4.0)
        try:
            displacement, response = cv2.phaseCorrelate(ref * weight, cur * weight)
        except cv2.error:
            return None
        if not all(math.isfinite(v) for v in (*displacement, response)):
            return None
        return -float(displacement[0]), -float(displacement[1]), float(response)

    @staticmethod
    def _edge_distance(reference: np.ndarray, current: np.ndarray) -> float:
        ref_count = cv2.countNonZero(reference)
        cur_count = cv2.countNonZero(current)
        if min(ref_count, cur_count) < 80:
            return math.nan
        to_ref = cv2.distanceTransform(cv2.bitwise_not(reference), cv2.DIST_L2, 3)
        to_cur = cv2.distanceTransform(cv2.bitwise_not(current), cv2.DIST_L2, 3)
        return float(
            (np.minimum(to_ref[current != 0], 5.0).mean()
             + np.minimum(to_cur[reference != 0], 5.0).mean()) * 0.5
        )
