"""Bounded cache of reference-only CV operations; current-frame work stays live."""
from __future__ import annotations

from collections import OrderedDict
import hashlib
import threading
from typing import Callable

import cv2
import numpy as np


class ReferencePreparationCache:
    def __init__(self, max_bytes: int = 128 * 1024 * 1024, max_entries: int = 256):
        self.max_bytes = max(0, int(max_bytes))
        self.max_entries = max(1, int(max_entries))
        self._entries: OrderedDict[tuple, tuple[dict, int]] = OrderedDict()
        self._bytes = 0
        self._lock = threading.Lock()

    @staticmethod
    def identity(reference: np.ndarray) -> tuple:
        # Content, not the address: inspection creates cropped/resized copies,
        # and an operator can replace a reference without changing its shape.
        content = memoryview(np.ascontiguousarray(reference)).cast("B")
        return reference.shape, reference.dtype.str, hashlib.blake2b(content, digest_size=16).digest()

    def get(self, key: tuple, build: Callable[[], dict]) -> dict:
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None:
                self._entries.move_to_end(key)
                return entry[0]
        # CV kernels must never hold the shared cache lock.
        prepared = build()
        size = sum(value.nbytes for value in prepared.values() if isinstance(value, np.ndarray))
        for value in prepared.values():
            if isinstance(value, np.ndarray):
                value.setflags(write=False)
        if not self.max_bytes or size > self.max_bytes:
            return prepared
        with self._lock:
            existing = self._entries.get(key)
            if existing is not None:
                self._entries.move_to_end(key)
                return existing[0]
            while self._entries and (self._bytes + size > self.max_bytes or len(self._entries) >= self.max_entries):
                _, (_, removed_bytes) = self._entries.popitem(last=False)
                self._bytes -= removed_bytes
            self._entries[key] = prepared, size
            self._bytes += size
        return prepared

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()
            self._bytes = 0


def prepare_illumination(gray: np.ndarray) -> dict:
    ref_float = gray.astype(np.float32)
    low_ref = cv2.GaussianBlur(ref_float, (9, 9), 0)
    detail = ref_float - low_ref
    return {"float": ref_float, "low": low_ref,
            "gx": cv2.Sobel(detail, cv2.CV_32F, 1, 0, ksize=3),
            "gy": cv2.Sobel(detail, cv2.CV_32F, 0, 1, ksize=3)}


def prepare_features(gray: np.ndarray, clahe_clip: float | None, detail: bool,
                     envelope_kernel: np.ndarray, edge_kernel: np.ndarray) -> dict:
    if clahe_clip is not None:
        gray = cv2.createCLAHE(clipLimit=clahe_clip, tileGridSize=(8, 8)).apply(gray)
    result = {}
    if detail:
        fine = cv2.GaussianBlur(gray, (3, 3), 0)
        result["detail_min"] = cv2.erode(fine, envelope_kernel)
        result["detail_max"] = cv2.dilate(fine, envelope_kernel)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    result.update(
        gray=gray,
        minimum=cv2.erode(gray, envelope_kernel, iterations=1),
        maximum=cv2.dilate(gray, envelope_kernel, iterations=1),
        gradient=cv2.magnitude(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3),
                               cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)),
        edges=cv2.dilate(cv2.Canny(gray, 80, 160), edge_kernel, iterations=2),
        structure=cv2.Sobel(gray, cv2.CV_8U, 1, 1, ksize=3),
    )
    return result
