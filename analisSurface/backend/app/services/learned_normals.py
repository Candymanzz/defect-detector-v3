"""Обучаемая память допустимых локальных отличий.

Модуль не классифицирует тип дефекта. Он выделяет независимые области в уже
построенной segmentation mask, запоминает подтверждённые оператором фрагменты
и при следующих инспекциях подавляет только достаточно похожие области.
"""

from __future__ import annotations

import json
import logging
import math
import os
import threading
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import cv2
import numpy as np


logger = logging.getLogger(__name__)

TEMPLATE_SIZE = 64


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def reference_fingerprint(image: np.ndarray) -> str:
    """Стабильный короткий fingerprint эталона без дорогого PNG-кодирования."""
    import hashlib

    digest = hashlib.sha256()
    digest.update(str(image.shape).encode("ascii"))
    digest.update(str(image.dtype).encode("ascii"))
    digest.update(np.ascontiguousarray(image).tobytes())
    return digest.hexdigest()[:24]


def _gray(image: np.ndarray) -> np.ndarray:
    if image.ndim == 2:
        return image
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)


def _encode(image: np.ndarray, extension: str, params: Optional[list[int]] = None) -> bytes:
    ok, encoded = cv2.imencode(extension, image, params or [])
    if not ok:
        raise ValueError(f"Could not encode review image as {extension}")
    return encoded.tobytes()


def _decode(data: bytes, flags: int = cv2.IMREAD_UNCHANGED) -> np.ndarray:
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), flags)
    if image is None:
        raise ValueError("Could not decode stored review image")
    return image


def _resize_template(image: np.ndarray, *, binary: bool = False) -> np.ndarray:
    interpolation = cv2.INTER_NEAREST if binary else cv2.INTER_AREA
    resized = cv2.resize(image, (TEMPLATE_SIZE, TEMPLATE_SIZE), interpolation=interpolation)
    if binary:
        return np.where(resized > 0, 255, 0).astype(np.uint8)
    return resized.astype(np.uint8)


@dataclass
class DefectCandidate:
    id: str
    bbox: tuple[int, int, int, int]
    bbox_norm: tuple[float, float, float, float]
    polygon_norm: list[tuple[float, float]]
    area: int
    diff_mean: float
    diff_q90: float
    diff_max: float
    score: float
    mask: np.ndarray = field(repr=False)
    mask_template: np.ndarray = field(repr=False)
    diff_template: np.ndarray = field(repr=False)
    appearance_template: np.ndarray = field(repr=False)
    matched_case_id: Optional[str] = None
    similarity: Optional[float] = None

    def to_public_dict(self) -> dict:
        x, y, width, height = self.bbox
        return {
            "id": self.id,
            "bbox": {"x": x, "y": y, "width": width, "height": height},
            "bbox_norm": {
                "x": self.bbox_norm[0],
                "y": self.bbox_norm[1],
                "width": self.bbox_norm[2],
                "height": self.bbox_norm[3],
            },
            "polygon": [{"x": x_norm, "y": y_norm} for x_norm, y_norm in self.polygon_norm],
            "area": self.area,
            "score": self.score,
            "diff_mean": self.diff_mean,
            "diff_q90": self.diff_q90,
            "diff_max": self.diff_max,
            "accepted_as_normal": self.matched_case_id is not None,
            "matched_case_id": self.matched_case_id,
            "similarity": self.similarity,
            "affects_final_score": self.matched_case_id is None,
        }


@dataclass
class AcceptedNormalCase:
    id: str
    product_type: str
    reference_hash: str
    bbox_norm: tuple[float, float, float, float]
    area: int
    diff_mean: float
    diff_q90: float
    diff_max: float
    created_at: str
    source_inspection_id: str
    source_defect_id: str
    note: str
    enabled: bool
    mask_template: np.ndarray = field(repr=False)
    diff_template: np.ndarray = field(repr=False)
    appearance_template: np.ndarray = field(repr=False)

    def to_public_dict(self) -> dict:
        return {
            "id": self.id,
            "product_type": self.product_type,
            "reference_hash": self.reference_hash,
            "bbox_norm": {
                "x": self.bbox_norm[0],
                "y": self.bbox_norm[1],
                "width": self.bbox_norm[2],
                "height": self.bbox_norm[3],
            },
            "area": self.area,
            "diff_mean": self.diff_mean,
            "diff_q90": self.diff_q90,
            "diff_max": self.diff_max,
            "created_at": self.created_at,
            "source_inspection_id": self.source_inspection_id,
            "source_defect_id": self.source_defect_id,
            "note": self.note,
            "enabled": self.enabled,
        }


