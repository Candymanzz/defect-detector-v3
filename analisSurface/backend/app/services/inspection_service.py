import base64
import json
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
    polygon_mask_from_norm_points,
    validate_polygon_inside_parent,
    validate_polygon_points,
)
from app.services.inspection_models import FPZone, InspectionResult, RoiSubZone, RoiSubZoneScore


class InspectionService:
    def __init__(self) -> None:
        self.references: Dict[str, np.ndarray] = {}
        self._ref_orb_cache: Dict[str, Tuple[list, Optional[np.ndarray]]] = {}
        self.roi_polygons: Dict[str, list[Tuple[float, float]]] = {}
        self.roi_sub_zones: Dict[str, list[RoiSubZone]] = {}
        self._roi_sub_zones_file = Path(__file__).resolve().parent.parent / "data" / "roi_sub_zones.json"
        self._analysis_settings_file = Path(__file__).resolve().parent.parent / "data" / "analysis_settings.json"
        self._analysis_settings_overrides: Dict[str, dict[str, object]] = {}
        self._orb = cv2.ORB_create(nfeatures=1800)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        self._fp_zones_file = Path(__file__).resolve().parent.parent / "data" / "fp_zones.json"
        self.fp_zones: Dict[str, list[FPZone]] = {}
        self._last_diff_maps: Dict[str, np.ndarray] = {}
        self._last_segmentation_masks: Dict[str, np.ndarray] = {}

        self._anomaly_engine = None
        self._load_anomalib_engine()
        self._load_fp_zones()
        self._load_roi_sub_zones()
        self._load_analysis_settings()

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
        self._update_ref_orb_cache(product_type, image)

    def set_reference_frame(self, product_type: str, frame: np.ndarray) -> None:
        image = frame.copy()
        self.references[product_type] = image
        self._update_ref_orb_cache(product_type, image)

    def get_reference(self, product_type: str) -> Optional[np.ndarray]:
        return self.references.get(product_type)

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
        overrides = self._analysis_settings_overrides.get(analysis_profile, {})
        return AnalysisSettings.from_overrides(overrides)

    def get_analysis_settings_overrides(self, analysis_profile: str) -> dict[str, object]:
        return dict(self._analysis_settings_overrides.get(analysis_profile, {}))

    def update_analysis_settings(self, analysis_profile: str, partial: dict[str, object]) -> dict[str, object]:
        current = dict(self._analysis_settings_overrides.get(analysis_profile, {}))
        allowed = AnalysisSettings.field_names()
        for key, value in partial.items():
            if key not in allowed:
                raise ValueError(f"Unknown analysis setting: {key}")
            current[key] = value
        AnalysisSettings.from_overrides(current)
        self._analysis_settings_overrides[analysis_profile] = current
        self._save_analysis_settings()
        return dict(current)

    def reset_analysis_settings(self, analysis_profile: str) -> dict[str, object]:
        self._analysis_settings_overrides.pop(analysis_profile, None)
        self._save_analysis_settings()
        return {}

    def add_fp_zone(
        self,
        product_type: str,
        points_norm_heatmap: list[Tuple[float, float]],
        heatmap_w: int,
        heatmap_h: int,
        note: str = "",
    ) -> FPZone:
        normalized = validate_polygon_points(points_norm_heatmap, "FP polygon")
        if polygon_area(normalized) < 0.0001:
            raise ValueError("FP polygon area is too small")
        if heatmap_w <= 0 or heatmap_h <= 0:
            raise ValueError("heatmap size must be positive")
        baseline = self._measure_fp_zone_activity(product_type, normalized)
        zone = FPZone(
            id=str(uuid.uuid4()),
            product_type=product_type,
            points_norm_heatmap=normalized,
            points_norm_ref=list(normalized),
            heatmap_w=int(heatmap_w),
            heatmap_h=int(heatmap_h),
            created_at=datetime.now(timezone.utc).isoformat(),
            baseline_diff_q90=baseline["diff_q90"],
            baseline_diff_max=baseline["diff_max"],
            baseline_active_ratio=baseline["active_ratio"],
            baseline_score=baseline["score"],
            note=note.strip(),
        )
        self.fp_zones.setdefault(product_type, []).append(zone)
        self._save_fp_zones()
        return zone

    def get_fp_zones(self, product_type: str) -> list[FPZone]:
        return list(self.fp_zones.get(product_type, []))

    def delete_fp_zone(self, zone_id: str) -> bool:
        for product_type, zones in self.fp_zones.items():
            retained = [zone for zone in zones if zone.id != zone_id]
            if len(retained) != len(zones):
                self.fp_zones[product_type] = retained
                self._save_fp_zones()
                return True
        return False

    def inspect(
        self,
        product_type: str,
        image_bytes: bytes,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
        detector_id: Optional[str] = None,
        alignment_h_ref_to_cur: Optional[list[list[float]]] = None,
    ) -> InspectionResult:
        current = self._decode_image(image_bytes)
        return self.inspect_frame(
            product_type=product_type,
            frame=current,
            threshold=threshold,
            include_visuals=include_visuals,
            detector_id=detector_id,
            alignment_h_ref_to_cur=alignment_h_ref_to_cur,
        )

    def inspect_frame(
        self,
        product_type: str,
        frame: np.ndarray,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
        detector_id: Optional[str] = None,
        alignment_h_ref_to_cur: Optional[list[list[float]]] = None,
    ) -> InspectionResult:
        settings = self.get_analysis_settings(product_type)
        reference = self.get_reference(product_type)
        if reference is None:
            raise ValueError(f"Reference for product_type '{product_type}' is not set")

        aligned = self._align_to_reference(
            frame,
            reference,
            product_type,
            alignment_h_ref_to_cur=alignment_h_ref_to_cur,
        )

        polygon = self.get_roi_polygon(product_type)
        if polygon is not None:
            aligned, reference = mask_to_polygon(aligned, reference, polygon)

        diff_map = self._compute_advanced_difference(aligned, reference, settings)

        anomaly_score, segmentation_mask = self._run_anomaly_model(diff_map, settings)
        self._last_diff_maps[product_type] = diff_map.copy()
        self._last_segmentation_masks[product_type] = segmentation_mask.copy()
        raw_score = anomaly_score
        fp_recheck = self._recheck_fp_zones(product_type, diff_map, segmentation_mask, raw_score, settings)
        filtered_diff_map = fp_recheck["filtered_diff_map"]
        segmentation_mask = fp_recheck["filtered_mask"]

        inspection_threshold = (
            threshold if threshold is not None else settings.default_threshold
        )
        sub_zones = self.get_roi_sub_zones(product_type)
        h, w = diff_map.shape[:2]
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

        heatmap_u8 = self._build_heatmap_gray(segmentation_mask, diff_map) if include_visuals else None
        heatmap = self._colorize_heatmap(heatmap_u8, segmentation_mask) if heatmap_u8 is not None else None
        if include_visuals and heatmap is not None:
            heatmap = self._draw_fp_zone_overlay(heatmap, self.get_fp_zones(product_type), fp_recheck["rechecked_zone_ids"])
            heatmap = self._draw_roi_sub_zone_overlay(heatmap, sub_zones, sub_zone_scores)

        return InspectionResult(
            product_type=product_type,
            status=status,
            anomaly_score=anomaly_score,
            threshold=inspection_threshold,
            detector_id=get_application_id(),
            raw_anomaly_score=raw_score,
            rechecked_zones_count=len(fp_recheck["rechecked_zone_ids"]),
            recheck_adjustment=raw_score - fp_recheck["final_score"],
            rechecked_zone_ids=fp_recheck["rechecked_zone_ids"],
            main_roi_score=main_roi_score,
            sub_zone_scores=sub_zone_scores,
            aligned_image=aligned if include_visuals else None,
            diff_map=diff_map if include_visuals else None,
            heatmap=heatmap if include_visuals else None,
            heatmap_u8=heatmap_u8 if include_visuals else None,
            segmentation_mask=segmentation_mask if include_visuals else None,
        )

    def _load_analysis_settings(self) -> None:
        self._analysis_settings_overrides = {}
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
        except Exception:
            self._analysis_settings_overrides = {}

    def _save_analysis_settings(self) -> None:
        self._analysis_settings_file.parent.mkdir(parents=True, exist_ok=True)
        entries = [
            {"analysis_profile": analysis_profile, "overrides": overrides}
            for analysis_profile, overrides in self._analysis_settings_overrides.items()
        ]
        self._analysis_settings_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")

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
                zone = FPZone(
                    id=str(entry.get("id", str(uuid.uuid4()))),
                    product_type=product_type,
                    points_norm_heatmap=[(float(p[0]), float(p[1])) for p in entry.get("points_norm_heatmap", [])],
                    points_norm_ref=[(float(p[0]), float(p[1])) for p in entry.get("points_norm_ref", entry.get("points_norm_heatmap", []))],
                    heatmap_w=int(entry.get("heatmap_w", 1)),
                    heatmap_h=int(entry.get("heatmap_h", 1)),
                    created_at=str(entry.get("created_at", datetime.now(timezone.utc).isoformat())),
                    baseline_diff_q90=float(entry.get("baseline_diff_q90", 0.0)),
                    baseline_diff_max=float(entry.get("baseline_diff_max", 0.0)),
                    baseline_active_ratio=float(entry.get("baseline_active_ratio", 0.0)),
                    baseline_score=float(entry.get("baseline_score", 0.0)),
                    note=str(entry.get("note", "")),
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
                        "baseline_diff_q90": zone.baseline_diff_q90,
                        "baseline_diff_max": zone.baseline_diff_max,
                        "baseline_active_ratio": zone.baseline_active_ratio,
                        "baseline_score": zone.baseline_score,
                        "note": zone.note,
                    }
                )
        self._fp_zones_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")

    def _measure_fp_zone_activity(self, product_type: str, points: list[Tuple[float, float]]) -> dict[str, float]:
        diff_map = self._last_diff_maps.get(product_type)
        segmentation_mask = self._last_segmentation_masks.get(product_type)
        if diff_map is None or segmentation_mask is None:
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}
        return self._measure_zone_activity(diff_map, segmentation_mask, points)

    def _measure_zone_activity(
        self,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        points: list[Tuple[float, float]],
    ) -> dict[str, float]:
        h, w = diff_map.shape[:2]
        zone_mask = polygon_mask_from_norm_points(w, h, points) > 0
        if not np.any(zone_mask):
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}

        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        zone_diff = diff_gray[zone_mask]
        zone_active = seg_gray[zone_mask] > 0
        diff_q90 = float(np.percentile(zone_diff, 90))
        diff_max = float(np.max(zone_diff))
        active_ratio = float(np.mean(zone_active))
        score = float(np.clip((diff_q90 / 255.0) * 0.8 + (diff_max / 255.0) * 0.2 + active_ratio * 1.2, 0.0, 1.0))
        return {"diff_q90": diff_q90, "diff_max": diff_max, "active_ratio": active_ratio, "score": score}

    def _should_suppress_fp_zone(self, zone: FPZone, activity: dict[str, float]) -> bool:
        has_baseline = any(
            value > 0.0
            for value in (
                zone.baseline_diff_q90,
                zone.baseline_diff_max,
                zone.baseline_active_ratio,
                zone.baseline_score,
            )
        )
        if not has_baseline:
            # Old zones have no baseline. Be conservative so a real new scratch is not hidden.
            return activity["score"] <= 0.35 and activity["active_ratio"] <= 0.08 and activity["diff_q90"] <= 45.0

        q90_limit = max(zone.baseline_diff_q90 + 18.0, zone.baseline_diff_q90 * 1.45)
        max_limit = max(zone.baseline_diff_max + 25.0, zone.baseline_diff_max * 1.35)
        active_limit = max(zone.baseline_active_ratio + 0.04, zone.baseline_active_ratio * 2.0)
        score_limit = max(zone.baseline_score + 0.20, zone.baseline_score * 1.6)
        return (
            activity["diff_q90"] <= q90_limit
            and activity["diff_max"] <= max_limit
            and activity["active_ratio"] <= active_limit
            and activity["score"] <= score_limit
        )

    def _score_region(
        self,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        region_mask: np.ndarray,
        settings: AnalysisSettings,
    ) -> float:
        if not np.any(region_mask):
            return 0.0
        masked_diff = diff_map.copy()
        masked_diff[~region_mask] = 0
        score, _ = self._run_anomaly_model(masked_diff, settings)
        activity = self._measure_zone_activity(diff_map, segmentation_mask, self._mask_to_norm_points(region_mask))
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

    def _recheck_fp_zones(
        self,
        product_type: str,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        raw_score: float,
        settings: AnalysisSettings,
    ) -> dict:
        zones = self.get_fp_zones(product_type)
        if not zones or not settings.fp_recheck_enabled:
            return {
                "final_score": raw_score,
                "rechecked_zone_ids": [],
                "filtered_mask": segmentation_mask,
                "filtered_diff_map": diff_map,
            }

        h, w = diff_map.shape[:2]
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        seg_active = (seg_gray > 0).astype(np.uint8) * 255
        # Small tolerance around anomaly mask to avoid frame-to-frame misses.
        seg_active_dilated = cv2.dilate(seg_active, np.ones((9, 9), dtype=np.uint8), iterations=1)
        rechecked_zone_ids: list[str] = []
        combined_suppress_mask = np.zeros((h, w), dtype=bool)
        for zone in zones:
            zone_mask = polygon_mask_from_norm_points(w, h, zone.points_norm_ref)
            zone_pixels = zone_mask > 0
            if not np.any(zone_pixels):
                continue
            zone_overlap_pixels = float(np.count_nonzero((seg_active_dilated > 0) & zone_pixels))
            activity = self._measure_zone_activity(diff_map, segmentation_mask, zone.points_norm_ref)
            # Trigger recheck either by mask overlap (with tolerance) or by strong diff energy.
            has_zone_activation = zone_overlap_pixels > 0 or activity["diff_q90"] >= settings.fp_trigger_diff_q90
            if not has_zone_activation:
                continue
            if not self._should_suppress_fp_zone(zone, activity):
                continue
            rechecked_zone_ids.append(zone.id)
            # Suppress only activated FP regions, then recompute the score from the remaining image.
            suppress_mask = cv2.dilate(zone_mask, np.ones((5, 5), dtype=np.uint8), iterations=1) > 0
            combined_suppress_mask |= suppress_mask

        if not rechecked_zone_ids:
            return {
                "final_score": raw_score,
                "rechecked_zone_ids": [],
                "filtered_mask": segmentation_mask,
                "filtered_diff_map": diff_map,
            }

        filtered_diff_map = diff_map.copy()
        filtered_diff_map[combined_suppress_mask] = 0
        remaining_score, filtered_mask = self._run_anomaly_model(filtered_diff_map, settings)
        filtered_mask[combined_suppress_mask] = 0
        final_score = float(min(raw_score, remaining_score))
        return {
            "final_score": final_score,
            "rechecked_zone_ids": rechecked_zone_ids,
            "filtered_mask": filtered_mask,
            "filtered_diff_map": filtered_diff_map,
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

    def _draw_fp_zone_overlay(self, heatmap: np.ndarray, zones: list[FPZone], rechecked_ids: list[str]) -> np.ndarray:
        if not zones:
            return heatmap
        overlay = heatmap.copy()
        h, w = heatmap.shape[:2]
        for zone in zones:
            pts = np.array(
                [[int(round(x * (w - 1))), int(round(y * (h - 1)))] for x, y in zone.points_norm_ref],
                dtype=np.int32,
            )
            if len(pts) < 3:
                continue
            color = (50, 220, 50) if zone.id in rechecked_ids else (40, 150, 220)
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
        alignment_h_ref_to_cur: Optional[list[list[float]]] = None,
    ) -> np.ndarray:
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
    def _align_with_geometry_homography(
        current: np.ndarray,
        reference: np.ndarray,
        alignment_h_ref_to_cur: Optional[list[list[float]]],
    ) -> Optional[np.ndarray]:
        if alignment_h_ref_to_cur is None:
            return None
        try:
            homography = np.asarray(alignment_h_ref_to_cur, dtype=np.float64)
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
            w = max(1, int(stats[label_idx, cv2.CC_STAT_WIDTH]))
            h = max(1, int(stats[label_idx, cv2.CC_STAT_HEIGHT]))
            aspect = max(w / h, h / w)
            object_min_area = 5 if aspect > 6.0 else min_area

            # Keep tiny but clearly elongated components (scratch-like traces).
            if area >= object_min_area or (area > 3 and aspect > settings.min_scratch_aspect):
                component_mask = labels == label_idx
                text_overlap = 0.0
                if np.any(component_mask):
                    text_overlap = float(np.mean(text_like_zone[component_mask]))
                is_text_critical = aspect > 8.0 and text_overlap > 0.2
                if is_text_critical or area >= object_min_area or (
                    area > 3 and aspect > settings.min_scratch_aspect
                ):
                    filtered[component_mask] = 255
                    max_aspect = max(max_aspect, float(aspect))
                    local_score = float((aspect / 15.0) + (area / 500.0))
                    if text_overlap > 0.2:
                        local_score *= 1.3

                    # Penalty for structural "emptiness": if inside anomaly region
                    # current gradient is much weaker than text gradient in reference.
                    ref_object_grad = float(np.mean(ref_grad_mag[component_mask])) if np.any(component_mask) else 0.0
                    cur_object_grad = float(np.mean(cur_grad_mag[component_mask])) if np.any(component_mask) else 0.0
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
        """Single-channel anomaly energy for gray_u8 SHM (orchestrator/UI apply JET)."""
        mask_gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        if diff_map is None:
            return mask_gray

        # Combine model/anomaly mask with raw difference energy so thin scratches stay visible.
        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        diff_norm = cv2.normalize(diff_gray, None, 0, 255, cv2.NORM_MINMAX)
        combined = cv2.max(mask_gray, diff_norm)
        combined = cv2.normalize(combined, None, 0, 255, cv2.NORM_MINMAX)
        combined_gamma = np.power(combined.astype(np.float32) / 255.0, 0.8) * 255.0
        return np.clip(combined_gamma, 0, 255).astype(np.uint8)

    def _colorize_heatmap(self, heatmap_gray: np.ndarray, mask: np.ndarray) -> np.ndarray:
        heatmap = cv2.applyColorMap(heatmap_gray, cv2.COLORMAP_JET)
        mask_gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        mask_float = (mask_gray.astype(np.float32) / 255.0)[..., np.newaxis]
        boosted = heatmap.astype(np.float32) * (1.0 + 0.5 * mask_float)
        return np.clip(boosted, 0, 255).astype(np.uint8)

