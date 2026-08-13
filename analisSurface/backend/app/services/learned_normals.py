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
TEMPLATE_INNER_SIZE = 56
TEMPLATE_VERSION = 3
# Максимальное расстояние между центрами текущего и сохранённого дефекта в
# нормированных координатах кадра. 0.15 означает локальный сдвиг
# (например, около 184 px по горизонтали на рабочем кадре шириной 1224 px),
# но не перенос исключения на другую часть изделия.
POSITION_TOLERANCE_NORM = 0.15
THIN_TRACE_MIN_SIMILARITY = 0.68
SCALED_SHAPE_MIN_SIMILARITY = 0.76
REDUCED_SHAPE_MIN_SIMILARITY = 0.78
GENERAL_MIN_SIMILARITY = 0.80
# Review must expose secondary components that can still keep the verdict BAD
# after the largest components have been accepted. 15% keeps material defects
# visible without bringing back the many weak speckles from the raw mask.
REVIEW_MIN_RELATIVE_IMPACT = 0.15
# Царапина может занимать немного пикселей, но всё равно заметно влиять на
# результат. Для неё используется отдельный, всё ещё относительный порог.
REVIEW_SIGNIFICANT_SCRATCH_MIN_Q90 = 30.0
# A thin trace occupies far fewer pixels than a broad stain/glare, so comparing
# both with the same relative cutoff hides visually meaningful scratches. 8%
# still rejects weak line noise, but keeps the real bad1 scratch (~9.4%).
REVIEW_SIGNIFICANT_SCRATCH_MIN_RELATIVE_IMPACT = 0.08
LEARNED_RESIDUAL_MIN_Q90 = 18.0
LEARNED_RESIDUAL_MIN_MAX = 24.0


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


def _fit_template(image: np.ndarray, *, binary: bool = False) -> np.ndarray:
    """Вписать локальный фрагмент в квадрат, не искажая его пропорции."""
    height, width = image.shape[:2]
    if height <= 0 or width <= 0:
        return np.zeros((TEMPLATE_SIZE, TEMPLATE_SIZE), dtype=np.uint8)
    interpolation = cv2.INTER_NEAREST if binary else cv2.INTER_AREA
    scale = min(TEMPLATE_INNER_SIZE / width, TEMPLATE_INNER_SIZE / height)
    resized_width = max(1, round(width * scale))
    resized_height = max(1, round(height * scale))
    resized = cv2.resize(image, (resized_width, resized_height), interpolation=interpolation)
    canvas = np.zeros((TEMPLATE_SIZE, TEMPLATE_SIZE), dtype=np.uint8)
    x = (TEMPLATE_SIZE - resized_width) // 2
    y = (TEMPLATE_SIZE - resized_height) // 2
    canvas[y : y + resized_height, x : x + resized_width] = resized
    if binary:
        return np.where(canvas > 0, 255, 0).astype(np.uint8)
    return canvas.astype(np.uint8)


def _convert_legacy_template(
    template: np.ndarray,
    bbox_norm: tuple[float, float, float, float],
    *,
    binary: bool = False,
) -> np.ndarray:
    """Приблизительно восстановить пропорции старого растянутого шаблона."""
    _, _, width_norm, height_norm = bbox_norm
    if width_norm <= 0.0 or height_norm <= 0.0:
        return _fit_template(template, binary=binary)
    if width_norm >= height_norm:
        restored_width = TEMPLATE_INNER_SIZE
        restored_height = max(1, round(TEMPLATE_INNER_SIZE * height_norm / width_norm))
    else:
        restored_height = TEMPLATE_INNER_SIZE
        restored_width = max(1, round(TEMPLATE_INNER_SIZE * width_norm / height_norm))
    interpolation = cv2.INTER_NEAREST if binary else cv2.INTER_AREA
    restored = cv2.resize(template, (restored_width, restored_height), interpolation=interpolation)
    canvas = np.zeros((TEMPLATE_SIZE, TEMPLATE_SIZE), dtype=np.uint8)
    x = (TEMPLATE_SIZE - restored_width) // 2
    y = (TEMPLATE_SIZE - restored_height) // 2
    canvas[y : y + restored_height, x : x + restored_width] = restored
    if binary:
        return np.where(canvas > 0, 255, 0).astype(np.uint8)
    return canvas.astype(np.uint8)


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
    source_crop: Optional[np.ndarray] = field(default=None, repr=False)
    matched_case_id: Optional[str] = None
    similarity: Optional[float] = None
    _geometry_cache: Optional["_MaskGeometry"] = field(default=None, repr=False, compare=False)

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
    template_version: int
    mask_template: np.ndarray = field(repr=False)
    diff_template: np.ndarray = field(repr=False)
    appearance_template: np.ndarray = field(repr=False)
    source_crop: Optional[np.ndarray] = field(default=None, repr=False)
    _geometry_cache: Optional["_MaskGeometry"] = field(default=None, repr=False, compare=False)
    _template_cache: Optional[tuple[np.ndarray, np.ndarray, np.ndarray]] = field(
        default=None,
        repr=False,
        compare=False,
    )

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
            "template_version": self.template_version,
        }