@dataclass
class LearnedFilterResult:
    filtered_diff_map: np.ndarray
    filtered_mask: np.ndarray
    candidates: list[DefectCandidate]
    matched_case_ids: list[str]


@dataclass
class InspectionReview:
    inspection_id: str
    product_type: str
    reference_hash: str
    created_at: str
    original_status: str
    original_score: float
    threshold: float
    aligned_jpeg: bytes = field(repr=False)
    diff_png: bytes = field(repr=False)
    raw_mask_png: bytes = field(repr=False)
    heatmap_jpeg: bytes = field(repr=False)
    defects: list[DefectCandidate] = field(default_factory=list, repr=False)
    accepted_defect_ids: set[str] = field(default_factory=set, repr=False)
    counterfactual_score: Optional[float] = None
    counterfactual_status: Optional[str] = None

    def summary(self) -> dict:
        return {
            "inspection_id": self.inspection_id,
            "product_type": self.product_type,
            "reference_hash": self.reference_hash,
            "created_at": self.created_at,
            "original_status": self.original_status,
            "original_score": self.original_score,
            "threshold": self.threshold,
            "defects_count": len(self.defects),
            "accepted_defects_count": len(self.accepted_defect_ids),
            "counterfactual_score": self.counterfactual_score,
            "counterfactual_status": self.counterfactual_status,
            "pipeline_decision_sent": True,
        }

    def details(self) -> dict:
        payload = self.summary()
        payload["defects"] = [
            {
                **candidate.to_public_dict(),
                "manually_accepted": candidate.id in self.accepted_defect_ids,
            }
            for candidate in self.defects
        ]
        payload["affects_original_pipeline_decision"] = False
        return payload

    def image(self, kind: str) -> tuple[bytes, str]:
        if kind == "aligned":
            return self.aligned_jpeg, "image/jpeg"
        if kind == "diff":
            return self.diff_png, "image/png"
        if kind == "mask":
            return self.raw_mask_png, "image/png"
        if kind == "heatmap":
            return self.heatmap_jpeg, "image/jpeg"
        raise KeyError(kind)


class InspectionReviewStore:
    """Ограниченный RAM-архив недавних браков для операторского review."""

    def __init__(self, max_items: Optional[int] = None) -> None:
        raw_limit = os.environ.get("ANALIS_LEARNING_REVIEW_LIMIT", "40")
        try:
            configured = int(raw_limit)
        except ValueError:
            configured = 40
        self.max_items = max(1, min(500, max_items if max_items is not None else configured))
        self._items: OrderedDict[str, InspectionReview] = OrderedDict()
        self._lock = threading.RLock()

    def add(
        self,
        *,
        inspection_id: str,
        product_type: str,
        reference_hash: str,
        status: str,
        score: float,
        threshold: float,
        aligned: np.ndarray,
        diff_map: np.ndarray,
        raw_mask: np.ndarray,
        candidates: list[DefectCandidate],
    ) -> InspectionReview:
        mask_gray = _gray(raw_mask)
        diff_gray = _gray(diff_map)
        energy = cv2.max(mask_gray, cv2.normalize(diff_gray, None, 0, 255, cv2.NORM_MINMAX))
        heatmap = cv2.applyColorMap(energy, cv2.COLORMAP_JET)
        # Full-frame component masks нужны только во время live-фильтрации. Для
        # review достаточно маленьких шаблонов 64x64; иначе N дефектов держали бы
        # N полных масок одного и того же кадра.
        review_candidates = [
            replace(candidate, mask=np.zeros((0, 0), dtype=bool))
            for candidate in candidates
        ]
        review = InspectionReview(
            inspection_id=inspection_id,
            product_type=product_type,
            reference_hash=reference_hash,
            created_at=_utc_now(),
            original_status=status,
            original_score=float(score),
            threshold=float(threshold),
            aligned_jpeg=_encode(aligned, ".jpg", [cv2.IMWRITE_JPEG_QUALITY, 85]),
            diff_png=_encode(diff_map, ".png"),
            raw_mask_png=_encode(raw_mask, ".png"),
            heatmap_jpeg=_encode(heatmap, ".jpg", [cv2.IMWRITE_JPEG_QUALITY, 88]),
            defects=review_candidates,
        )
        with self._lock:
            self._items[inspection_id] = review
            self._items.move_to_end(inspection_id)
            while len(self._items) > self.max_items:
                self._items.popitem(last=False)
        return review

    def list(self, product_type: Optional[str] = None) -> list[dict]:
        with self._lock:
            items = list(reversed(self._items.values()))
            if product_type:
                items = [item for item in items if item.product_type == product_type]
            return [item.summary() for item in items]

    def get(self, inspection_id: str) -> Optional[InspectionReview]:
        with self._lock:
            return self._items.get(inspection_id)

    def mark_accepted(
        self,
        inspection_id: str,
        defect_id: str,
        *,
        counterfactual_score: Optional[float],
        counterfactual_status: Optional[str],
    ) -> None:
        with self._lock:
            review = self._items.get(inspection_id)
            if review is None:
                raise KeyError(inspection_id)
            review.accepted_defect_ids.add(defect_id)
            review.counterfactual_score = (
                float(counterfactual_score) if counterfactual_score is not None else None
            )
            review.counterfactual_status = counterfactual_status


