"""Python-позиционирование кадра инспекции по эталону (замена java-positioning).

Ведро может довернуться на ~0.1°, из-за чего кадр отличается от эталона на
несколько пикселей. Кадр подгоняется под эталон жёстким преобразованием
(сдвиг + микро-поворот, без масштаба/перспективы), а по самому преобразованию
вычисляются две зоны, которые инспектировать нельзя:

* ``void``    — часть эталона, которой в текущем кадре больше нет: после сдвига
  там пусто («не ведро»). Это маска ``valid_mask == 0`` в координатах эталона.
* ``dropped`` — новая часть ведра, появившаяся из-за доворота: в эталоне её нет,
  после центрирования она уходит за границу кадра. Возвращается полигоном в
  координатах исходного кадра (для диагностики/UI).
"""

from __future__ import annotations

import math
import threading
import zlib
from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np

_MAX_WORK_DIM = 640
_ORB_FEATURES = 1500
_MIN_INLIERS = 10
_RATIO = 0.75
_RANSAC_PX = 3.0
_MIN_REF_KEYPOINTS = 24
_POLISH_MIN_PX = 0.15
_POLISH_MAX_PX = 30.0
_ROI_PAD_FRACTION = 0.08


@dataclass
class PositioningResult:
    aligned: np.ndarray
    # 255 — пиксель получен из текущего кадра, 0 — зона ``void`` (инспектировать нельзя).
    valid_mask: np.ndarray
    # Матрица current→reference (2x3) в полном разрешении; None — преобразование не применялось.
    matrix: Optional[np.ndarray] = None
    # Полигон новой части кадра, ушедшей за границу эталона (координаты исходного кадра, px).
    dropped_polygon: list[tuple[float, float]] = field(default_factory=list)
    diagnostics: dict[str, object] = field(default_factory=dict)


@dataclass
class _PreparedReference:
    key: tuple
    scale: float
    gray: np.ndarray
    keypoints: list
    descriptors: Optional[np.ndarray]