@dataclass
class LearnedFilterResult:
    filtered_diff_map: np.ndarray
    filtered_mask: np.ndarray
    candidates: list[DefectCandidate]
    matched_case_ids: list[str]
    matched_candidates_count: int = 0
    all_important_candidates_matched: bool = False
    original_max_candidate_impact: float = 0.0


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

    def unmark_case(self, case_id: str) -> None:
        """Убрать удалённый пример из ещё живых операторских review."""
        with self._lock:
            for review in self._items.values():
                changed = False
                for candidate in review.defects:
                    if candidate.matched_case_id != case_id:
                        continue
                    candidate.matched_case_id = None
                    candidate.similarity = None
                    review.accepted_defect_ids.discard(candidate.id)
                    changed = True
                if changed:
                    review.counterfactual_score = None
                    review.counterfactual_status = None


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

    def image(self, case_id: str) -> Optional[tuple[bytes, str]]:
        """Наглядный crop сохранённого фрагмента с подсвеченной маской."""
        with self._lock:
            case = self._cases.get(case_id)
            if case is None:
                return None
            appearance = case.appearance_template.copy()
            mask = case.mask_template.copy() > 0

        preview = cv2.cvtColor(appearance, cv2.COLOR_GRAY2BGR)
        tint = preview.copy()
        tint[mask] = (55, 210, 95)
        preview = cv2.addWeighted(preview, 0.62, tint, 0.38, 0.0)
        contours, _ = cv2.findContours(
            mask.astype(np.uint8) * 255,
            cv2.RETR_EXTERNAL,
            cv2.CHAIN_APPROX_SIMPLE,
        )
        cv2.drawContours(preview, contours, -1, (70, 255, 125), 1)
        return _encode(preview, ".png"), "image/png"

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
            template_version=TEMPLATE_VERSION,
            mask_template=candidate.mask_template.copy(),
            diff_template=candidate.diff_template.copy(),
            appearance_template=candidate.appearance_template.copy(),
            source_crop=(candidate.source_crop.copy() if candidate.source_crop is not None else None),
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

        important_candidate_ids = {
            candidate.id for candidate in filter_review_candidates(candidates)
        }
        original_max_candidate_impact = max(
            (candidate_review_impact(candidate) for candidate in candidates),
            default=0.0,
        )
        matched_candidate_ids: set[str] = set()
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
            matched_candidate_ids.add(candidate.id)
            x, y, box_width, box_height = candidate.bbox
            # Большая допустимая область (например, засвет) может содержать
            # новый локальный дефект. Для новых норм сохраняется исходный BGR
            # crop: сравнение с ним оставляет цветовой остаток, вместо удаления
            # всей connected component целиком.
            use_color_residual = (
                best_case.source_crop is not None
                and best_case.source_crop.size > 0
                and box_width * box_height >= 2048
                # Residual subtraction is intended for broad connected regions
                # such as glare. Thin scratches and L-shaped traces still use
                # shape-normalized suppression so shifted/scaled copies remain
                # acceptable as before.
                and candidate.area / max(1, box_width * box_height) >= 0.35
                and (box_width * box_height) / max(1, aligned.shape[0] * aligned.shape[1]) >= 0.10
            )
            if use_color_residual:
                residual_padding = 2
                residual_x0 = max(0, x - residual_padding)
                residual_y0 = max(0, y - residual_padding)
                residual_x1 = min(filtered_diff.shape[1], x + box_width + residual_padding)
                residual_y1 = min(filtered_diff.shape[0], y + box_height + residual_padding)
                # Remove the morphology halo of the accepted component first;
                # the actual bbox is then replaced with the colour residual.
                filtered_diff[residual_y0:residual_y1, residual_x0:residual_x1] = 0
                filtered_mask[residual_y0:residual_y1, residual_x0:residual_x1] = 0
                expected = cv2.resize(
                    best_case.source_crop,
                    (box_width, box_height),
                    interpolation=cv2.INTER_LINEAR,
                )
                current = aligned[y : y + box_height, x : x + box_width]
                color_residual = np.max(
                    cv2.absdiff(current, expected),
                    axis=2,
                ).astype(np.int16)
                color_residual = np.clip(color_residual - 12, 0, 255).astype(np.uint8)
                residual_bgr = cv2.cvtColor(color_residual, cv2.COLOR_GRAY2BGR)
                filtered_diff[y : y + box_height, x : x + box_width] = residual_bgr
                matched_case_ids.append(best_case.id)
                continue
            # Убираем также узкий ореол морфологии вокруг совпавшей компоненты,
            # иначе остаточный diff продолжает повышать score уже «разрешённого»
            # фрагмента. Радиус мал и применяется только после строгого match.
            # Candidate хранит только локальную маску своего bbox. Это не создаёт по одной
            # полноразмерной копии маски на каждый компонент (особенно дорого на 5-Мп кадрах).
            # Padding сохраняет прежний двухпиксельный ореол подавления и за пределами bbox.
            self._suppress_candidate(filtered_diff, filtered_mask, candidate)
            matched_case_ids.append(best_case.id)

        # После вычитания нормы слабые остатки нельзя ранжировать заново как
        # «самые важные»: иначе тот же принятый оператором кадр снова становится
        # БРАК. Если совпали все исходно значимые области, подавляем только
        # незначимые компоненты исходного кадра. Новый значимый дефект не даст
        # этому условию выполниться и продолжит влиять на вердикт.
        if (
            important_candidate_ids
            and important_candidate_ids.issubset(matched_candidate_ids)
        ):
            for candidate in candidates:
                if candidate.id in matched_candidate_ids:
                    continue
                self._suppress_candidate(filtered_diff, filtered_mask, candidate)

        return LearnedFilterResult(
            filtered_diff_map=filtered_diff,
            filtered_mask=filtered_mask,
            candidates=candidates,
            matched_case_ids=list(dict.fromkeys(matched_case_ids)),
            matched_candidates_count=len(matched_case_ids),
            all_important_candidates_matched=bool(important_candidate_ids)
            and important_candidate_ids.issubset(matched_candidate_ids),
            original_max_candidate_impact=original_max_candidate_impact,
        )

    @staticmethod
    def _suppress_candidate(
        filtered_diff: np.ndarray,
        filtered_mask: np.ndarray,
        candidate: DefectCandidate,
    ) -> None:
        """Удалить локальную компоненту вместе с узким морфологическим ореолом."""
        x, y, box_width, box_height = candidate.bbox
        radius = 2
        padded = cv2.copyMakeBorder(
            candidate.mask.astype(np.uint8) * 255,
            radius,
            radius,
            radius,
            radius,
            cv2.BORDER_CONSTANT,
            value=0,
        )
        suppress_local = cv2.dilate(
            padded,
            np.ones((5, 5), dtype=np.uint8),
            iterations=1,
        ) > 0
        x0 = max(0, x - radius)
        y0 = max(0, y - radius)
        x1 = min(filtered_diff.shape[1], x + box_width + radius)
        y1 = min(filtered_diff.shape[0], y + box_height + radius)
        crop_x0 = x0 - (x - radius)
        crop_y0 = y0 - (y - radius)
        crop_x1 = crop_x0 + (x1 - x0)
        crop_y1 = crop_y0 + (y1 - y0)
        suppress_crop = suppress_local[crop_y0:crop_y1, crop_x0:crop_x1]
        diff_region = filtered_diff[y0:y1, x0:x1]
        mask_region = filtered_mask[y0:y1, x0:x1]
        diff_region[suppress_crop] = 0
        mask_region[suppress_crop] = 0

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
                source_crop=case.source_crop if case.source_crop is not None else np.empty((0, 0, 3), dtype=np.uint8),
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
                        template_version=int(payload.get("template_version", 1)),
                        mask_template=arrays["mask_template"].copy(),
                        diff_template=arrays["diff_template"].copy(),
                        appearance_template=arrays["appearance_template"].copy(),
                        source_crop=(
                            arrays["source_crop"].copy()
                            if "source_crop" in arrays.files and arrays["source_crop"].size > 0
                            else None
                        ),
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
    height, width = binary.shape[:2]
    # Тонкий след может разорваться порогом на несколько близких островков.
    # Dilation используется только для назначения островков одной группе. Сама
    # candidate.mask ниже пересекается с исходной binary, поэтому заполненный
    # промежуток не вычитается из diff и не скрывает соседние реальные данные.
    grouping_radius = max(1, min(7, round(min(height, width) * 0.025)))
    grouping_kernel = np.ones((grouping_radius * 2 + 1, grouping_radius * 2 + 1), dtype=np.uint8)
    grouping_binary = cv2.dilate(binary, grouping_kernel, iterations=1)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(grouping_binary, connectivity=8)
    diff_gray = _gray(diff_map)
    aligned_gray = _gray(aligned)
    candidates: list[DefectCandidate] = []

    raw_candidates: list[tuple[int, int, int, int, int, int]] = []
    for label_idx in range(1, count):
        group_x = int(stats[label_idx, cv2.CC_STAT_LEFT])
        group_y = int(stats[label_idx, cv2.CC_STAT_TOP])
        group_width = int(stats[label_idx, cv2.CC_STAT_WIDTH])
        group_height = int(stats[label_idx, cv2.CC_STAT_HEIGHT])
        group_labels = labels[group_y : group_y + group_height, group_x : group_x + group_width]
        group_binary = binary[group_y : group_y + group_height, group_x : group_x + group_width]
        component_local = (group_labels == label_idx) & (group_binary > 0)
        y_points, x_points = np.where(component_local)
        area = int(x_points.size)
        if area <= 0:
            continue
        x = group_x + int(np.min(x_points))
        y = group_y + int(np.min(y_points))
        # x_points/y_points локальны относительно group bbox.
        box_width = int(np.max(x_points) - np.min(x_points) + 1)
        box_height = int(np.max(y_points) - np.min(y_points) + 1)
        raw_candidates.append((y, x, label_idx, area, box_width, box_height))

    raw_candidates.sort(key=lambda entry: (entry[0], entry[1]))
    for ordinal, (y, x, label_idx, area, box_width, box_height) in enumerate(raw_candidates, start=1):
        local_labels = labels[y : y + box_height, x : x + box_width]
        local_binary = binary[y : y + box_height, x : x + box_width]
        local_mask = (local_labels == label_idx) & (local_binary > 0)
        local_diff = diff_gray[y : y + box_height, x : x + box_width]
        local_aligned = aligned_gray[y : y + box_height, x : x + box_width]
        values = local_diff[local_mask]
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
            # У сгруппированного прерывистого следа может быть несколько
            # контуров. Общая оболочка делает его целиком кликабельным в UI.
            contour = cv2.convexHull(np.vstack(contours))
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
                mask=local_mask.copy(),
                mask_template=_fit_template(local_mask.astype(np.uint8) * 255, binary=True),
                diff_template=_fit_template(local_diff),
                appearance_template=_fit_template(local_aligned),
                source_crop=aligned[y : y + box_height, x : x + box_width].copy(),
            )
        )
    return candidates