class AcceptedNormalMemory:
    """Персистентная память фрагментов, подтверждённых оператором как норма."""

    def __init__(self, storage_dir: Path) -> None:
        self.storage_dir = Path(storage_dir)
        self._cases: dict[str, AcceptedNormalCase] = {}
        self._lock = threading.RLock()
        self._load()

    def list(self, product_type: Optional[str] = None) -> list[dict]:
        with self._lock:
            cases = list(self._cases.values())
            if product_type:
                cases = [case for case in cases if case.product_type == product_type]
            cases.sort(key=lambda case: case.created_at, reverse=True)
            return [case.to_public_dict() for case in cases]

    def get(self, case_id: str) -> Optional[AcceptedNormalCase]:
        with self._lock:
            return self._cases.get(case_id)

    def add_from_candidate(
        self,
        *,
        product_type: str,
        reference_hash: str,
        inspection_id: str,
        candidate: DefectCandidate,
        note: str = "",
    ) -> AcceptedNormalCase:
        case = AcceptedNormalCase(
            id=str(uuid.uuid4()),
            product_type=product_type,
            reference_hash=reference_hash,
            bbox_norm=candidate.bbox_norm,
            area=candidate.area,
            diff_mean=candidate.diff_mean,
            diff_q90=candidate.diff_q90,
            diff_max=candidate.diff_max,
            created_at=_utc_now(),
            source_inspection_id=inspection_id,
            source_defect_id=candidate.id,
            note=note.strip(),
            enabled=True,
            mask_template=candidate.mask_template.copy(),
            diff_template=candidate.diff_template.copy(),
            appearance_template=candidate.appearance_template.copy(),
        )
        with self._lock:
            self._cases[case.id] = case
            try:
                self._save_case(case)
            except Exception:
                self._cases.pop(case.id, None)
                raise
        return case

    def delete(self, case_id: str) -> bool:
        with self._lock:
            case = self._cases.pop(case_id, None)
            if case is None:
                return False
            for suffix in (".json", ".npz"):
                try:
                    (self.storage_dir / f"{case_id}{suffix}").unlink(missing_ok=True)
                except OSError:
                    logger.exception("failed to delete accepted-normal artifact case_id=%s", case_id)
            return True

    def apply(
        self,
        *,
        product_type: str,
        reference_hash: str,
        aligned: np.ndarray,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
    ) -> LearnedFilterResult:
        candidates = extract_defect_candidates(aligned, diff_map, segmentation_mask)
        with self._lock:
            cases = [
                case
                for case in self._cases.values()
                if case.enabled
                and case.product_type == product_type
                and case.reference_hash == reference_hash
            ]

        filtered_diff = diff_map.copy()
        filtered_mask = segmentation_mask.copy()
        matched_case_ids: list[str] = []
        for candidate in candidates:
            best_case: Optional[AcceptedNormalCase] = None
            best_similarity = 0.0
            for case in cases:
                similarity = candidate_similarity(candidate, case)
                if similarity is not None and similarity > best_similarity:
                    best_similarity = similarity
                    best_case = case
            if best_case is None:
                continue
            candidate.matched_case_id = best_case.id
            candidate.similarity = best_similarity
            # Убираем также узкий ореол морфологии вокруг совпавшей компоненты,
            # иначе остаточный diff продолжает повышать score уже «разрешённого»
            # фрагмента. Радиус мал и применяется только после строгого match.
            suppress_mask = cv2.dilate(
                candidate.mask.astype(np.uint8) * 255,
                np.ones((5, 5), dtype=np.uint8),
                iterations=1,
            ) > 0
            filtered_diff[suppress_mask] = 0
            filtered_mask[suppress_mask] = 0
            matched_case_ids.append(best_case.id)

        return LearnedFilterResult(
            filtered_diff_map=filtered_diff,
            filtered_mask=filtered_mask,
            candidates=candidates,
            matched_case_ids=list(dict.fromkeys(matched_case_ids)),
        )

    def _save_case(self, case: AcceptedNormalCase) -> None:
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        metadata = case.to_public_dict()
        json_path = self.storage_dir / f"{case.id}.json"
        npz_path = self.storage_dir / f"{case.id}.npz"
        temp_json = json_path.with_suffix(".json.tmp")
        temp_npz = npz_path.with_suffix(".npz.tmp")
        temp_json.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        with temp_npz.open("wb") as output:
            np.savez_compressed(
                output,
                mask_template=case.mask_template,
                diff_template=case.diff_template,
                appearance_template=case.appearance_template,
            )
        temp_json.replace(json_path)
        temp_npz.replace(npz_path)

    def _load(self) -> None:
        if not self.storage_dir.exists():
            return
        for json_path in self.storage_dir.glob("*.json"):
            try:
                payload = json.loads(json_path.read_text(encoding="utf-8"))
                case_id = str(payload["id"])
                arrays_path = self.storage_dir / f"{case_id}.npz"
                with np.load(arrays_path, allow_pickle=False) as arrays:
                    bbox = payload.get("bbox_norm", {})
                    case = AcceptedNormalCase(
                        id=case_id,
                        product_type=str(payload["product_type"]),
                        reference_hash=str(payload.get("reference_hash", "")),
                        bbox_norm=(
                            float(bbox.get("x", 0.0)),
                            float(bbox.get("y", 0.0)),
                            float(bbox.get("width", 0.0)),
                            float(bbox.get("height", 0.0)),
                        ),
                        area=int(payload.get("area", 0)),
                        diff_mean=float(payload.get("diff_mean", 0.0)),
                        diff_q90=float(payload.get("diff_q90", 0.0)),
                        diff_max=float(payload.get("diff_max", 0.0)),
                        created_at=str(payload.get("created_at", "")),
                        source_inspection_id=str(payload.get("source_inspection_id", "")),
                        source_defect_id=str(payload.get("source_defect_id", "")),
                        note=str(payload.get("note", "")),
                        enabled=bool(payload.get("enabled", True)),
                        mask_template=arrays["mask_template"].copy(),
                        diff_template=arrays["diff_template"].copy(),
                        appearance_template=arrays["appearance_template"].copy(),
                    )
                self._cases[case.id] = case
            except Exception:
                logger.exception("failed to load accepted-normal case metadata=%s", json_path)


