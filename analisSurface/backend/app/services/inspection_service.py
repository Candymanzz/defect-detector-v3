import base64
import json
import logging
import threading
import uuid
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np
from PIL import Image

from app.runtime import get_application_id
from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import (
    combine_region_masks,
    mask_to_polygon,
    polygon_area,
    polygon_bbox_from_norm_points,
    polygon_mask_from_norm_points,
    padded_bbox_polygon,
    validate_polygon_inside_parent,
    validate_polygon_points,
)
from app.services.inspection_models import FPZone, FPZoneScore, InspectionResult, RoiSubZone, RoiSubZoneScore
from app.services.learned_normals import (
    AcceptedNormalMemory,
    InspectionReviewStore,
    decode_review_arrays,
    extract_defect_candidates,
    filter_review_candidates,
    reference_fingerprint,
)


logger = logging.getLogger(__name__)

_FP_CROP_MIN = 64


class InspectionService:
    """Ядро инспекции: выравнивание, diff, детекция аномалий, FP мини-эталоны, вердикт.

    Публичная точка входа для анализа — inspect_frame().
    CRUD по ROI/зонам и загрузка JSON вынесены в отдельные методы без алгоритмической логики.
    """
    def __init__(
        self,
        learned_normals_dir: Optional[Path] = None,
        review_limit: Optional[int] = None,
        reviews_dir: Optional[Path] = None,
        session_wipe: bool = True,
        learned_normals_session_wipe: Optional[bool] = None,
    ) -> None:
        self.references: Dict[str, np.ndarray] = {}
        self._reference_hashes: Dict[str, str] = {}
        self._ref_orb_cache: Dict[str, Tuple[list, Optional[np.ndarray]]] = {}
        self.roi_polygons: Dict[str, list[Tuple[float, float]]] = {}
        self.roi_sub_zones: Dict[str, list[RoiSubZone]] = {}
        self._roi_sub_zones_file = Path(__file__).resolve().parent.parent / "data" / "roi_sub_zones.json"
        self._analysis_settings_file = Path(__file__).resolve().parent.parent / "data" / "analysis_settings.json"
        self._analysis_settings_overrides: Dict[str, dict[str, object]] = {}
        self._analysis_settings_simple_knobs: Dict[str, dict[str, object]] = {}
        self._analysis_settings_pro_knobs: Dict[str, dict[str, object]] = {}
        self._analysis_settings_lock = threading.Lock()
        self._analysis_settings_mtime_ns = -1
        self._orb = cv2.ORB_create(nfeatures=1800)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        self._fp_zones_file = Path(__file__).resolve().parent.parent / "data" / "fp_zones.json"
        self._fp_crops_dir = Path(__file__).resolve().parent.parent / "data" / "fp_zone_crops"
        self.fp_zones: Dict[str, list[FPZone]] = {}
        self._last_diff_maps: Dict[str, np.ndarray] = {}
        self._last_segmentation_masks: Dict[str, np.ndarray] = {}
        self._last_aligned: Dict[str, np.ndarray] = {}
        self._last_aligned_ref_hash: Dict[str, str] = {}
        data_dir = Path(__file__).resolve().parent.parent / "data"
        self._accepted_normals = AcceptedNormalMemory(
            learned_normals_dir if learned_normals_dir is not None else data_dir / "accepted_normals",
            session_wipe=session_wipe if learned_normals_session_wipe is None else learned_normals_session_wipe,
        )
        self._learning_reviews = InspectionReviewStore(
            max_items=review_limit,
            storage_dir=reviews_dir if reviews_dir is not None else data_dir / "learning_reviews",
            session_wipe=session_wipe,
        )

        self._anomaly_engine = None
        self._load_anomalib_engine()
        self._load_fp_zones()
        self._load_roi_sub_zones()
        self._load_analysis_settings()
        self._stamp_analysis_settings_mtime()

    def _load_anomalib_engine(self) -> None:
        try:
            from anomalib.deploy import OpenVINOInferencer  # type: ignore

            self._anomaly_engine = OpenVINOInferencer(
                path="models/patchcore/openvino/model.xml" # model path
            )
        except Exception:
            self._anomaly_engine = None

    def set_reference(self, product_type: str, image_bytes: bytes) -> None:
        image = self._decode_image(image_bytes)
        self.references[product_type] = image
        self._reference_hashes[product_type] = reference_fingerprint(image)
        self._update_ref_orb_cache(product_type, image)

    def set_reference_frame(self, product_type: str, frame: np.ndarray) -> None:
        image = frame.copy()
        self.references[product_type] = image
        self._reference_hashes[product_type] = reference_fingerprint(image)
        self._update_ref_orb_cache(product_type, image)

    def get_reference(self, product_type: str) -> Optional[np.ndarray]:
        return self.references.get(product_type)

    def clear_inspection_context(self) -> dict[str, int]:
        """Полный сброс runtime-контекста инспекции: эталоны, ROI, FP-зоны."""
        cleared = {
            "references": len(self.references),
            "roi_polygons": len(self.roi_polygons),
            "roi_sub_zones": sum(len(zones) for zones in self.roi_sub_zones.values()),
            "fp_zones": sum(len(zones) for zones in self.fp_zones.values()),
        }
        self.references.clear()
        self._reference_hashes.clear()
        self._ref_orb_cache.clear()
        self.roi_polygons.clear()
        self.roi_sub_zones.clear()
        self.fp_zones.clear()
        self._last_diff_maps.clear()
        self._last_segmentation_masks.clear()
        self._last_aligned.clear()
        self._last_aligned_ref_hash.clear()
        self._save_fp_zones()
        self._save_roi_sub_zones()
        self._clear_fp_crop_files()
        return cleared

    def list_learning_reviews(self, product_type: Optional[str] = None) -> list[dict]:
        return self._learning_reviews.list(product_type=product_type)

    def get_learning_review(self, inspection_id: str) -> Optional[dict]:
        review = self._learning_reviews.get(inspection_id)
        return review.details() if review is not None else None

    def get_learning_review_image(self, inspection_id: str, kind: str) -> Optional[tuple[bytes, str]]:
        review = self._learning_reviews.get(inspection_id)
        if review is None:
            return None
        try:
            return review.image(kind)
        except KeyError:
            return None

    def list_accepted_normal_cases(self, product_type: Optional[str] = None) -> list[dict]:
        return self._accepted_normals.list(product_type=product_type)

    def get_accepted_normal_case_image(self, case_id: str) -> Optional[tuple[bytes, str]]:
        return self._accepted_normals.image(case_id)

    def delete_accepted_normal_case(self, case_id: str) -> bool:
        case = self._accepted_normals.get(case_id)
        deleted = self._accepted_normals.delete(case_id)
        if deleted:
            self._learning_reviews.unmark_case(case_id)
            if case is not None:
                self._delete_fp_zones_for_source(
                    source_defect_id=case.source_defect_id,
                    source_inspection_id=case.source_inspection_id,
                )
        return deleted

    def delete_all_accepted_normal_cases(self) -> int:
        deleted_count = self._accepted_normals.clear()
        self._learning_reviews.unmark_all_cases()
        self._delete_auto_fp_zones()
        return deleted_count

    def accept_review_defect_as_normal(
        self,
        inspection_id: str,
        defect_id: str,
        note: str = "",
    ) -> dict:
        """Запомнить фрагмент; прошлое pipeline-решение остаётся неизменным."""
        review = self._learning_reviews.get(inspection_id)
        if review is None:
            raise KeyError("inspection")
        if defect_id in review.accepted_defect_ids:
            raise ValueError("Defect is already accepted as normal")
        candidate = next((item for item in review.defects if item.id == defect_id), None)
        if candidate is None:
            raise KeyError("defect")
        if candidate.matched_case_id is not None:
            raise ValueError("Defect is already recognized as an accepted normal")

        aligned, _, _ = decode_review_arrays(review)
        accepted_case = self._accepted_normals.add_from_candidate(
            product_type=review.product_type,
            reference_hash=review.reference_hash,
            inspection_id=review.inspection_id,
            candidate=candidate,
            source_frame=aligned,
            note=note,
        )
        candidate.matched_case_id = accepted_case.id
        candidate.similarity = 1.0
        review.accepted_defect_ids.add(defect_id)
        self._learning_reviews.put(review)

        fp_zones = self._add_fp_zones_from_candidates(review, [candidate], note)
        counterfactual_score, counterfactual_status = self._review_counterfactual(review)
        return {
            "saved": True,
            "accepted_case": accepted_case.to_public_dict(),
            "inspection_id": inspection_id,
            "defect_id": defect_id,
            "fp_zone_ids": [zone.id for zone in fp_zones],
            "fp_zones_count": len(fp_zones),
            "original_status": review.original_status,
            "original_score": review.original_score,
            "counterfactual_status": counterfactual_status,
            "counterfactual_score": counterfactual_score,
            "affects_original_pipeline_decision": False,
            "pipeline_decision_sent": True,
        }

    def accept_all_review_defects_as_normal(
        self,
        inspection_id: str,
        note: str = "",
    ) -> dict:
        """Одной операцией запомнить все ещё не принятые дефекты review."""
        review = self._learning_reviews.get(inspection_id)
        if review is None:
            raise KeyError("inspection")

        candidates = [
            candidate
            for candidate in review.defects
            if candidate.id not in review.accepted_defect_ids
            and candidate.matched_case_id is None
        ]
        if not candidates:
            raise ValueError("All review defects are already accepted as normal")

        accepted_cases = []
        aligned, _, _ = decode_review_arrays(review)
        try:
            for candidate in candidates:
                accepted_case = self._accepted_normals.add_from_candidate(
                    product_type=review.product_type,
                    reference_hash=review.reference_hash,
                    inspection_id=review.inspection_id,
                    candidate=candidate,
                    source_frame=aligned,
                    note=note,
                )
                candidate.matched_case_id = accepted_case.id
                candidate.similarity = 1.0
                review.accepted_defect_ids.add(candidate.id)
                accepted_cases.append(accepted_case)
        except Exception:
            for candidate, accepted_case in zip(candidates, accepted_cases):
                self._accepted_normals.delete(accepted_case.id)
                candidate.matched_case_id = None
                candidate.similarity = None
                review.accepted_defect_ids.discard(candidate.id)
            raise

        self._learning_reviews.put(review)
        fp_zones = self._add_fp_zones_from_candidates(review, candidates, note)
        counterfactual_score, counterfactual_status = self._review_counterfactual(review)
        return {
            "saved": True,
            "accepted_count": len(accepted_cases),
            "accepted_cases": [case.to_public_dict() for case in accepted_cases],
            "inspection_id": inspection_id,
            "defect_ids": [candidate.id for candidate in candidates],
            "fp_zone_ids": [zone.id for zone in fp_zones],
            "fp_zones_count": len(fp_zones),
            "original_status": review.original_status,
            "original_score": review.original_score,
            "counterfactual_status": counterfactual_status,
            "counterfactual_score": counterfactual_score,
            "affects_original_pipeline_decision": False,
            "pipeline_decision_sent": True,
        }

    def accept_review_all_as_normal(self, inspection_id: str, note: str = "") -> dict:
        return self.accept_all_review_defects_as_normal(inspection_id, note)

    def _review_counterfactual(self, review) -> Tuple[Optional[float], Optional[str]]:
        """Ознакомительный пересчёт не публикуется в оркестратор/PLC."""
        counterfactual_score: Optional[float] = None
        counterfactual_status: Optional[str] = None
        try:
            aligned, diff_map, raw_mask = decode_review_arrays(review)
            settings = self.get_analysis_settings(review.product_type)
            reference = self.get_reference(review.product_type)
            polygon = self.get_roi_polygon(review.product_type)
            if reference is not None:
                reference = reference.copy()
                if polygon is not None:
                    aligned, reference = mask_to_polygon(aligned, reference, polygon)
            learned_filter = self._accepted_normals.apply(
                product_type=review.product_type,
                reference_hash=review.reference_hash,
                aligned=aligned,
                diff_map=diff_map,
                segmentation_mask=raw_mask,
            )
            learned_score, learned_mask = self._run_anomaly_model(
                learned_filter.filtered_diff_map,
                settings,
            )
            fp_recheck = self._recheck_fp_zones(
                review.product_type,
                aligned,
                reference,
                learned_filter.filtered_diff_map,
                learned_mask,
                learned_score,
                settings,
                review.reference_hash,
                review.threshold,
            )
            _, _, counterfactual_score, counterfactual_status = self._score_inspection_regions(
                filtered_diff_map=fp_recheck["filtered_diff_map"],
                segmentation_mask=fp_recheck["filtered_mask"],
                inspection_threshold=review.threshold,
                settings=settings,
                polygon=polygon,
                sub_zones=self.get_roi_sub_zones(review.product_type),
            )
            review.counterfactual_score = counterfactual_score
            review.counterfactual_status = counterfactual_status
            self._learning_reviews.put(review)
        except Exception:
            logger.exception(
                "accepted-normal counterfactual preview failed inspection_id=%s",
                review.inspection_id,
            )
        return counterfactual_score, counterfactual_status

    def set_roi_polygon(self, product_type: str, points: list[Tuple[float, float]]) -> None:
        self.roi_polygons[product_type] = validate_polygon_points(points, "ROI polygon")

    def get_roi_polygon(self, product_type: str) -> Optional[list[Tuple[float, float]]]:
        return self.roi_polygons.get(product_type)

    def get_roi_sub_zones(self, product_type: str) -> list[RoiSubZone]:
        return list(self.roi_sub_zones.get(product_type, []))

    def add_roi_sub_zone(
        self,
        product_type: str,
        points: list[Tuple[float, float]],
        threshold: Optional[float] = None,
        label: str = "",
    ) -> RoiSubZone:
        parent = self.get_roi_polygon(product_type)
        if parent is None:
            raise ValueError("Parent ROI polygon must be set before adding sub-zones")
        normalized = validate_polygon_inside_parent(points, parent, "Sub-ROI polygon")
        if polygon_area(normalized) < 0.0001:
            raise ValueError("Sub-ROI polygon area is too small")
        if threshold is not None and not (0.0 < threshold <= 1.0):
            raise ValueError("Sub-ROI threshold must be in (0, 1]")
        zone = RoiSubZone(
            id=str(uuid.uuid4()),
            product_type=product_type,
            points=normalized,
            threshold=threshold,
            label=label.strip(),
            created_at=datetime.now(timezone.utc).isoformat(),
        )
        self.roi_sub_zones.setdefault(product_type, []).append(zone)
        self._save_roi_sub_zones()
        return zone

    def update_roi_sub_zone(
        self,
        zone_id: str,
        threshold: Optional[float] = None,
        label: Optional[str] = None,
        points: Optional[list[Tuple[float, float]]] = None,
    ) -> Optional[RoiSubZone]:
        for product_type, zones in self.roi_sub_zones.items():
            for idx, zone in enumerate(zones):
                if zone.id != zone_id:
                    continue
                parent = self.get_roi_polygon(product_type)
                if parent is None:
                    raise ValueError("Parent ROI polygon must be set")
                if points is not None:
                    zone.points = validate_polygon_inside_parent(points, parent, "Sub-ROI polygon")
                if threshold is not None:
                    if not (0.0 < threshold <= 1.0):
                        raise ValueError("Sub-ROI threshold must be in (0, 1]")
                    zone.threshold = threshold
                if label is not None:
                    zone.label = label.strip()
                zones[idx] = zone
                self._save_roi_sub_zones()
                return zone
        return None

    def delete_roi_sub_zone(self, zone_id: str) -> bool:
        for product_type, zones in self.roi_sub_zones.items():
            retained = [zone for zone in zones if zone.id != zone_id]
            if len(retained) != len(zones):
                self.roi_sub_zones[product_type] = retained
                self._save_roi_sub_zones()
                return True
        return False

    def get_analysis_settings(self, analysis_profile: str) -> AnalysisSettings:
        self._reload_analysis_settings_if_stale()
        overrides = self._resolve_analysis_settings_overrides(analysis_profile)
        return AnalysisSettings.from_overrides(overrides)

    def get_analysis_settings_overrides(self, analysis_profile: str) -> dict[str, object]:
        self._reload_analysis_settings_if_stale()
        return dict(self._resolve_analysis_settings_overrides(analysis_profile))

    def _resolve_analysis_settings_overrides(self, analysis_profile: str) -> dict[str, object]:
        """UI пишет overrides в analysis_profile (bench-lan1), inspect — в product#cam=N.

        Читаем точный ключ, иначе fallback на базу без суффикса #cam=.
        """
        key = (analysis_profile or "").strip()
        if not key:
            return {}
        if key in self._analysis_settings_overrides:
            return self._analysis_settings_overrides[key]
        if "#cam=" in key:
            base = key.rsplit("#cam=", 1)[0].strip()
            if base and base in self._analysis_settings_overrides:
                return self._analysis_settings_overrides[base]
        return {}

    def update_analysis_settings(self, analysis_profile: str, partial: dict[str, object]) -> dict[str, object]:
        current = dict(self._analysis_settings_overrides.get(analysis_profile, {}))
        allowed = AnalysisSettings.field_names()
        for key, value in partial.items():
            if key not in allowed:
                raise ValueError(f"Unknown analysis setting: {key}")
            current[key] = value
        AnalysisSettings.from_overrides(current)
        self._analysis_settings_overrides[analysis_profile] = current
        # Полный API сбивает abstract-режим: knobs больше не соответствуют overrides.
        self._analysis_settings_simple_knobs.pop(analysis_profile, None)
        self._analysis_settings_pro_knobs.pop(analysis_profile, None)
        self._save_analysis_settings()
        return dict(current)

    def reset_analysis_settings(self, analysis_profile: str) -> dict[str, object]:
        self._analysis_settings_overrides.pop(analysis_profile, None)
        self._analysis_settings_simple_knobs.pop(analysis_profile, None)
        self._analysis_settings_pro_knobs.pop(analysis_profile, None)
        self._save_analysis_settings()
        return {}

    def get_simple_knobs(self, analysis_profile: str) -> dict[str, object] | None:
        self._reload_analysis_settings_if_stale()
        return self._resolve_analysis_settings_knobs(self._analysis_settings_simple_knobs, analysis_profile)

    def get_pro_knobs(self, analysis_profile: str) -> dict[str, object] | None:
        self._reload_analysis_settings_if_stale()
        return self._resolve_analysis_settings_knobs(self._analysis_settings_pro_knobs, analysis_profile)

    def _resolve_analysis_settings_knobs(
        self,
        knobs_by_profile: Dict[str, dict[str, object]],
        analysis_profile: str,
    ) -> dict[str, object] | None:
        key = (analysis_profile or "").strip()
        if not key:
            return None
        knobs = knobs_by_profile.get(key)
        if knobs is not None:
            return dict(knobs)
        if "#cam=" in key:
            base = key.rsplit("#cam=", 1)[0].strip()
            knobs = knobs_by_profile.get(base) if base else None
            if knobs is not None:
                return dict(knobs)
        return None

    def apply_simple_settings(
        self,
        analysis_profile: str,
        overrides: dict[str, object],
        knobs: dict[str, object],
    ) -> dict[str, object]:
        """Полная замена overrides из simple-пресета + сохранение knobs."""
        AnalysisSettings.from_overrides(overrides)
        self._analysis_settings_overrides[analysis_profile] = dict(overrides)
        self._analysis_settings_simple_knobs[analysis_profile] = dict(knobs)
        self._analysis_settings_pro_knobs.pop(analysis_profile, None)
        self._save_analysis_settings()
        return dict(overrides)

    def apply_pro_settings(
        self,
        analysis_profile: str,
        overrides: dict[str, object],
        knobs: dict[str, object],
    ) -> dict[str, object]:
        """Полная замена overrides из pro-пресета + сохранение knobs."""
        AnalysisSettings.from_overrides(overrides)
        self._analysis_settings_overrides[analysis_profile] = dict(overrides)
        self._analysis_settings_pro_knobs[analysis_profile] = dict(knobs)
        self._analysis_settings_simple_knobs.pop(analysis_profile, None)
        self._save_analysis_settings()
        return dict(overrides)

    def add_fp_zone(
        self,
        product_type: str,
        points_norm_heatmap: list[Tuple[float, float]],
        heatmap_w: int,
        heatmap_h: int,
        note: str = "",
        aligned: Optional[np.ndarray] = None,
        reference_hash: Optional[str] = None,
        source_inspection_id: str = "",
        source_defect_id: str = "",
    ) -> FPZone:
        normalized = validate_polygon_points(points_norm_heatmap, "FP polygon")
        if polygon_area(normalized) < 0.0001:
            raise ValueError("FP polygon area is too small")
        if heatmap_w <= 0 or heatmap_h <= 0:
            raise ValueError("heatmap size must be positive")
        frame = aligned if aligned is not None else self._last_aligned.get(product_type)
        if frame is None:
            raise ValueError("FP zone requires a previous inspection to capture the mini-etalon crop")
        current_hash = self._reference_hashes.get(product_type, "")
        last_hash = self._last_aligned_ref_hash.get(product_type, "")
        if aligned is None and current_hash and last_hash and current_hash != last_hash:
            raise ValueError("Reference changed after the last inspection; inspect again before saving the FP zone")
        height, width = frame.shape[:2]
        x0, y0, crop_w, crop_h = polygon_bbox_from_norm_points(width, height, normalized, padding=2)
        if crop_w < 2 or crop_h < 2:
            raise ValueError("FP polygon crop is too small")
        fp_crop = frame[y0 : y0 + crop_h, x0 : x0 + crop_w].copy()
        zone = FPZone(
            id=str(uuid.uuid4()),
            product_type=product_type,
            points_norm_heatmap=normalized,
            points_norm_ref=list(normalized),
            heatmap_w=int(heatmap_w),
            heatmap_h=int(heatmap_h),
            created_at=datetime.now(timezone.utc).isoformat(),
            note=note.strip(),
            reference_hash=reference_hash or current_hash or last_hash,
            crop_bbox=(x0, y0, crop_w, crop_h),
            fp_crop=fp_crop,
            source_inspection_id=source_inspection_id,
            source_defect_id=source_defect_id,
        )
        self.fp_zones.setdefault(product_type, []).append(zone)
        self._save_fp_zones()
        self._save_fp_crop(zone)
        return zone

    def _add_fp_zones_from_candidates(self, review, candidates, note: str = "") -> list[FPZone]:
        """По контурам review построить FP-зоны с отступом и мини-эталоном."""
        aligned = self._last_aligned.get(review.product_type)
        if aligned is None:
            try:
                aligned, _, _ = decode_review_arrays(review)
            except Exception:
                logger.exception(
                    "cannot decode review aligned frame for FP zones inspection_id=%s",
                    review.inspection_id,
                )
                return []
        height, width = aligned.shape[:2]
        zones: list[FPZone] = []
        for candidate in candidates:
            try:
                points = padded_bbox_polygon(candidate.bbox_norm, width, height)
                zone = self.add_fp_zone(
                    product_type=review.product_type,
                    points_norm_heatmap=points,
                    heatmap_w=width,
                    heatmap_h=height,
                    note=note or f"auto from {candidate.id}",
                    aligned=aligned,
                    reference_hash=review.reference_hash,
                    source_inspection_id=review.inspection_id,
                    source_defect_id=candidate.id,
                )
                zones.append(zone)
            except ValueError:
                logger.info("skip FP zone for %s: invalid auto polygon", candidate.id)
        return zones

    def _delete_fp_zones_for_source(self, source_defect_id: str, source_inspection_id: str) -> None:
        if not source_defect_id and not source_inspection_id:
            return
        changed = False
        for product_type, zones in list(self.fp_zones.items()):
            retained = []
            for zone in zones:
                linked = (
                    source_defect_id
                    and zone.source_defect_id == source_defect_id
                    and zone.source_inspection_id == source_inspection_id
                )
                if linked:
                    self._delete_fp_crop_file(zone.id)
                    changed = True
                    continue
                retained.append(zone)
            self.fp_zones[product_type] = retained
        if changed:
            self._save_fp_zones()

    def _delete_auto_fp_zones(self) -> None:
        changed = False
        for product_type, zones in list(self.fp_zones.items()):
            retained = []
            for zone in zones:
                if zone.source_defect_id:
                    self._delete_fp_crop_file(zone.id)
                    changed = True
                    continue
                retained.append(zone)
            self.fp_zones[product_type] = retained
        if changed:
            self._save_fp_zones()

    def get_fp_zones(self, product_type: str) -> list[FPZone]:
        return list(self.fp_zones.get(product_type, []))

    def get_fp_zone(self, zone_id: str) -> Optional[FPZone]:
        for zones in self.fp_zones.values():
            for zone in zones:
                if zone.id == zone_id:
                    return zone
        return None

    def get_fp_zone_crop_png(self, zone_id: str) -> Optional[bytes]:
        zone = self.get_fp_zone(zone_id)
        if zone is None or zone.fp_crop is None or zone.fp_crop.size == 0:
            return None
        ok, buffer = cv2.imencode(".png", zone.fp_crop)
        if not ok:
            return None
        return buffer.tobytes()

    def delete_fp_zone(self, zone_id: str) -> bool:
        for product_type, zones in self.fp_zones.items():
            retained = [zone for zone in zones if zone.id != zone_id]
            if len(retained) != len(zones):
                self.fp_zones[product_type] = retained
                self._delete_fp_crop_file(zone_id)
                self._save_fp_zones()
                return True
        return False

    def delete_all_fp_zones(self) -> int:
        deleted_count = sum(len(zones) for zones in self.fp_zones.values())
        self.fp_zones = {}
        self._save_fp_zones()
        self._clear_fp_crop_files()
        return deleted_count

    def inspect(
        self,
        product_type: str,
        image_bytes: bytes,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
        include_heatmap_u8: bool = False,
        detector_id: Optional[str] = None,
        alignment_h_ref_to_cur: Optional[list[float] | list[list[float]]] = None,
    ) -> InspectionResult:
        current = self._decode_image(image_bytes)
        return self.inspect_frame(
            product_type=product_type,
            frame=current,
            threshold=threshold,
            include_visuals=include_visuals,
            include_heatmap_u8=include_heatmap_u8,
            detector_id=detector_id,
            alignment_h_ref_to_cur=alignment_h_ref_to_cur,
        )

    def inspect_frame(
        self,
        product_type: str,
        frame: np.ndarray,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
        include_heatmap_u8: bool = False,
        detector_id: Optional[str] = None,
        alignment_h_ref_to_cur: Optional[list[float] | list[list[float]]] = None,
        analysis_profile: Optional[str] = None,
    ) -> InspectionResult:
        # --- Пайплайн инспекции (см. docs/GUIDE.md) ---
        settings_key = (analysis_profile or "").strip() or product_type
        settings = self.get_analysis_settings(settings_key)
        reference = self.get_reference(product_type)
        if reference is None:
            raise ValueError(f"Reference for product_type '{product_type}' is not set")
        reference = reference.copy()

        # 1. Совместить текущий кадр с эталоном (geometry H или ORB+homography, затем ECC).
        aligned = self._align_to_reference(
            frame,
            reference,
            product_type,
            alignment_h_ref_to_cur=alignment_h_ref_to_cur,
        )

        # 2. Ограничить анализ ROI-полигоном (вне полигона — нули).
        polygon = self.get_roi_polygon(product_type)
        if polygon is not None:
            aligned, reference = mask_to_polygon(aligned, reference, polygon)

        self._last_aligned[product_type] = aligned.copy()
        inspection_threshold = (
            threshold if threshold is not None else settings.default_threshold
        )

        # 3. Карта отличий эталон vs выровненный кадр.
        diff_map = self._compute_advanced_difference(aligned, reference, settings)

        # 4. Бинарная маска дефектов + глобальный score по diff.
        anomaly_score, segmentation_mask = self._run_anomaly_model(diff_map, settings)
        self._last_diff_maps[product_type] = diff_map.copy()
        self._last_segmentation_masks[product_type] = segmentation_mask.copy()
        raw_score = anomaly_score

        # 5. Обучаемая память нормы: совпавшие фрагменты удаляются до зонального score.
        ref_hash = self._reference_hashes.get(product_type) or reference_fingerprint(reference)
        self._last_aligned_ref_hash[product_type] = ref_hash
        learned_filter = self._accepted_normals.apply(
            product_type=product_type,
            reference_hash=ref_hash,
            aligned=aligned,
            diff_map=diff_map,
            segmentation_mask=segmentation_mask,
        )
        learned_score = raw_score
        learned_diff_map = learned_filter.filtered_diff_map
        segmentation_mask = learned_filter.filtered_mask
        if learned_filter.matched_case_ids:
            learned_score, segmentation_mask = self._run_anomaly_model(learned_diff_map, settings)
            if learned_filter.all_important_candidates_matched:
                residual_candidates = extract_defect_candidates(
                    aligned,
                    learned_diff_map,
                    segmentation_mask,
                )
                significant_residuals = filter_review_candidates(
                    residual_candidates,
                    baseline_maximum_impact=learned_filter.original_max_candidate_impact,
                )
                if not significant_residuals:
                    learned_diff_map = np.zeros_like(learned_diff_map)
                    segmentation_mask = np.zeros_like(segmentation_mask)
                    learned_score = 0.0

        # 6. FP-зоны: дырка в основном score + отдельная проверка vs мини-эталон.
        fp_recheck = self._recheck_fp_zones(
            product_type,
            aligned,
            reference,
            learned_diff_map,
            segmentation_mask,
            learned_score,
            settings,
            ref_hash,
            inspection_threshold,
        )
        filtered_diff_map = fp_recheck["filtered_diff_map"]
        segmentation_mask = fp_recheck["filtered_mask"]

        # 8. Score по main ROI (с «дырами» sub-zones) и по каждой подзоне отдельно.
        sub_zones = self.get_roi_sub_zones(product_type)
        main_roi_score, sub_zone_scores, anomaly_score, status = self._score_inspection_regions(
            filtered_diff_map=filtered_diff_map,
            segmentation_mask=segmentation_mask,
            inspection_threshold=inspection_threshold,
            settings=settings,
            polygon=polygon,
            sub_zones=sub_zones,
        )

        candidate_source = extract_defect_candidates(
            aligned,
            filtered_diff_map,
            segmentation_mask,
        )
        # Сохраняем в review только значимые области: они и отображаются, и
        # принимаются групповой кнопкой как допустимая норма.
        review_candidates = filter_review_candidates(candidate_source)
        display_mask = np.zeros_like(segmentation_mask)
        for candidate in review_candidates:
            x, y, box_width, box_height = candidate.bbox
            local_mask = candidate.mask.astype(bool)
            display_region = display_mask[y : y + box_height, x : x + box_width]
            display_region[local_mask] = 255

        # 8. Визуализации (heatmap_u8 — gray для SHM/UI, heatmap — цветной JET для base64).
        # Только энергия дефекта: сырой min-max по всему ROI заливает полигон зелёным.
        heatmap_mask = display_mask if int(np.count_nonzero(display_mask)) > 0 else segmentation_mask
        heatmap_u8 = None
        if include_visuals:
            heatmap_u8 = self._build_heatmap_gray(heatmap_mask, filtered_diff_map)
        elif include_heatmap_u8:
            try:
                heatmap_u8 = self._build_heatmap_gray(heatmap_mask, filtered_diff_map)
            except Exception:
                logger.exception("UI heatmap generation failed after inspection completed")
        heatmap = self._colorize_heatmap(heatmap_u8, segmentation_mask) if include_visuals else None
        if include_visuals and heatmap is not None:
            heatmap = self._draw_fp_zone_overlay(heatmap, self.get_fp_zones(product_type), fp_recheck["fp_zone_scores"])
            heatmap = self._draw_roi_sub_zone_overlay(heatmap, sub_zones, sub_zone_scores)

        # История кадров: и ГОДЕН, и БРАК. Обучение меняет только будущие инспекции.
        inspection_id = str(uuid.uuid4())
        try:
            self._learning_reviews.add(
                inspection_id=inspection_id,
                product_type=product_type,
                reference_hash=ref_hash,
                status=status,
                score=anomaly_score,
                threshold=inspection_threshold,
                aligned=aligned,
                diff_map=filtered_diff_map,
                raw_mask=display_mask,
                candidates=review_candidates,
            )
        except Exception:
            inspection_id = None
            logger.exception("failed to save inspection history product_type=%s", product_type)

        return InspectionResult(
            product_type=product_type,
            status=status,
            anomaly_score=anomaly_score,
            threshold=inspection_threshold,
            detector_id=get_application_id(),
            raw_anomaly_score=raw_score,
            rechecked_zones_count=len(fp_recheck["rechecked_zone_ids"]),
            recheck_adjustment=learned_score - fp_recheck["final_score"],
            rechecked_zone_ids=fp_recheck["rechecked_zone_ids"],
            main_roi_score=main_roi_score,
            sub_zone_scores=sub_zone_scores,
            inspection_id=inspection_id,
            learned_normal_matches_count=learned_filter.matched_candidates_count,
            learned_normal_adjustment=max(0.0, raw_score - learned_score),
            matched_accepted_case_ids=learned_filter.matched_case_ids,
            fp_zone_scores=fp_recheck["fp_zone_scores"],
            aligned_image=aligned if include_visuals else None,
            diff_map=diff_map if include_visuals else None,
            heatmap=heatmap if include_visuals else None,
            heatmap_u8=heatmap_u8,
            segmentation_mask=segmentation_mask if include_visuals else None,
        )

    def _score_inspection_regions(
        self,
        *,
        filtered_diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        inspection_threshold: float,
        settings: AnalysisSettings,
        polygon: Optional[list[Tuple[float, float]]],
        sub_zones: list[RoiSubZone],
    ) -> tuple[float, list[RoiSubZoneScore], float, str]:
        """Единый расчёт вердикта для live-inspect и ознакомительного review."""
        h, w = filtered_diff_map.shape[:2]
        hole_polygons = [zone.points for zone in sub_zones]
        main_region_mask = combine_region_masks(w, h, polygon, hole_polygons)
        main_roi_score = self._score_region(
            filtered_diff_map,
            segmentation_mask,
            main_region_mask,
            settings,
        )
        sub_zone_scores: list[RoiSubZoneScore] = []
        for zone in sub_zones:
            zone_mask = polygon_mask_from_norm_points(w, h, zone.points) > 0
            zone_score = self._score_region(filtered_diff_map, segmentation_mask, zone_mask, settings)
            zone_threshold = zone.threshold if zone.threshold is not None else inspection_threshold
            sub_zone_scores.append(
                RoiSubZoneScore(
                    zone_id=zone.id,
                    label=zone.label,
                    anomaly_score=zone_score,
                    threshold=zone_threshold,
                    status="БРАК" if zone_score >= zone_threshold else "ГОДЕН",
                )
            )

        zone_scores = [main_roi_score, *(entry.anomaly_score for entry in sub_zone_scores)]
        anomaly_score = float(max(zone_scores)) if zone_scores else 0.0
        main_failed = main_roi_score >= inspection_threshold
        sub_failed = any(entry.status == "БРАК" for entry in sub_zone_scores)
        status = "БРАК" if main_failed or sub_failed else "ГОДЕН"
        return main_roi_score, sub_zone_scores, anomaly_score, status

    def _load_analysis_settings(self) -> None:
        self._analysis_settings_overrides = {}
        self._analysis_settings_simple_knobs = {}
        self._analysis_settings_pro_knobs = {}
        if not self._analysis_settings_file.exists():
            return
        try:
            raw_payload = json.loads(self._analysis_settings_file.read_text(encoding="utf-8"))
            entries = raw_payload if isinstance(raw_payload, list) else []
            for entry in entries:
                analysis_profile = str(
                    entry.get("analysis_profile", entry.get("product_type", ""))
                ).strip()
                if not analysis_profile:
                    continue
                overrides = entry.get("overrides", {})
                if not isinstance(overrides, dict):
                    continue
                filtered = {
                    key: value
                    for key, value in overrides.items()
                    if key in AnalysisSettings.field_names()
                }
                if filtered:
                    self._analysis_settings_overrides[analysis_profile] = filtered
                simple_knobs = entry.get("simple_knobs")
                if isinstance(simple_knobs, dict) and simple_knobs:
                    self._analysis_settings_simple_knobs[analysis_profile] = dict(simple_knobs)
                pro_knobs = entry.get("pro_knobs")
                if isinstance(pro_knobs, dict) and pro_knobs:
                    self._analysis_settings_pro_knobs[analysis_profile] = dict(pro_knobs)
        except Exception:
            self._analysis_settings_overrides = {}
            self._analysis_settings_simple_knobs = {}
            self._analysis_settings_pro_knobs = {}

    def _analysis_settings_file_mtime_ns(self) -> int:
        try:
            if not self._analysis_settings_file.exists():
                return 0
            return int(self._analysis_settings_file.stat().st_mtime_ns)
        except OSError:
            return 0

    def _stamp_analysis_settings_mtime(self) -> None:
        self._analysis_settings_mtime_ns = self._analysis_settings_file_mtime_ns()

    def _reload_analysis_settings_if_stale(self) -> None:
        mtime_ns = self._analysis_settings_file_mtime_ns()
        if mtime_ns == self._analysis_settings_mtime_ns:
            return
        with self._analysis_settings_lock:
            if mtime_ns == self._analysis_settings_mtime_ns:
                return
            self._load_analysis_settings()
            self._analysis_settings_mtime_ns = mtime_ns

    def _save_analysis_settings(self) -> None:
        self._analysis_settings_file.parent.mkdir(parents=True, exist_ok=True)
        profiles = set(self._analysis_settings_overrides) | set(self._analysis_settings_simple_knobs) | set(
            self._analysis_settings_pro_knobs
        )
        entries = []
        for analysis_profile in sorted(profiles):
            entry: dict[str, object] = {
                "analysis_profile": analysis_profile,
                "overrides": self._analysis_settings_overrides.get(analysis_profile, {}),
            }
            simple_knobs = self._analysis_settings_simple_knobs.get(analysis_profile)
            if simple_knobs is not None:
                entry["simple_knobs"] = simple_knobs
            pro_knobs = self._analysis_settings_pro_knobs.get(analysis_profile)
            if pro_knobs is not None:
                entry["pro_knobs"] = pro_knobs
            entries.append(entry)
        self._analysis_settings_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")
        self._stamp_analysis_settings_mtime()

    def _load_roi_sub_zones(self) -> None:
        self.roi_sub_zones = {}
        if not self._roi_sub_zones_file.exists():
            return
        try:
            raw_payload = json.loads(self._roi_sub_zones_file.read_text(encoding="utf-8"))
            entries = raw_payload if isinstance(raw_payload, list) else []
            for entry in entries:
                product_type = str(entry.get("product_type", "")).strip()
                if not product_type:
                    continue
                points = [
                    (float(p[0]), float(p[1]))
                    for p in entry.get("points", [])
                    if isinstance(p, (list, tuple)) and len(p) >= 2
                ]
                if len(points) < 3:
                    continue
                threshold_raw = entry.get("threshold")
                threshold = float(threshold_raw) if threshold_raw is not None else None
                zone = RoiSubZone(
                    id=str(entry.get("id", str(uuid.uuid4()))),
                    product_type=product_type,
                    points=points,
                    threshold=threshold,
                    label=str(entry.get("label", "")),
                    created_at=str(entry.get("created_at", datetime.now(timezone.utc).isoformat())),
                )
                self.roi_sub_zones.setdefault(product_type, []).append(zone)
        except Exception:
            self.roi_sub_zones = {}

    def _save_roi_sub_zones(self) -> None:
        self._roi_sub_zones_file.parent.mkdir(parents=True, exist_ok=True)
        entries = []
        for zones in self.roi_sub_zones.values():
            for zone in zones:
                entries.append(
                    {
                        "id": zone.id,
                        "product_type": zone.product_type,
                        "points": zone.points,
                        "threshold": zone.threshold,
                        "label": zone.label,
                        "created_at": zone.created_at,
                    }
                )
        self._roi_sub_zones_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")

    def _load_fp_zones(self) -> None:
        self.fp_zones = {}
        if not self._fp_zones_file.exists():
            return
        try:
            raw_payload = json.loads(self._fp_zones_file.read_text(encoding="utf-8"))
            entries = raw_payload if isinstance(raw_payload, list) else []
            for entry in entries:
                product_type = str(entry.get("product_type", "")).strip()
                if not product_type:
                    continue
                bbox_raw = entry.get("crop_bbox") or [0, 0, 0, 0]
                zone = FPZone(
                    id=str(entry.get("id", str(uuid.uuid4()))),
                    product_type=product_type,
                    points_norm_heatmap=[(float(p[0]), float(p[1])) for p in entry.get("points_norm_heatmap", [])],
                    points_norm_ref=[(float(p[0]), float(p[1])) for p in entry.get("points_norm_ref", entry.get("points_norm_heatmap", []))],
                    heatmap_w=int(entry.get("heatmap_w", 1)),
                    heatmap_h=int(entry.get("heatmap_h", 1)),
                    created_at=str(entry.get("created_at", datetime.now(timezone.utc).isoformat())),
                    note=str(entry.get("note", "")),
                    reference_hash=str(entry.get("reference_hash", "")),
                    crop_bbox=(int(bbox_raw[0]), int(bbox_raw[1]), int(bbox_raw[2]), int(bbox_raw[3])),
                    fp_crop=self._load_fp_crop(str(entry.get("id", ""))),
                    source_inspection_id=str(entry.get("source_inspection_id", "")),
                    source_defect_id=str(entry.get("source_defect_id", "")),
                )
                if len(zone.points_norm_ref) >= 3:
                    self.fp_zones.setdefault(product_type, []).append(zone)
        except Exception:
            self.fp_zones = {}

    def _save_fp_zones(self) -> None:
        self._fp_zones_file.parent.mkdir(parents=True, exist_ok=True)
        entries = []
        for zones in self.fp_zones.values():
            for zone in zones:
                entries.append(
                    {
                        "id": zone.id,
                        "product_type": zone.product_type,
                        "points_norm_heatmap": zone.points_norm_heatmap,
                        "points_norm_ref": zone.points_norm_ref,
                        "heatmap_w": zone.heatmap_w,
                        "heatmap_h": zone.heatmap_h,
                        "created_at": zone.created_at,
                        "note": zone.note,
                        "reference_hash": zone.reference_hash,
                        "crop_bbox": list(zone.crop_bbox),
                        "source_inspection_id": zone.source_inspection_id,
                        "source_defect_id": zone.source_defect_id,
                    }
                )
        self._fp_zones_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")

    def _fp_crop_path(self, zone_id: str) -> Path:
        return self._fp_crops_dir / f"{zone_id}.png"

    def _load_fp_crop(self, zone_id: str) -> Optional[np.ndarray]:
        if not zone_id:
            return None
        path = self._fp_crop_path(zone_id)
        if not path.exists():
            return None
        data = np.fromfile(path, dtype=np.uint8)
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        return image

    def _save_fp_crop(self, zone: FPZone) -> None:
        if zone.fp_crop is None or zone.fp_crop.size == 0:
            return
        self._fp_crops_dir.mkdir(parents=True, exist_ok=True)
        ok, buffer = cv2.imencode(".png", zone.fp_crop)
        if ok:
            self._fp_crop_path(zone.id).write_bytes(buffer.tobytes())

    def _delete_fp_crop_file(self, zone_id: str) -> None:
        path = self._fp_crop_path(zone_id)
        if path.exists():
            path.unlink()

    def _clear_fp_crop_files(self) -> None:
        if not self._fp_crops_dir.exists():
            return
        for path in self._fp_crops_dir.glob("*.png"):
            path.unlink(missing_ok=True)

    def _measure_zone_activity(
        self,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        points: list[Tuple[float, float]],
    ) -> dict[str, float]:
        """Сводные метрики активности внутри полигона — для FP-recheck и score по ROI."""
        h, w = diff_map.shape[:2]
        zone_mask = polygon_mask_from_norm_points(w, h, points) > 0
        if not np.any(zone_mask):
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}

        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        zone_diff = diff_gray[zone_mask]
        zone_active = seg_gray[zone_mask] > 0
        # q90 diff — типичная «энергия» отличия; active_ratio — доля пикселей в маске дефекта.
        diff_q90 = float(np.percentile(zone_diff, 90))
        diff_max = float(np.max(zone_diff))
        active_ratio = float(np.mean(zone_active))
        score = self._activity_score(diff_q90, diff_max, active_ratio)
        return {"diff_q90": diff_q90, "diff_max": diff_max, "active_ratio": active_ratio, "score": score}

    @staticmethod
    def _activity_score(diff_q90: float, diff_max: float, active_ratio: float) -> float:
        """Свести метрики активности в score 0..1 без мгновенного насыщения до 1.0.

        Раньше active_ratio*1.2 зажимал score в 1.0 уже при ~80% маски — любой
        умеренный шум/свет давал вечный БРАК независимо от силы diff.
        """
        return float(
            np.clip(
                (diff_q90 / 255.0) * 0.55
                + (diff_max / 255.0) * 0.20
                + float(active_ratio) * 0.35,
                0.0,
                1.0,
            )
        )

    def _measure_zone_activity_mask(
        self,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        region_mask: np.ndarray,
    ) -> dict[str, float]:
        """Метрики активности строго по region_mask (без bbox-аппроксимации)."""
        if region_mask is None or not np.any(region_mask):
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}

        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        zone_diff = diff_gray[region_mask]
        zone_active = seg_gray[region_mask] > 0
        if zone_diff.size == 0:
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}

        diff_q90 = float(np.percentile(zone_diff, 90))
        diff_max = float(np.max(zone_diff))
        active_ratio = float(np.mean(zone_active))
        score = self._activity_score(diff_q90, diff_max, active_ratio)
        return {"diff_q90": diff_q90, "diff_max": diff_max, "active_ratio": active_ratio, "score": score}

    def _score_region(
        self,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        region_mask: np.ndarray,
        settings: AnalysisSettings,
    ) -> float:
        """Score одной области: повторный прогон детектора на маске + метрики активности."""
        if not np.any(region_mask):
            return 0.0
        masked_diff = diff_map.copy()
        masked_diff[~region_mask] = 0
        score, _ = self._run_anomaly_model(masked_diff, settings)
        # Важно: считать activity по реальной маске ROI, а не по bbox-полигону —
        # bbox раздувает зону и завышает active_ratio вне ROI.
        activity = self._measure_zone_activity_mask(diff_map, segmentation_mask, region_mask)
        blended = float(max(score, activity["score"]))
        return blended

    def _mask_to_norm_points(self, region_mask: np.ndarray) -> list[Tuple[float, float]]:
        h, w = region_mask.shape[:2]
        if w <= 1 or h <= 1:
            return [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)]
        ys, xs = np.where(region_mask)
        if len(xs) == 0:
            return [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)]
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()), int(ys.max())
        return [
            (x0 / (w - 1), y0 / (h - 1)),
            (x1 / (w - 1), y0 / (h - 1)),
            (x1 / (w - 1), y1 / (h - 1)),
            (x0 / (w - 1), y1 / (h - 1)),
        ]

    def _fp_zone_hole_mask(self, width: int, height: int, zones: list[FPZone]) -> np.ndarray:
        hole = np.zeros((height, width), dtype=bool)
        for zone in zones:
            if zone.fp_crop is None or zone.fp_crop.size == 0:
                continue
            zone_mask = polygon_mask_from_norm_points(width, height, zone.points_norm_ref)
            if not np.any(zone_mask):
                continue
            suppress = cv2.dilate(zone_mask, np.ones((5, 5), dtype=np.uint8), iterations=1) > 0
            hole |= suppress
        return hole

    @staticmethod
    def _pad_fp_crops(
        current: np.ndarray,
        expected: np.ndarray,
        mask: np.ndarray,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, tuple[int, int, int, int]]:
        height, width = current.shape[:2]
        pad_y = max(0, _FP_CROP_MIN - height)
        pad_x = max(0, _FP_CROP_MIN - width)
        top = pad_y // 2
        bottom = pad_y - top
        left = pad_x // 2
        right = pad_x - left
        if pad_y == 0 and pad_x == 0:
            return current, expected, mask, (0, 0, width, height)
        current_p = cv2.copyMakeBorder(current, top, bottom, left, right, cv2.BORDER_REPLICATE)
        expected_p = cv2.copyMakeBorder(expected, top, bottom, left, right, cv2.BORDER_REPLICATE)
        mask_p = cv2.copyMakeBorder(mask, top, bottom, left, right, cv2.BORDER_CONSTANT, value=0)
        return current_p, expected_p, mask_p, (left, top, width, height)

    def _polygon_region_difference(
        self,
        current_full: np.ndarray,
        expected,
        points: list[Tuple[float, float]],
        settings: AnalysisSettings,
        *,
        expected_is_full_frame: bool,
    ) -> Optional[dict]:
        height, width = current_full.shape[:2]
        x0, y0, crop_w, crop_h = polygon_bbox_from_norm_points(width, height, points, padding=2)
        if crop_w < 2 or crop_h < 2:
            return None
        current_crop = current_full[y0 : y0 + crop_h, x0 : x0 + crop_w]
        if expected_is_full_frame:
            if expected is None or expected.shape[0] < y0 + crop_h or expected.shape[1] < x0 + crop_w:
                return None
            expected_crop = expected[y0 : y0 + crop_h, x0 : x0 + crop_w]
        else:
            if expected is None or expected.size == 0:
                return None
            expected_crop = expected
            if expected_crop.shape[:2] != current_crop.shape[:2]:
                expected_crop = cv2.resize(expected_crop, (crop_w, crop_h), interpolation=cv2.INTER_LINEAR)
        local_mask = polygon_mask_from_norm_points(width, height, points)[y0 : y0 + crop_h, x0 : x0 + crop_w]
        current_p, expected_p, _, box = self._pad_fp_crops(current_crop, expected_crop, local_mask)
        diff_p = self._compute_advanced_difference(current_p, expected_p, settings)
        left, top, orig_w, orig_h = box
        diff = diff_p[top : top + orig_h, left : left + orig_w]
        diff[local_mask == 0] = 0
        score, seg = self._run_anomaly_model(diff, settings)
        activity = self._measure_zone_activity_mask(diff, seg, local_mask > 0)
        return {
            "bbox": (x0, y0, crop_w, crop_h),
            "diff": diff,
            "local_mask": local_mask,
            "score": float(max(score, activity["score"])),
            "activity": activity,
        }

    def _fp_zone_triggered(self, region: dict, settings: AnalysisSettings) -> bool:
        activity = region["activity"]
        return (
            activity["diff_q90"] >= settings.fp_trigger_diff_q90
            or activity["active_ratio"] > 0.01
            or region["score"] >= 0.08
        )

    def _recheck_fp_zones(
        self,
        product_type: str,
        aligned: np.ndarray,
        reference: Optional[np.ndarray],
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        raw_score: float,
        settings: AnalysisSettings,
        reference_hash: str = "",
        inspection_threshold: float = 0.25,
    ) -> dict:
        """Сегментация FP-зон: дырка в score + мини-эталон, если зона сработала vs основной эталон."""
        empty = {
            "final_score": raw_score,
            "rechecked_zone_ids": [],
            "filtered_mask": segmentation_mask,
            "filtered_diff_map": diff_map,
            "fp_zone_scores": [],
        }
        zones = self.get_fp_zones(product_type)
        if not zones or not settings.fp_recheck_enabled:
            return empty

        filtered_diff_map = diff_map.copy()
        rechecked_zone_ids: list[str] = []
        fp_zone_scores: list[FPZoneScore] = []
        pending_residuals: list[tuple[FPZone, list]] = []

        for zone in zones:
            points = zone.points_norm_ref
            if len(points) < 3:
                continue
            if zone.fp_crop is None or zone.fp_crop.size == 0:
                fp_zone_scores.append(
                    FPZoneScore(
                        zone_id=zone.id,
                        triggered_vs_reference=False,
                        applied_fp_etalon=False,
                        residual_score=0.0,
                        status="SKIPPED",
                        note="no mini-etalon crop",
                    )
                )
                continue
            if zone.reference_hash and reference_hash and zone.reference_hash != reference_hash:
                fp_zone_scores.append(
                    FPZoneScore(
                        zone_id=zone.id,
                        triggered_vs_reference=False,
                        applied_fp_etalon=False,
                        residual_score=0.0,
                        status="SKIPPED",
                        note="reference changed",
                    )
                )
                continue

            activity = self._measure_zone_activity(diff_map, segmentation_mask, points)
            triggered = (
                activity["diff_q90"] >= settings.fp_trigger_diff_q90
                or activity["active_ratio"] > 0.01
                or activity["score"] >= 0.08
            )
            if not triggered:
                fp_zone_scores.append(
                    FPZoneScore(
                        zone_id=zone.id,
                        triggered_vs_reference=False,
                        applied_fp_etalon=False,
                        residual_score=0.0,
                        status="ГОДЕН",
                        note="zone quiet after main analysis",
                    )
                )
                continue
            pending_residuals.append((zone, points))

        hole_mask = self._fp_zone_hole_mask(diff_map.shape[1], diff_map.shape[0], zones)
        if np.any(hole_mask):
            filtered_diff_map[hole_mask] = 0

        for zone, points in pending_residuals:
            vs_fp = self._polygon_region_difference(
                aligned,
                zone.fp_crop,
                points,
                settings,
                expected_is_full_frame=False,
            )
            residual_score = 0.0 if vs_fp is None else float(vs_fp["score"])
            keep_residual = vs_fp is not None and self._fp_zone_triggered(vs_fp, settings)
            if keep_residual and vs_fp is not None:
                x0, y0, crop_w, crop_h = vs_fp["bbox"]
                local = vs_fp["local_mask"] > 0
                region = filtered_diff_map[y0 : y0 + crop_h, x0 : x0 + crop_w]
                region[local] = vs_fp["diff"][local]
                status = "БРАК" if residual_score >= inspection_threshold else "ГОДЕН"
                note = "residual defect over FP etalon"
            else:
                status = "ГОДЕН"
                note = "matched FP mini-etalon"
            rechecked_zone_ids.append(zone.id)
            fp_zone_scores.append(
                FPZoneScore(
                    zone_id=zone.id,
                    triggered_vs_reference=True,
                    applied_fp_etalon=True,
                    residual_score=residual_score,
                    status=status,
                    note=note,
                )
            )

        remaining_score, filtered_mask = self._run_anomaly_model(filtered_diff_map, settings)
        return {
            "final_score": float(remaining_score),
            "rechecked_zone_ids": rechecked_zone_ids,
            "filtered_mask": filtered_mask,
            "filtered_diff_map": filtered_diff_map,
            "fp_zone_scores": fp_zone_scores,
        }

    def _draw_roi_sub_zone_overlay(
        self,
        heatmap: np.ndarray,
        zones: list[RoiSubZone],
        scores: list[RoiSubZoneScore],
    ) -> np.ndarray:
        if not zones:
            return heatmap
        overlay = heatmap.copy()
        h, w = heatmap.shape[:2]
        score_by_id = {entry.zone_id: entry for entry in scores}
        for zone in zones:
            pts = np.array(
                [[int(round(x * (w - 1))), int(round(y * (h - 1)))] for x, y in zone.points],
                dtype=np.int32,
            )
            if len(pts) < 3:
                continue
            zone_score = score_by_id.get(zone.id)
            is_fail = zone_score is not None and zone_score.status == "БРАК"
            color = (40, 80, 255) if is_fail else (220, 120, 40)
            cv2.fillPoly(overlay, [pts], color)
            cv2.polylines(overlay, [pts], isClosed=True, color=color, thickness=2)
        return cv2.addWeighted(overlay, 0.22, heatmap, 0.78, 0.0)

    def _draw_fp_zone_overlay(
        self,
        heatmap: np.ndarray,
        zones: list[FPZone],
        scores: list[FPZoneScore],
    ) -> np.ndarray:
        if not zones:
            return heatmap
        overlay = heatmap.copy()
        h, w = heatmap.shape[:2]
        score_by_id = {entry.zone_id: entry for entry in scores}
        for zone in zones:
            pts = np.array(
                [[int(round(x * (w - 1))), int(round(y * (h - 1)))] for x, y in zone.points_norm_ref],
                dtype=np.int32,
            )
            if len(pts) < 3:
                continue
            zone_score = score_by_id.get(zone.id)
            if zone_score is not None and zone_score.status == "БРАК":
                color = (40, 80, 255)
            elif zone_score is not None and zone_score.applied_fp_etalon:
                color = (50, 220, 50)
            else:
                color = (40, 150, 220)
            cv2.fillPoly(overlay, [pts], color)
            cv2.polylines(overlay, [pts], isClosed=True, color=color, thickness=2)
        return cv2.addWeighted(overlay, 0.18, heatmap, 0.82, 0.0)

    def _decode_image(self, image_bytes: bytes) -> np.ndarray:
        data = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image")
        return image

    def encode_image_b64(self, image: np.ndarray) -> str:
        return self._encode_image(image)

    def _encode_image(self, image: np.ndarray) -> str:
        rgb_image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        pil_image = Image.fromarray(rgb_image)
        buffer = BytesIO()
        pil_image.save(buffer, format="PNG")
        return base64.b64encode(buffer.getvalue()).decode("utf-8")

    def _update_ref_orb_cache(self, product_type: str, reference: np.ndarray) -> None:
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        kp_ref, des_ref = self._orb.detectAndCompute(ref_gray, None)
        self._ref_orb_cache[product_type] = (kp_ref, des_ref)

    def _get_ref_orb(self, product_type: str, reference: np.ndarray) -> Tuple[list, Optional[np.ndarray]]:
        cached = self._ref_orb_cache.get(product_type)
        if cached is not None:
            return cached
        self._update_ref_orb_cache(product_type, reference)
        return self._ref_orb_cache[product_type]

    def _align_to_reference(
        self,
        current: np.ndarray,
        reference: np.ndarray,
        product_type: str,
        alignment_h_ref_to_cur: Optional[list[float] | list[list[float]]] = None,
    ) -> np.ndarray:
        """Привести current к системе координат reference.

        Приоритет: гомография от java-geometry/positioning → ORB-матчи + findHomography → resize.
        Если кадр уже выровнен upstream (identity H от positioning) — не трогаем:
        повторный warp+ECC на фоне разницы яркости ломает совмещение и раздувает diff до 1.0.
        Иначе после нетривиального warp — ECC-подстройка микросдвига.
        """
        if self._is_identity_homography(alignment_h_ref_to_cur):
            if current.shape[:2] != reference.shape[:2]:
                return cv2.resize(current, (reference.shape[1], reference.shape[0]))
            return current

        geometry_aligned = self._align_with_geometry_homography(
            current,
            reference,
            alignment_h_ref_to_cur,
        )
        if geometry_aligned is not None:
            return self._refine_alignment_ecc(geometry_aligned, reference)

        cur_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)
        kp_ref, des_ref = self._get_ref_orb(product_type, reference)
        kp_cur, des_cur = self._orb.detectAndCompute(cur_gray, None)
        if des_ref is None or des_cur is None or len(kp_ref) < 8 or len(kp_cur) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        # Lowe ratio test: оставляем только однозначные дескрипторные соответствия.
        matches = self._matcher.knnMatch(des_cur, des_ref, k=2)
        good_matches = []
        for pair in matches:
            if len(pair) < 2:
                continue
            m, n = pair
            if m.distance < 0.75 * n.distance:
                good_matches.append(m)

        if len(good_matches) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        src_pts = np.float32([kp_cur[m.queryIdx].pt for m in good_matches]).reshape(-1, 1, 2)
        dst_pts = np.float32([kp_ref[m.trainIdx].pt for m in good_matches]).reshape(-1, 1, 2)

        homography, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 1.0)
        if homography is None or mask is None:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        height, width = reference.shape[:2]
        aligned = cv2.warpPerspective(current, homography, (width, height))
        return self._refine_alignment_ecc(aligned, reference)

    @staticmethod
    def _use_fixed_frame(current: np.ndarray, reference: np.ndarray) -> np.ndarray:
        """Вернуть кадр в фиксированных координатах без центрирования/warp."""
        if current.shape[:2] == reference.shape[:2]:
            return current
        return cv2.resize(
            current,
            (reference.shape[1], reference.shape[0]),
            interpolation=cv2.INTER_AREA,
        )

    @staticmethod
    def _is_identity_homography(
        alignment_h_ref_to_cur: Optional[list[float] | list[list[float]]],
        atol: float = 1e-6,
    ) -> bool:
        """True если оркестратор передал identity (кадр уже в системе эталона)."""
        if alignment_h_ref_to_cur is None:
            return False
        try:
            homography = np.asarray(alignment_h_ref_to_cur, dtype=np.float64)
            if homography.size == 9:
                homography = homography.reshape(3, 3)
            if homography.shape != (3, 3) or not np.all(np.isfinite(homography)):
                return False
            return bool(np.allclose(homography, np.eye(3), atol=atol, rtol=0.0))
        except (TypeError, ValueError):
            return False

    @staticmethod
    def _align_with_geometry_homography(
        current: np.ndarray,
        reference: np.ndarray,
        alignment_h_ref_to_cur: Optional[list[list[float]]],
    ) -> Optional[np.ndarray]:
        """H из оркестратора: ref→cur; WARP_INVERSE_MAP применяет обратное отображение cur→ref."""
        if alignment_h_ref_to_cur is None:
            return None
        try:
            homography = np.asarray(alignment_h_ref_to_cur, dtype=np.float64)
            if homography.size == 9:
                homography = homography.reshape(3, 3)
            if homography.shape != (3, 3) or not np.all(np.isfinite(homography)):
                return None
            if abs(float(np.linalg.det(homography))) < 1e-12:
                return None
            height, width = reference.shape[:2]
            return cv2.warpPerspective(
                current,
                homography,
                (width, height),
                flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                borderMode=cv2.BORDER_REPLICATE,
            )
        except (TypeError, ValueError, cv2.error, np.linalg.LinAlgError):
            return None

    def _compute_advanced_difference(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
        settings: AnalysisSettings,
    ) -> np.ndarray:
        """Построить карту отличий (BGR), устойчивую к микросдвигу и тексту эталона."""
        if aligned.shape[:2] != reference.shape[:2]:
            aligned = cv2.resize(aligned, (reference.shape[1], reference.shape[0]))

        # Shift-tolerant difference:
        # allow small local displacement by comparing aligned intensity
        # against local min/max envelope of reference.
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        cur_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)

        # CLAHE can over-amplify texture noise on smooth frames, so apply it only
        # when the frame has enough contrast/variance.
        if settings.enable_clahe and float(np.std(cur_gray)) > 5.0:
            clahe = cv2.createCLAHE(clipLimit=settings.clahe_clip_limit, tileGridSize=(8, 8))
            ref_gray = clahe.apply(ref_gray)
            cur_gray = clahe.apply(cur_gray)

        # Light pre-smoothing suppresses matrix/sensor micro-noise so it does not
        # turn into false positives in robust difference map.
        ref_gray = cv2.GaussianBlur(ref_gray, (5, 5), 0)
        cur_gray = cv2.GaussianBlur(cur_gray, (5, 5), 0)

        kernel = np.ones((5, 5), dtype=np.uint8)
        ref_min = cv2.erode(ref_gray, kernel, iterations=1)
        ref_max = cv2.dilate(ref_gray, kernel, iterations=1)

        over = cv2.subtract(cur_gray, ref_max)
        under = cv2.subtract(ref_min, cur_gray)
        robust_gray = cv2.max(over, under)

        # Bilateral filter better preserves thin scratch edges than Gaussian blur.
        robust_gray = cv2.bilateralFilter(robust_gray, d=7, sigmaColor=35, sigmaSpace=35)

        # Light Gaussian denoising before Black Hat suppresses sensor speckles
        # that can otherwise be boosted as false micro-defects.
        robust_gray = cv2.GaussianBlur(robust_gray, (3, 3), 0)

        # Black Hat/Top Hat pair highlights both dark and bright thin defects
        # over text/background structure (scratches, erasures, missing strokes).
        blackhat = cv2.morphologyEx(
            robust_gray,
            cv2.MORPH_BLACKHAT,
            np.ones((15, 15), dtype=np.uint8),
        )
        tophat = cv2.morphologyEx(
            robust_gray,
            cv2.MORPH_TOPHAT,
            np.ones((15, 15), dtype=np.uint8),
        )
        robust_gray = cv2.addWeighted(robust_gray, 0.6, blackhat, 0.2, 0.0)
        robust_gray = cv2.addWeighted(robust_gray, 1.0, tophat, 0.2, 0.0)

        # Edge suppression on strong static reference edges (lid/border/text bounds):
        # reduce anomaly response in a small tolerance band around those edges.
        edges_ref = cv2.Canny(ref_gray, 80, 160)
        edges_zone = cv2.dilate(edges_ref, np.ones((3, 3), dtype=np.uint8), iterations=2)
        edge_mask = edges_zone > 0
        robust_gray = robust_gray.astype(np.float32)
        robust_gray[edge_mask] *= settings.edge_suppress_factor
        robust_gray = np.clip(robust_gray, 0, 255).astype(np.uint8)

        # Structural masking for text-heavy regions:
        # where reference has dense structure, require stronger local contrast
        # to treat response as anomaly.
        structure_mask = cv2.Sobel(ref_gray, cv2.CV_8U, 1, 1, ksize=3)
        text_like_zone = structure_mask > settings.text_structure_threshold
        if np.any(text_like_zone):
            text_vals = robust_gray[text_like_zone]
            robust_gray[text_like_zone] = np.where(
                text_vals >= settings.text_min_contrast,
                text_vals,
                0,
            ).astype(np.uint8)

        # Boost zones where reference has strong text gradients but current frame
        # has low gradients (possible erased/missing text).
        ref_grad_x = cv2.Sobel(ref_gray, cv2.CV_32F, 1, 0, ksize=3)
        ref_grad_y = cv2.Sobel(ref_gray, cv2.CV_32F, 0, 1, ksize=3)
        cur_grad_x = cv2.Sobel(cur_gray, cv2.CV_32F, 1, 0, ksize=3)
        cur_grad_y = cv2.Sobel(cur_gray, cv2.CV_32F, 0, 1, ksize=3)
        ref_grad_mag = cv2.magnitude(ref_grad_x, ref_grad_y)
        cur_grad_mag = cv2.magnitude(cur_grad_x, cur_grad_y)
        contrast_loss_zone = (ref_grad_mag > settings.contrast_loss_ref_grad) & (
            cur_grad_mag < settings.contrast_loss_cur_grad
        )
        if np.any(contrast_loss_zone):
            robust_float = robust_gray.astype(np.float32)
            robust_float[contrast_loss_zone] *= settings.contrast_loss_boost
            robust_gray = np.clip(robust_float, 0, 255).astype(np.uint8)

        # Median blur removes salt-like speckles without erasing thin linear defects.
        robust_gray = cv2.medianBlur(robust_gray, 3)
        return cv2.cvtColor(robust_gray, cv2.COLOR_GRAY2BGR)

    def _refine_alignment_ecc(self, aligned: np.ndarray, reference: np.ndarray) -> np.ndarray:
        """Доточить affine-сдвиг пирамидальным ECC (после грубой гомографии)."""
        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        aligned_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
        # Pyramid ECC (4 levels) with phase-correlation bootstrap.
        # This is more stable for tiny shifts on text-heavy surfaces.
        levels = 4
        ref_pyr = [ref_gray]
        aligned_pyr = [aligned_gray]
        for _ in range(1, levels):
            ref_pyr.append(cv2.pyrDown(ref_pyr[-1]))
            aligned_pyr.append(cv2.pyrDown(aligned_pyr[-1]))

        warp_aff = np.eye(2, 3, dtype=np.float32)
        criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 50, 1e-4)
        try:
            for level in reversed(range(levels)):
                ref_lvl = ref_pyr[level]
                cur_lvl = aligned_pyr[level]

                if level < levels - 1:
                    warp_aff[0, 2] *= 2.0
                    warp_aff[1, 2] *= 2.0

                # Coarse translational bootstrap from phase correlation.
                shift, _ = cv2.phaseCorrelate(
                    np.float32(ref_lvl),
                    np.float32(cur_lvl),
                )
                warp_aff[0, 2] += float(shift[0])
                warp_aff[1, 2] += float(shift[1])

                cv2.findTransformECC(
                    ref_lvl,
                    cur_lvl,
                    warp_aff,
                    cv2.MOTION_AFFINE,
                    criteria,
                    None,
                    5,
                )

            h, w = reference.shape[:2]
            return cv2.warpAffine(
                aligned,
                warp_aff,
                (w, h),
                flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                borderMode=cv2.BORDER_REPLICATE,
            )
        except Exception:
            return aligned

    def _run_anomaly_model(
        self,
        diff_map: np.ndarray,
        settings: AnalysisSettings,
    ) -> Tuple[float, np.ndarray]:
        """Вернуть (score 0..1, маска дефектов BGR).

        Сначала эвристика по connected components на diff_map;
        при use_patchcore — объединение с PatchCore, берётся max(score).
        """
        # Heuristic fallback score with emphasis on strong local differences.
        # This helps thin/high-contrast defects (e.g. scratches) score higher than
        # broad low-contrast texture/background changes.
        gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        gray_blur = cv2.GaussianBlur(gray, (3, 3), 0)
        if float(np.max(gray_blur)) < settings.min_diff_signal:
            zero = np.zeros_like(gray_blur, dtype=np.uint8)
            return 0.0, cv2.cvtColor(zero, cv2.COLOR_GRAY2BGR)
        threshold_value = float(
            max(10.0, min(np.percentile(gray_blur, settings.diff_percentile), 35.0))
        )
        _, binary = cv2.threshold(gray_blur, threshold_value, 255, cv2.THRESH_BINARY)

        # Suppress speckle noise but keep thin structures (scratches).
        cleaned = cv2.medianBlur(binary, 3)
        cleaned = cv2.morphologyEx(
            cleaned,
            cv2.MORPH_CLOSE,
            np.ones((3, 3), dtype=np.uint8),
            iterations=1,
        )
        kernel_long = cv2.getStructuringElement(cv2.MORPH_RECT, (20, 1))
        cleaned = cv2.morphologyEx(
            cleaned,
            cv2.MORPH_CLOSE,
            kernel_long,
            iterations=1,
        )

        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(cleaned, connectivity=8)
        filtered = np.zeros_like(cleaned)
        min_area = settings.min_defect_area
        max_aspect = 0.0
        max_object_score = 0.0
        # Approximate text-like zones on diff map by strong local gradients.
        grad_x = cv2.Sobel(gray_blur, cv2.CV_32F, 1, 0, ksize=3)
        grad_y = cv2.Sobel(gray_blur, cv2.CV_32F, 0, 1, ksize=3)
        grad_mag = cv2.magnitude(grad_x, grad_y)
        text_like_zone = grad_mag > 25.0
        cur_grad_mag = grad_mag
        # Proxy "reference" gradient: local expected structure level in neighborhood.
        ref_grad_mag = cv2.GaussianBlur(cur_grad_mag, (9, 9), 0)
        for label_idx in range(1, num_labels):
            area = int(stats[label_idx, cv2.CC_STAT_AREA])
            x = int(stats[label_idx, cv2.CC_STAT_LEFT])
            y = int(stats[label_idx, cv2.CC_STAT_TOP])
            w = max(1, int(stats[label_idx, cv2.CC_STAT_WIDTH]))
            h = max(1, int(stats[label_idx, cv2.CC_STAT_HEIGHT]))
            aspect = max(w / h, h / w)
            object_min_area = 5 if aspect > 6.0 else min_area

            # Keep tiny but clearly elongated components (scratch-like traces).
            if area >= object_min_area or (area > 3 and aspect > settings.min_scratch_aspect):
                # Ограничиваем операции bbox компонента. Результат идентичен сравнению
                # labels по всему кадру, но не сканирует 5-Мп изображение сотни раз.
                local_labels = labels[y : y + h, x : x + w]
                component_mask = local_labels == label_idx
                local_text_zone = text_like_zone[y : y + h, x : x + w]
                text_overlap = float(np.mean(local_text_zone[component_mask]))
                is_text_critical = aspect > 8.0 and text_overlap > 0.2
                if is_text_critical or area >= object_min_area or (
                    area > 3 and aspect > settings.min_scratch_aspect
                ):
                    filtered_region = filtered[y : y + h, x : x + w]
                    filtered_region[component_mask] = 255
                    max_aspect = max(max_aspect, float(aspect))
                    local_score = float((aspect / 15.0) + (area / 500.0))
                    if text_overlap > 0.2:
                        local_score *= 1.3

                    # Penalty for structural "emptiness": if inside anomaly region
                    # current gradient is much weaker than text gradient in reference.
                    ref_region = ref_grad_mag[y : y + h, x : x + w]
                    cur_region = cur_grad_mag[y : y + h, x : x + w]
                    ref_object_grad = float(np.mean(ref_region[component_mask]))
                    cur_object_grad = float(np.mean(cur_region[component_mask]))
                    if ref_object_grad > (cur_object_grad + 12.0):
                        local_score += 0.4

                max_object_score = max(max_object_score, local_score)

        filtered = cv2.dilate(filtered, np.ones((3, 3), dtype=np.uint8), iterations=1)

        # Brightest tail statistics should be computed only on detected anomaly pixels.
        # Using the full frame can saturate score to 1.0 even on visually stable images.
        anomaly_pixels = filtered > 0
        if np.any(anomaly_pixels):
            active_values = gray_blur[anomaly_pixels]
            k = max(1, int(active_values.size * 0.02))  # top 2% inside anomaly regions
            top_mean = float(np.mean(np.partition(active_values, -k)[-k:])) / 255.0
        else:
            top_mean = 0.0

        heuristic_score = float(np.clip((max_object_score * 0.85) + (top_mean * 0.55), 0.0, 1.0))
        if max_aspect > settings.scratch_aspect_floor:
            heuristic_score = max(heuristic_score, settings.scratch_score_floor)
        heuristic_mask = cv2.cvtColor(filtered, cv2.COLOR_GRAY2BGR)

        if settings.use_patchcore and self._anomaly_engine is not None:
            try:
                prediction = self._anomaly_engine.predict(image=diff_map)
                model_score = float(prediction.pred_score)
                mask = prediction.pred_mask.astype(np.uint8) * 255
                if len(mask.shape) == 2:
                    mask = cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR)
                # Merge model mask with heuristic mask so thin scratches seen in diff_map
                # are not lost when model mask is conservative on textured surfaces.
                merged_mask = cv2.bitwise_or(mask, heuristic_mask)
                # Use the larger score to avoid missing obvious defects when model score is conservative.
                return max(model_score, heuristic_score), merged_mask
            except Exception:
                pass

        return heuristic_score, heuristic_mask

    def _build_heatmap_gray(self, mask: np.ndarray, diff_map: Optional[np.ndarray] = None) -> np.ndarray:
        """Single-channel anomaly energy for gray_u8 SHM (orchestrator/UI apply JET).

        Gate diff by the defect mask. Global min-max of the whole ROI turns residual
        lighting into a solid green JET blob even when there is no defect.
        """
        mask_gray = mask if mask.ndim == 2 else cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        if diff_map is None:
            return mask_gray

        diff_gray = diff_map if diff_map.ndim == 2 else cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        gate = cv2.dilate(mask_gray, np.ones((11, 11), dtype=np.uint8), iterations=1)
        gated_diff = np.where(gate > 0, diff_gray, 0).astype(np.uint8)
        return cv2.max(mask_gray, gated_diff)

    def _colorize_heatmap(self, heatmap_gray: np.ndarray, mask: np.ndarray) -> np.ndarray:
        heatmap = cv2.applyColorMap(heatmap_gray, cv2.COLORMAP_JET)
        mask_gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        mask_float = (mask_gray.astype(np.float32) / 255.0)[..., np.newaxis]
        boosted = heatmap.astype(np.float32) * (1.0 + 0.5 * mask_float)
        return np.clip(boosted, 0, 255).astype(np.uint8)