def candidate_review_impact(candidate: DefectCandidate) -> float:
    """Дешёвая оценка вклада отдельной области в решение инспектора.

    Суммарная diff-энергия делает крупную область важнее одиночного шумового
    пикселя, а умеренная прибавка за вытянутость не даёт потерять тонкую
    царапину. Это только ранжирование для UI, не новый производственный score.
    """
    _, _, box_width, box_height = candidate.bbox
    short_side = max(1, min(box_width, box_height))
    aspect = max(box_width, box_height) / short_side
    diff_level = (
        candidate.diff_mean * 0.45
        + candidate.diff_q90 * 0.35
        + candidate.diff_max * 0.20
    )
    elongation_boost = 1.0 + 0.12 * min(5.0, max(0.0, aspect - 1.0))
    return max(0.0, float(candidate.area) * diff_level * elongation_boost)


def filter_review_candidates(
    candidates: list[DefectCandidate],
    min_relative_impact: float = REVIEW_MIN_RELATIVE_IMPACT,
    baseline_maximum_impact: Optional[float] = None,
) -> list[DefectCandidate]:
    """Оставить области со значимым вкладом относительно текущего кадра.

    Порог не зависит от числа пикселей или разрешения: остаются компоненты,
    влияние которых составляет не менее ``min_relative_impact`` от максимума
    среди дефектов этого изделия. Самая влиятельная область всегда остаётся.

    Это исключительно UI/review-фильтр. Основная маска, итоговый score и
    сопоставление с сохранёнными нормами продолжают использовать все области.
    """
    if not candidates:
        return []

    relative_threshold = float(np.clip(min_relative_impact, 0.0, 1.0))
    impacts = [candidate_review_impact(candidate) for candidate in candidates]
    maximum_impact = (
        float(baseline_maximum_impact)
        if baseline_maximum_impact is not None
        else max(impacts)
    )
    if maximum_impact <= 0.0:
        return candidates[:1]

    cutoff = maximum_impact * relative_threshold
    scratch_cutoff = maximum_impact * min(
        relative_threshold,
        REVIEW_SIGNIFICANT_SCRATCH_MIN_RELATIVE_IMPACT,
    )
    return [
        candidate
        for candidate, impact in zip(candidates, impacts)
        if impact >= cutoff
        or (
            baseline_maximum_impact is not None
            and candidate.diff_q90 >= LEARNED_RESIDUAL_MIN_Q90
            and candidate.diff_max >= LEARNED_RESIDUAL_MIN_MAX
        )
        or (
            max(candidate.bbox[2], candidate.bbox[3])
            / max(1, min(candidate.bbox[2], candidate.bbox[3]))
            >= 8.0
            and candidate.diff_q90 >= REVIEW_SIGNIFICANT_SCRATCH_MIN_Q90
            and impact >= scratch_cutoff
        )
    ]