class FramePositioner:
    """Подгоняет кадр под эталон. Потокобезопасен; кэширует признаки эталона."""

    def __init__(
        self,
        *,
        max_rotation_deg: float = 2.0,
        max_shift_px: float = 120.0,
        edge_guard_px: int = 2,
    ) -> None:
        self.max_rotation_deg = float(max_rotation_deg)
        self.max_shift_px = float(max_shift_px)
        self.edge_guard_px = max(0, int(edge_guard_px))
        self._lock = threading.Lock()
        self._orb = cv2.ORB_create(nfeatures=_ORB_FEATURES)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        self._cache: dict[str, _PreparedReference] = {}

    def invalidate(self, product_type: Optional[str] = None) -> None:
        with self._lock:
            if product_type is None:
                self._cache.clear()
            else:
                self._cache.pop(product_type, None)

    # ------------------------------------------------------------------ public

    def position(
        self,
        current: np.ndarray,
        reference: np.ndarray,
        *,
        product_type: str = "",
        roi_polygon: Optional[list[tuple[float, float]]] = None,
    ) -> PositioningResult:
        height, width = reference.shape[:2]
        if current.shape[:2] != (height, width):
            current = cv2.resize(current, (width, height), interpolation=cv2.INTER_AREA)
        full_valid = np.full((height, width), 255, dtype=np.uint8)

        try:
            matrix, diag = self._estimate(current, reference, product_type, roi_polygon)
        except cv2.error as exc:  # pragma: no cover - защитный путь
            return PositioningResult(current, full_valid, None, [], {"status": f"cv_error:{exc.code}"})
        if matrix is None:
            return PositioningResult(current, full_valid, None, [], diag)

        aligned = cv2.warpAffine(
            current, matrix, (width, height),
            flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0,
        )
        ones = np.full((height, width), 255, dtype=np.uint8)
        valid = cv2.warpAffine(
            ones, matrix, (width, height),
            flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=0,
        )
        if self.edge_guard_px > 0:
            # Интерполяция размазывает границу: убираем кромку из проверяемой области.
            k = 2 * self.edge_guard_px + 1
            valid = cv2.erode(valid, np.ones((k, k), np.uint8))
        # Пиксели void не должны давать искусственный перепад на границе: берём из эталона.
        void = valid == 0
        if void.any():
            aligned = aligned.copy()
            aligned[void] = reference[void]

        dropped = self._dropped_polygon(matrix, width, height)
        diag.update(
            void_px=int(np.count_nonzero(void)),
            void_percent=round(100.0 * float(np.count_nonzero(void)) / max(1, height * width), 3),
            dropped_points=len(dropped),
        )
        return PositioningResult(aligned, valid, matrix, dropped, diag)

    # ---------------------------------------------------------------- internal

    def _estimate(
        self,
        current: np.ndarray,
        reference: np.ndarray,
        product_type: str,
        roi_polygon: Optional[list[tuple[float, float]]],
    ) -> tuple[Optional[np.ndarray], dict[str, object]]:
        prepared = self._prepare_reference(product_type, reference, roi_polygon)
        scale = prepared.scale
        cur_gray = self._work_gray(current, scale)
        mask = self._roi_mask(cur_gray.shape, roi_polygon)

        with self._lock:
            kp_cur, des_cur = self._orb.detectAndCompute(cur_gray, mask)
        diag: dict[str, object] = {"status": "ok", "ref_keypoints": len(prepared.keypoints)}
        if prepared.descriptors is None or des_cur is None or len(prepared.keypoints) < _MIN_REF_KEYPOINTS \
                or len(kp_cur) < _MIN_REF_KEYPOINTS:
            diag["status"] = "few_keypoints"
            return None, diag

        with self._lock:
            pairs = self._matcher.knnMatch(des_cur, prepared.descriptors, k=2)
        good = [p[0] for p in pairs if len(p) == 2 and p[0].distance < _RATIO * p[1].distance]
        diag["good_matches"] = len(good)
        if len(good) < _MIN_INLIERS:
            diag["status"] = "few_matches"
            return None, diag

        src = np.float32([kp_cur[m.queryIdx].pt for m in good])
        dst = np.float32([prepared.keypoints[m.trainIdx].pt for m in good])
        affine, inliers = cv2.estimateAffinePartial2D(
            src, dst, method=cv2.RANSAC, ransacReprojThreshold=_RANSAC_PX, maxIters=500,
        )
        if affine is None or inliers is None or int(inliers.sum()) < _MIN_INLIERS:
            diag["status"] = "ransac_failed"
            return None, diag
        mask_in = inliers.ravel().astype(bool)
        diag["inliers"] = int(mask_in.sum())

        # Жёсткое преобразование: масштаб из ORB отбрасываем, угол ограничиваем.
        angle = math.atan2(float(affine[1, 0]), float(affine[0, 0]))
        max_angle = math.radians(self.max_rotation_deg)
        if abs(angle) > max_angle:
            diag["rotation_clamped_from_deg"] = round(math.degrees(angle), 4)
            angle = math.copysign(max_angle, angle)
        cos_a, sin_a = math.cos(angle), math.sin(angle)
        rot = np.array([[cos_a, -sin_a], [sin_a, cos_a]], dtype=np.float64)
        translation = np.median(dst[mask_in] - src[mask_in] @ rot.T, axis=0)

        # Из рабочего масштаба — в полное разрешение: перенос масштабируется, поворот нет.
        matrix = np.hstack([rot, (translation / scale).reshape(2, 1)])
        # Поворот в ORB считался вокруг начала координат; при масштабировании координат он тот же.
        shift = float(np.hypot(*(translation / scale)))
        if shift > self.max_shift_px:
            diag["status"] = "shift_too_large"
            diag["shift_px"] = round(shift, 2)
            return None, diag

        matrix = self._polish_translation(current, reference, matrix, roi_polygon, diag)
        diag["rotation_deg"] = round(math.degrees(angle), 4)
        diag["shift_x_px"] = round(float(matrix[0, 2]), 3)
        diag["shift_y_px"] = round(float(matrix[1, 2]), 3)
        return matrix, diag

    def _polish_translation(
        self,
        current: np.ndarray,
        reference: np.ndarray,
        matrix: np.ndarray,
        roi_polygon: Optional[list[tuple[float, float]]],
        diag: dict[str, object],
    ) -> np.ndarray:
        """Субпиксельная доводка сдвига фазовой корреляцией по области ROI."""
        height, width = reference.shape[:2]
        x0, y0, x1, y1 = self._roi_box(width, height, roi_polygon)
        if x1 - x0 < 32 or y1 - y0 < 32:
            return matrix
        warped = cv2.warpAffine(current, matrix, (width, height), flags=cv2.INTER_LINEAR)
        ref_g = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)[y0:y1, x0:x1].astype(np.float32)
        cur_g = cv2.cvtColor(warped, cv2.COLOR_BGR2GRAY)[y0:y1, x0:x1].astype(np.float32)
        window = cv2.createHanningWindow((ref_g.shape[1], ref_g.shape[0]), cv2.CV_32F)
        (dx, dy), response = cv2.phaseCorrelate(ref_g, cur_g, window)
        residual = float(np.hypot(dx, dy))
        diag["polish_residual_px"] = round(residual, 3)
        diag["polish_response"] = round(float(response), 3)
        if not (_POLISH_MIN_PX <= residual <= _POLISH_MAX_PX) or response < 0.05:
            return matrix
        candidate = matrix.copy()
        # phaseCorrelate(ref, cur) возвращает сдвиг cur относительно ref: компенсируем обратным знаком.
        candidate[0, 2] -= dx
        candidate[1, 2] -= dy
        before = self._absdiff(reference, warped, (x0, y0, x1, y1))
        after_img = cv2.warpAffine(current, candidate, (width, height), flags=cv2.INTER_LINEAR)
        after = self._absdiff(reference, after_img, (x0, y0, x1, y1))
        if after < before:
            diag["polish_applied"] = True
            return candidate
        return matrix

    @staticmethod
    def _absdiff(a: np.ndarray, b: np.ndarray, box: tuple[int, int, int, int]) -> float:
        x0, y0, x1, y1 = box
        return float(np.mean(cv2.absdiff(a[y0:y1, x0:x1], b[y0:y1, x0:x1])))

    @staticmethod
    def _dropped_polygon(matrix: np.ndarray, width: int, height: int) -> list[tuple[float, float]]:
        """Контур эталона в координатах исходного кадра.

        Всё, что в исходном кадре лежит снаружи этого контура, — новая часть ведра
        (``dropped``): в эталоне её нет, после центрирования она выходит за кадр.
        """
        inv = cv2.invertAffineTransform(matrix)
        corners = np.float32([[[0, 0], [width, 0], [width, height], [0, height]]])
        return [(float(x), float(y)) for x, y in cv2.transform(corners, inv)[0]]

    def _prepare_reference(
        self,
        product_type: str,
        reference: np.ndarray,
        roi_polygon: Optional[list[tuple[float, float]]],
    ) -> _PreparedReference:
        thumb = cv2.resize(reference, (32, 32), interpolation=cv2.INTER_AREA)
        poly_key = tuple(round(v, 4) for p in (roi_polygon or []) for v in p)
        key = (reference.shape, zlib.crc32(thumb.tobytes()), poly_key)
        with self._lock:
            cached = self._cache.get(product_type)
            if cached is not None and cached.key == key:
                return cached
        scale = min(1.0, _MAX_WORK_DIM / float(max(reference.shape[:2])))
        gray = self._work_gray(reference, scale)
        mask = self._roi_mask(gray.shape, roi_polygon)
        with self._lock:
            keypoints, descriptors = self._orb.detectAndCompute(gray, mask)
            prepared = _PreparedReference(key, scale, gray, list(keypoints), descriptors)
            self._cache[product_type] = prepared
        return prepared

    @staticmethod
    def _work_gray(image: np.ndarray, scale: float) -> np.ndarray:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
        if scale < 0.999:
            gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        return cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)

    @staticmethod
    def _roi_box(
        width: int, height: int, polygon: Optional[list[tuple[float, float]]],
    ) -> tuple[int, int, int, int]:
        if not polygon or len(polygon) < 3:
            return 0, 0, width, height
        xs = [p[0] for p in polygon]
        ys = [p[1] for p in polygon]
        px, py = _ROI_PAD_FRACTION * width, _ROI_PAD_FRACTION * height
        x0 = max(0, int(min(xs) * (width - 1) - px))
        y0 = max(0, int(min(ys) * (height - 1) - py))
        x1 = min(width, int(max(xs) * (width - 1) + px) + 1)
        y1 = min(height, int(max(ys) * (height - 1) + py) + 1)
        return x0, y0, x1, y1

    def _roi_mask(
        self, shape: tuple[int, int], polygon: Optional[list[tuple[float, float]]],
    ) -> Optional[np.ndarray]:
        if not polygon or len(polygon) < 3:
            return None
        h, w = shape[:2]
        x0, y0, x1, y1 = self._roi_box(w, h, polygon)
        mask = np.zeros((h, w), dtype=np.uint8)
        mask[y0:y1, x0:x1] = 255
        return mask
