"""Fast translation-only compensation for small bucket jitter.

No rotation, scale fitting, ORB/RANSAC, reference-pixel replacement or exclusion
strips. Unreliable estimates leave the input unchanged, not a technical REJECT.
"""
from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass, field
import threading
from typing import Optional

import cv2
import numpy as np

from app.services.reference_preparation import ReferencePreparationCache


@dataclass
class PositioningResult:
    aligned: np.ndarray
    # Compatibility fields: basic translation does not create exclusion masks.
    valid_mask: Optional[np.ndarray] = None
    matrix: Optional[np.ndarray] = None
    dropped_polygon: list[tuple[float, float]] = field(default_factory=list)
    diagnostics: dict[str, object] = field(default_factory=dict)


@dataclass
class _PreparedReference:
    key: tuple
    box: tuple[int, int, int, int]
    size: tuple[int, int]
    scale: tuple[float, float]
    detail: np.ndarray
    window: np.ndarray
    windowed: np.ndarray


class FramePositioner:
    def __init__(self, *, max_shift_px: float = 32.0, max_work_dim: int = 640) -> None:
        self.max_shift_px = float(max_shift_px)
        self.max_work_dim = max(64, int(max_work_dim))
        self._lock = threading.Lock()
        self._cache: OrderedDict[str, _PreparedReference] = OrderedDict()

    def invalidate(self, product_type: Optional[str] = None) -> None:
        with self._lock:
            if product_type is None:
                self._cache.clear()
            else:
                self._cache.pop(product_type, None)

    def position(self, current: np.ndarray, reference: np.ndarray, *, product_type: str = "",
                 roi_polygon: Optional[list[tuple[float, float]]] = None,
                 reference_token: Optional[str] = None) -> PositioningResult:
        height, width = reference.shape[:2]
        if current.shape[:2] != (height, width):
            current = cv2.resize(current, (width, height), interpolation=cv2.INTER_AREA)
        diag: dict[str, object] = {"method": "translation_phase", "status": "unchanged", "rotation_deg": 0.0}
        try:
            prepared = self._prepare_reference(product_type, reference, roi_polygon, reference_token)
            cur_detail = self._work_detail(current, prepared.box, prepared.size)
            if float(np.std(prepared.detail)) < 1.0 or float(np.std(cur_detail)) < 1.0:
                diag["status"] = "low_texture"
                return PositioningResult(current, diagnostics=diag)
            # phaseCorrelate(window=...) modifies its source arrays. Pre-window
            # separately so cached reference data remains immutable across calls.
            (dx, dy), response = cv2.phaseCorrelate(prepared.windowed, cur_detail * prepared.window)
            sx, sy = prepared.scale
            tx, ty = -dx / sx, -dy / sy
            diag.update(phase_response=round(float(response), 4),
                        shift_x_px=round(tx, 3), shift_y_px=round(ty, 3))
            if not np.isfinite([tx, ty, response]).all() or response < 0.08:
                diag["status"] = "low_confidence"
                return PositioningResult(current, diagnostics=diag)
            if np.hypot(tx, ty) > self.max_shift_px:
                diag["status"] = "shift_too_large"
                return PositioningResult(current, diagnostics=diag)
            if np.hypot(tx, ty) < 0.15:
                diag["status"] = "already_aligned"
                return PositioningResult(current, diagnostics=diag)

            # Verify cheaply at working resolution, on real overlapping pixels.
            # Clipping the loss prevents one glare/defect dominating the decision.
            small_matrix = np.float32([[1, 0, -dx], [0, 1, -dy]])
            shifted = cv2.warpAffine(cur_detail, small_matrix, prepared.size, borderMode=cv2.BORDER_REPLICATE)
            margin = int(np.ceil(max(abs(dx), abs(dy)))) + 4
            if min(cur_detail.shape) <= 2 * margin + 8:
                diag["status"] = "small_roi"
                return PositioningResult(current, diagnostics=diag)
            inner = (slice(margin, -margin), slice(margin, -margin))
            ref_inner = prepared.detail[inner]
            before = float(np.minimum(cv2.absdiff(ref_inner, cur_detail[inner]), 30).mean())
            after = float(np.minimum(cv2.absdiff(ref_inner, shifted[inner]), 30).mean())
            diag.update(error_before=round(before, 4), error_after=round(after, 4))
            if after >= before * 0.99:
                diag["status"] = "no_improvement"
                return PositioningResult(current, diagnostics=diag)
            matrix = np.float32([[1, 0, tx], [0, 1, ty]])
            aligned = cv2.warpAffine(current, matrix, (width, height),
                                     flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            diag["status"] = "ok"
            return PositioningResult(aligned, matrix=matrix, diagnostics=diag)
        except cv2.error as exc:
            diag["status"] = f"cv_error:{exc.code}"
            return PositioningResult(current, diagnostics=diag)

    def _prepare_reference(self, product_type, reference, polygon, token) -> _PreparedReference:
        identity = token if token is not None else ReferencePreparationCache.identity(reference)
        box = self._roi_box(reference.shape[1], reference.shape[0], polygon)
        key = (reference.shape, identity, box, self.max_work_dim)
        with self._lock:
            cached = self._cache.get(product_type)
            if cached is not None and cached.key == key:
                self._cache.move_to_end(product_type)
                return cached
        x0, y0, x1, y1 = box
        scale = min(1.0, self.max_work_dim / max(x1 - x0, y1 - y0))
        size = (max(8, round((x1 - x0) * scale)), max(8, round((y1 - y0) * scale)))
        detail = self._work_detail(reference, box, size)
        window = cv2.createHanningWindow(size, cv2.CV_32F)
        windowed = detail * window
        for array in (detail, window, windowed):
            array.setflags(write=False)
        prepared = _PreparedReference(key, box, size, (size[0] / (x1 - x0), size[1] / (y1 - y0)),
                                      detail, window, windowed)
        with self._lock:
            self._cache[product_type] = prepared
            self._cache.move_to_end(product_type)
            while len(self._cache) > 16:
                self._cache.popitem(last=False)
        return prepared

    @staticmethod
    def _work_detail(image, box, size):
        x0, y0, x1, y1 = box
        small = image[y0:y1, x0:x1]
        if (small.shape[1], small.shape[0]) != size:
            small = cv2.resize(small, size, interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY) if small.ndim == 3 else small
        gray = gray.astype(np.float32)
        return gray - cv2.GaussianBlur(gray, (0, 0), 3.0)

    @staticmethod
    def _roi_box(width, height, polygon):
        if not polygon or len(polygon) < 3:
            return 0, 0, width, height
        xs, ys = zip(*polygon)
        # Enough real context for the small displacement, without involving the
        # full stationary background when a bucket ROI has been configured.
        px, py = max(16, width * 0.04), max(16, height * 0.04)
        x0, y0 = max(0, int(min(xs) * (width - 1) - px)), max(0, int(min(ys) * (height - 1) - py))
        x1, y1 = min(width, int(max(xs) * (width - 1) + px) + 1), min(height, int(max(ys) * (height - 1) + py) + 1)
        return (x0, y0, x1, y1) if x1 - x0 >= 16 and y1 - y0 >= 16 else (0, 0, width, height)