@dataclass(frozen=True)
class _MaskGeometry:
    aspect: float
    fill_ratio: float
    elongation: float
    angle_degrees: float
    compactness: float
    solidity: float
    component_count: int
    contour_vertices: int
    largest_contour: np.ndarray = field(repr=False, compare=False)


def _mask_geometry(mask: np.ndarray) -> Optional[_MaskGeometry]:
    binary = np.where(mask > 0, 255, 0).astype(np.uint8)
    y_points, x_points = np.where(binary > 0)
    if x_points.size < 3:
        return None

    width = int(np.max(x_points) - np.min(x_points) + 1)
    height = int(np.max(y_points) - np.min(y_points) + 1)
    area = float(x_points.size)
    aspect = width / max(1.0, float(height))
    fill_ratio = area / max(1.0, float(width * height))

    coordinates = np.column_stack((x_points.astype(np.float32), y_points.astype(np.float32)))
    centered = coordinates - np.mean(coordinates, axis=0, keepdims=True)
    covariance = centered.T @ centered / max(1, coordinates.shape[0] - 1)
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)
    major_index = int(np.argmax(eigenvalues))
    major_value = max(1e-6, float(eigenvalues[major_index]))
    minor_value = max(1e-6, float(eigenvalues[1 - major_index]))
    elongation = math.sqrt(major_value / minor_value)
    major_vector = eigenvectors[:, major_index]
    angle_degrees = math.degrees(math.atan2(float(major_vector[1]), float(major_vector[0]))) % 180.0

    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    component_count, _ = cv2.connectedComponents(binary, connectivity=8)
    perimeter = sum(float(cv2.arcLength(contour, True)) for contour in contours)
    compactness = float(np.clip((4.0 * math.pi * area) / max(1e-6, perimeter * perimeter), 0.0, 1.0))
    hull = cv2.convexHull(np.column_stack((x_points, y_points)).astype(np.int32))
    hull_area = max(1.0, float(cv2.contourArea(hull)))
    solidity = float(np.clip(area / hull_area, 0.0, 1.0))
    largest_contour = max(contours, key=cv2.contourArea)
    contour_perimeter = float(cv2.arcLength(largest_contour, True))
    approximated = cv2.approxPolyDP(largest_contour, max(1.0, contour_perimeter * 0.04), True)
    return _MaskGeometry(
        aspect=aspect,
        fill_ratio=fill_ratio,
        elongation=elongation,
        angle_degrees=angle_degrees,
        compactness=compactness,
        solidity=solidity,
        component_count=max(0, int(component_count) - 1),
        contour_vertices=int(len(approximated)),
        largest_contour=largest_contour,
    )