def extract_defect_candidates(
    aligned: np.ndarray,
    diff_map: np.ndarray,
    segmentation_mask: np.ndarray,
) -> list[DefectCandidate]:
    mask_gray = _gray(segmentation_mask)
    binary = np.where(mask_gray > 0, 255, 0).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    height, width = binary.shape[:2]
    diff_gray = _gray(diff_map)
    aligned_gray = _gray(aligned)
    candidates: list[DefectCandidate] = []

    raw_candidates: list[tuple[int, int, int, int, int, int]] = []
    for label_idx in range(1, count):
        area = int(stats[label_idx, cv2.CC_STAT_AREA])
        if area <= 0:
            continue
        x = int(stats[label_idx, cv2.CC_STAT_LEFT])
        y = int(stats[label_idx, cv2.CC_STAT_TOP])
        box_width = int(stats[label_idx, cv2.CC_STAT_WIDTH])
        box_height = int(stats[label_idx, cv2.CC_STAT_HEIGHT])
        raw_candidates.append((y, x, label_idx, area, box_width, box_height))

    raw_candidates.sort(key=lambda entry: (entry[0], entry[1]))
    for ordinal, (y, x, label_idx, area, box_width, box_height) in enumerate(raw_candidates, start=1):
        component_mask = labels == label_idx
        local_mask = component_mask[y : y + box_height, x : x + box_width]
        local_diff = diff_gray[y : y + box_height, x : x + box_width]
        local_aligned = aligned_gray[y : y + box_height, x : x + box_width]
        values = diff_gray[component_mask]
        diff_mean = float(np.mean(values)) if values.size else 0.0
        diff_q90 = float(np.percentile(values, 90)) if values.size else 0.0
        diff_max = float(np.max(values)) if values.size else 0.0
        active_ratio = area / max(1, box_width * box_height)
        score = float(
            np.clip(
                (diff_q90 / 255.0) * 0.55
                + (diff_max / 255.0) * 0.20
                + active_ratio * 0.35,
                0.0,
                1.0,
            )
        )

        contours, _ = cv2.findContours(local_mask.astype(np.uint8) * 255, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        polygon_norm: list[tuple[float, float]] = []
        if contours:
            contour = max(contours, key=cv2.contourArea)
            epsilon = max(1.0, 0.006 * cv2.arcLength(contour, True))
            approximated = cv2.approxPolyDP(contour, epsilon, True).reshape(-1, 2)
            polygon_norm = [
                (
                    float((x + int(point[0])) / max(1, width - 1)),
                    float((y + int(point[1])) / max(1, height - 1)),
                )
                for point in approximated
            ]
        if len(polygon_norm) < 3:
            x0 = x / max(1, width - 1)
            y0 = y / max(1, height - 1)
            x1 = (x + box_width - 1) / max(1, width - 1)
            y1 = (y + box_height - 1) / max(1, height - 1)
            polygon_norm = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]

        candidates.append(
            DefectCandidate(
                id=f"defect-{ordinal}",
                bbox=(x, y, box_width, box_height),
                bbox_norm=(
                    x / max(1, width),
                    y / max(1, height),
                    box_width / max(1, width),
                    box_height / max(1, height),
                ),
                polygon_norm=polygon_norm,
                area=area,
                diff_mean=diff_mean,
                diff_q90=diff_q90,
                diff_max=diff_max,
                score=score,
                mask=component_mask,
                mask_template=_resize_template(local_mask.astype(np.uint8) * 255, binary=True),
                diff_template=_resize_template(local_diff),
                appearance_template=_resize_template(local_aligned),
            )
        )
    return candidates