def _ratio_similarity(first: float, second: float) -> float:
    high = max(abs(first), abs(second), 1e-6)
    return float(np.clip(min(abs(first), abs(second)) / high, 0.0, 1.0))


def _angle_distance(first: float, second: float) -> float:
    difference = abs(first - second) % 180.0
    return min(difference, 180.0 - difference)


def _case_templates(case: AcceptedNormalCase) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    if case._template_cache is not None:
        return case._template_cache
    # Version 3 adds a source-resolution colour crop, but does not change the
    # normalized shape templates introduced in version 2.
    if case.template_version >= 2:
        templates = (case.mask_template, case.diff_template, case.appearance_template)
    else:
        templates = (
            _convert_legacy_template(case.mask_template, case.bbox_norm, binary=True),
            _convert_legacy_template(case.diff_template, case.bbox_norm),
            _convert_legacy_template(case.appearance_template, case.bbox_norm),
        )
    case._template_cache = templates
    return templates


def _contour_match_distance(first: _MaskGeometry, second: _MaskGeometry) -> float:
    return float(
        cv2.matchShapes(
            first.largest_contour,
            second.largest_contour,
            cv2.CONTOURS_MATCH_I1,
            0.0,
        )
    )


def _mask_overlap_metrics(first_mask: np.ndarray, second_mask: np.ndarray) -> tuple[float, float]:
    """Вернуть устойчивую близость формы и Dice для двух нормализованных масок."""
    first = np.asarray(first_mask, dtype=bool)
    second = np.asarray(second_mask, dtype=bool)
    first_u8 = first.astype(np.uint8) * 255
    second_u8 = second.astype(np.uint8) * 255
    distance_to_first = cv2.distanceTransform(255 - first_u8, cv2.DIST_L2, 3)
    distance_to_second = cv2.distanceTransform(255 - second_u8, cv2.DIST_L2, 3)
    symmetric_distance = (
        float(np.mean(distance_to_first[second]))
        + float(np.mean(distance_to_second[first]))
    ) * 0.5
    distance_scale = TEMPLATE_SIZE * math.sqrt(2.0) * 0.20
    tolerant_similarity = max(0.0, 1.0 - symmetric_distance / distance_scale)
    intersection = float(np.count_nonzero(first & second))
    dice_similarity = (2.0 * intersection) / max(
        1.0,
        float(np.count_nonzero(first) + np.count_nonzero(second)),
    )
    return tolerant_similarity, dice_similarity


def _diff_core_mask(diff_template: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Выделить устойчивое ядро отличия, менее зависимое от морфологии общей маски."""
    binary_mask = np.asarray(mask, dtype=bool)
    values = diff_template[binary_mask]
    if values.size == 0:
        return binary_mask
    threshold = max(8.0, float(np.percentile(values, 50)))
    core = binary_mask & (diff_template.astype(np.float32) >= threshold)
    if np.count_nonzero(core) < 3:
        return binary_mask
    return cv2.dilate(
        core.astype(np.uint8),
        np.ones((3, 3), dtype=np.uint8),
        iterations=1,
    ) > 0


def candidate_similarity(candidate: DefectCandidate, case: AcceptedNormalCase) -> Optional[float]:
    """Сопоставить форму и размер только рядом с местом сохранённой нормы."""
    cx, cy, cw, ch = candidate.bbox_norm
    sx, sy, sw, sh = case.bbox_norm

    candidate_center = (cx + cw * 0.5, cy + ch * 0.5)
    sample_center = (sx + sw * 0.5, sy + sh * 0.5)
    position_distance = math.hypot(
        candidate_center[0] - sample_center[0],
        candidate_center[1] - sample_center[1],
    )
    # Проверка выполняется до дорогого сравнения шаблонов. Координаты нормированы,
    # поэтому допуск одинаков по смыслу при полном кадре и inspect_scale=0.5.
    if position_distance > POSITION_TOLERANCE_NORM:
        return None

    candidate_area_norm = max(1e-9, cw * ch)
    sample_area_norm = max(1e-9, sw * sh)
    bbox_area_ratio = candidate_area_norm / sample_area_norm
    # Нижней границы нет: любая уменьшенная версия подтверждённого следа может
    # совпасть. Увеличение ограничено, чтобы большая аномалия не наследовала
    # исключение от маленького примера.
    if bbox_area_ratio >= 1.55:
        return None

    if candidate.diff_q90 > max(case.diff_q90 * 1.60, case.diff_q90 + 12.0):
        return None
    maximum_diff_max = (
        max(case.diff_max * 1.75, case.diff_max + 35.0)
        if bbox_area_ratio <= 1.0
        else max(case.diff_max * 1.50, case.diff_max + 20.0)
    )
    if candidate.diff_max > maximum_diff_max:
        return None

    case_mask_template, case_diff_template, case_appearance_template = _case_templates(case)
    candidate_mask = candidate.mask_template > 0
    candidate_diff_template = candidate.diff_template
    candidate_appearance_template = candidate.appearance_template
    sample_mask = case_mask_template > 0
    candidate_geometry = candidate._geometry_cache
    if candidate_geometry is None:
        candidate_geometry = _mask_geometry(candidate_mask)
        candidate._geometry_cache = candidate_geometry
    sample_geometry = case._geometry_cache
    if sample_geometry is None:
        sample_geometry = _mask_geometry(sample_mask)
        case._geometry_cache = sample_geometry
    if candidate_geometry is None or sample_geometry is None:
        return None

    sample_is_thin_trace = (
        sample_geometry.fill_ratio <= 0.35
        and sample_geometry.compactness <= 0.25
    )
    # Для изогнутого тонкого следа PCA-направление определяется длиной его плеч и скачет
    # при уменьшении/разрыве маски. Сравниваем четыре поворота шаблона и выбираем тот,
    # который лучше совмещает именно форму. Прямые царапины сюда не попадают благодаря
    # высокой solidity, поэтому горизонтальная линия не становится вертикальной нормой.
    sample_is_bent_trace = (
        sample_is_thin_trace
        and sample_geometry.solidity <= 0.58
        and sample_geometry.contour_vertices >= 4
    )
    if sample_is_bent_trace:
        best_rotation = 0
        best_rotation_quality = -1.0
        for rotation in range(4):
            rotated_mask = np.rot90(candidate_mask, rotation)
            tolerant, dice = _mask_overlap_metrics(rotated_mask, sample_mask)
            quality = tolerant * 0.65 + dice * 0.35
            if quality > best_rotation_quality:
                best_rotation_quality = quality
                best_rotation = rotation
        if best_rotation:
            candidate_mask = np.rot90(candidate_mask, best_rotation)
            candidate_diff_template = np.rot90(candidate_diff_template, best_rotation)
            candidate_appearance_template = np.rot90(candidate_appearance_template, best_rotation)
            rotated_geometry = _mask_geometry(candidate_mask)
            if rotated_geometry is not None:
                candidate_geometry = rotated_geometry

    aspect_similarity = _ratio_similarity(candidate_geometry.aspect, sample_geometry.aspect)
    fill_similarity = _ratio_similarity(candidate_geometry.fill_ratio, sample_geometry.fill_ratio)
    compactness_similarity = _ratio_similarity(candidate_geometry.compactness, sample_geometry.compactness)
    solidity_similarity = _ratio_similarity(candidate_geometry.solidity, sample_geometry.solidity)
    elongation_similarity = _ratio_similarity(candidate_geometry.elongation, sample_geometry.elongation)
    tolerant_shape_similarity, dice_similarity = _mask_overlap_metrics(candidate_mask, sample_mask)
    candidate_core_mask = _diff_core_mask(candidate_diff_template, candidate_mask)
    sample_core_mask = _diff_core_mask(case_diff_template, sample_mask)
    core_tolerant_similarity, core_dice_similarity = _mask_overlap_metrics(
        candidate_core_mask,
        sample_core_mask,
    )
    scaled_core_geometry_candidate = (
        bbox_area_ratio <= 0.80
        and tolerant_shape_similarity >= 0.88
        and dice_similarity >= 0.55
        and core_tolerant_similarity >= 0.93
        and core_dice_similarity >= 0.55
        and aspect_similarity >= 0.78
        and elongation_similarity >= 0.75
    )

    # Направление важно только для явно вытянутых следов. Для почти круглых пятен
    # PCA-угол нестабилен и не должен мешать совпадению.
    if min(candidate_geometry.elongation, sample_geometry.elongation) >= 1.8:
        maximum_angle_distance = 45.0 if sample_is_bent_trace else 30.0
        if _angle_distance(candidate_geometry.angle_degrees, sample_geometry.angle_degrees) > maximum_angle_distance:
            return None
    one_is_elongated = max(candidate_geometry.elongation, sample_geometry.elongation) >= 2.5
    other_is_compact = min(candidate_geometry.elongation, sample_geometry.elongation) <= 1.45
    if one_is_elongated and other_is_compact:
        return None

    # Эти проверки масштабонезависимы: абсолютная площадь не участвует. Положение
    # уже проверено выше по нормированному расстоянию между центрами.
    if aspect_similarity < 0.40:
        return None
    minimum_fill_similarity = 0.30 if sample_is_thin_trace else 0.38
    if fill_similarity < minimum_fill_similarity:
        return None
    minimum_compactness_similarity = 0.28 if sample_is_thin_trace else 0.33
    if (
        compactness_similarity < minimum_compactness_similarity
        or solidity_similarity < 0.42
    ) and not scaled_core_geometry_candidate:
        return None

    comparison_region = cv2.dilate(
        np.where(candidate_mask | sample_mask, 255, 0).astype(np.uint8),
        np.ones((3, 3), dtype=np.uint8),
        iterations=1,
    ) > 0
    if not np.any(comparison_region):
        return None
    diff_similarity = 1.0 - float(
        np.mean(
            np.abs(
                candidate_diff_template.astype(np.float32)
                - case_diff_template.astype(np.float32)
            )[comparison_region]
        )
        / 255.0
    )
    appearance_similarity = 1.0 - float(
        np.mean(
            np.abs(
                candidate_appearance_template.astype(np.float32)
                - case_appearance_template.astype(np.float32)
            )[comparison_region]
        )
        / 255.0
    )
    stable_scaled_shape_candidate = (
        scaled_core_geometry_candidate
        and diff_similarity >= 0.82
        and appearance_similarity >= 0.70
    )

    # Узкий режим для уменьшенной разорванной версии сохранённой тонкой трассы.
    # Он не применяется к пятнам/сколам и не разрешает увеличение. Такой след
    # может распасться порогом на 2–3 островка на тёмной печатной этикетке.
    partial_trace_candidate = (
        sample_geometry.fill_ratio <= 0.25
        and sample_geometry.compactness <= 0.18
        and sample_geometry.elongation >= 2.2
        and 2 <= candidate_geometry.component_count <= 3
        and bbox_area_ratio <= 0.50
        and aspect_similarity >= 0.35
        and diff_similarity >= 0.82
        and appearance_similarity >= 0.55
    )

    # IoU слишком чувствителен к длине отдельных плеч царапины/следа. Среднее
    # расстояние между обеими масками терпит такую деформацию, но остаётся
    # симметричным: и текущая, и сохранённая форма должны объяснять друг друга.
    if partial_trace_candidate:
        partial_similarity = (
            tolerant_shape_similarity * 0.40
            + aspect_similarity * 0.15
            + fill_similarity * 0.10
            + diff_similarity * 0.25
            + appearance_similarity * 0.10
        )
        return float(partial_similarity) if partial_similarity >= 0.66 else None

    stable_thin_trace_candidate = (
        sample_is_thin_trace
        and candidate_geometry.component_count == 1
        and _angle_distance(candidate_geometry.angle_degrees, sample_geometry.angle_degrees)
        <= (45.0 if sample_is_bent_trace else 25.0)
        and tolerant_shape_similarity >= (0.70 if sample_is_bent_trace else 0.84)
        and dice_similarity >= (0.28 if sample_is_bent_trace else 0.30)
        and aspect_similarity >= (0.38 if sample_is_bent_trace else 0.55)
        and elongation_similarity >= 0.55
        and diff_similarity >= 0.75
        and appearance_similarity >= 0.50
    )

    if (
        not stable_thin_trace_candidate
        and not stable_scaled_shape_candidate
        and (tolerant_shape_similarity < 0.72 or dice_similarity < 0.32)
    ):
        return None
    if diff_similarity < 0.70 or appearance_similarity < 0.45:
        return None

    geometry_similarity = (
        tolerant_shape_similarity * 0.35
        + dice_similarity * 0.25
        + aspect_similarity * 0.10
        + fill_similarity * 0.08
        + compactness_similarity * 0.07
        + solidity_similarity * 0.08
        + elongation_similarity * 0.07
    )
    both_filled_compact = (
        max(candidate_geometry.elongation, sample_geometry.elongation) < 2.2
        and min(candidate_geometry.fill_ratio, sample_geometry.fill_ratio) >= 0.55
    )
    if both_filled_compact and not stable_scaled_shape_candidate:
        if dice_similarity < 0.83 or aspect_similarity < 0.78:
            return None
        contour_distance = _contour_match_distance(candidate_geometry, sample_geometry)
        if contour_distance > 0.25:
            return None
        vertex_difference = abs(
            candidate_geometry.contour_vertices - sample_geometry.contour_vertices
        )
        if vertex_difference > 2 or (vertex_difference >= 2 and contour_distance > 0.20):
            return None
    if (
        geometry_similarity < 0.72
        and not stable_thin_trace_candidate
        and not stable_scaled_shape_candidate
    ):
        return None

    # Уменьшенная версия одного и того же следа может немного не добрать общий
    # порог из-за иного соотношения длины его плеч. Это особенно заметно у
    # L-образных следов, которые на одном кадре сегментируются одним контуром,
    # а на другом — двумя близкими островками. Послабление узкое: положение уже
    # проверено выше, увеличение размера запрещено, а форма, контраст и внешний
    # вид должны оставаться близкими. Общий порог для остальных кандидатов не
    # меняется.
    stable_reduced_shape_candidate = (
        bbox_area_ratio <= 1.0
        and tolerant_shape_similarity >= 0.84
        and dice_similarity >= 0.42
        and aspect_similarity >= 0.60
        and diff_similarity >= 0.85
        and appearance_similarity >= 0.72
        and abs(candidate_geometry.contour_vertices - sample_geometry.contour_vertices) <= 1
    )

    # Уменьшение не штрафуется. Для допустимого увеличения остаётся небольшой
    # штраф, а основной вес имеют форма, diff и внешний вид фрагмента.
    scale_similarity = 1.0 if bbox_area_ratio <= 1.0 else 1.0 / bbox_area_ratio
    similarity = (
        geometry_similarity * 0.70
        + diff_similarity * 0.18
        + appearance_similarity * 0.07
        + scale_similarity * 0.05
    )
    if stable_thin_trace_candidate:
        minimum_similarity = THIN_TRACE_MIN_SIMILARITY
    elif stable_scaled_shape_candidate:
        minimum_similarity = SCALED_SHAPE_MIN_SIMILARITY
    elif stable_reduced_shape_candidate:
        minimum_similarity = REDUCED_SHAPE_MIN_SIMILARITY
    else:
        minimum_similarity = GENERAL_MIN_SIMILARITY
    if similarity < minimum_similarity:
        return None
    return float(similarity)


def decode_review_arrays(review: InspectionReview) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    return (
        _decode(review.aligned_jpeg, cv2.IMREAD_COLOR),
        _decode(review.diff_png, cv2.IMREAD_COLOR),
        _decode(review.raw_mask_png, cv2.IMREAD_COLOR),
    )