def candidate_similarity(candidate: DefectCandidate, case: AcceptedNormalCase) -> Optional[float]:
    """Строгое сопоставление без семантической классификации дефекта."""
    cx, cy, cw, ch = candidate.bbox_norm
    sx, sy, sw, sh = case.bbox_norm
    candidate_center = (cx + cw * 0.5, cy + ch * 0.5)
    sample_center = (sx + sw * 0.5, sy + sh * 0.5)
    center_distance = math.dist(candidate_center, sample_center)
    max_center_distance = max(0.025, math.hypot(sw, sh) * 0.55)
    if center_distance > max_center_distance:
        return None

    candidate_area_norm = max(1e-9, cw * ch)
    sample_area_norm = max(1e-9, sw * sh)
    bbox_area_ratio = candidate_area_norm / sample_area_norm
    if not 0.50 <= bbox_area_ratio <= 1.60:
        return None

    if candidate.diff_q90 > max(case.diff_q90 * 1.60, case.diff_q90 + 12.0):
        return None
    if candidate.diff_max > max(case.diff_max * 1.50, case.diff_max + 20.0):
        return None

    candidate_mask = candidate.mask_template > 0
    sample_mask = case.mask_template > 0
    union = np.count_nonzero(candidate_mask | sample_mask)
    shape_iou = float(np.count_nonzero(candidate_mask & sample_mask) / union) if union else 0.0
    if shape_iou < 0.45:
        return None

    location_similarity = max(0.0, 1.0 - center_distance / max_center_distance)
    size_similarity = min(bbox_area_ratio, 1.0 / bbox_area_ratio)
    diff_similarity = 1.0 - float(
        np.mean(np.abs(candidate.diff_template.astype(np.float32) - case.diff_template.astype(np.float32)))
        / 255.0
    )
    appearance_similarity = 1.0 - float(
        np.mean(
            np.abs(
                candidate.appearance_template.astype(np.float32)
                - case.appearance_template.astype(np.float32)
            )
        )
        / 255.0
    )
    similarity = (
        location_similarity * 0.20
        + size_similarity * 0.15
        + shape_iou * 0.30
        + diff_similarity * 0.25
        + appearance_similarity * 0.10
    )
    if similarity < 0.80:
        return None
    return float(similarity)


def decode_review_arrays(review: InspectionReview) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    return (
        _decode(review.aligned_jpeg, cv2.IMREAD_COLOR),
        _decode(review.diff_png, cv2.IMREAD_COLOR),
        _decode(review.raw_mask_png, cv2.IMREAD_COLOR),
    )
