from dataclasses import dataclass, field
from typing import Optional, Tuple

import numpy as np


@dataclass
class RoiSubZoneScore:
    zone_id: str
    label: str
    anomaly_score: float
    threshold: float
    status: str


@dataclass
class InspectionResult:
    product_type: str
    status: str  # ГОДЕН | БРАК
    anomaly_score: float  # max по зонам, сравнивается с threshold
    threshold: float
    detector_id: str = ""
    raw_anomaly_score: float = 0.0  # до FP-recheck
    rechecked_zones_count: int = 0
    recheck_adjustment: float = 0.0  # raw - final после FP
    rechecked_zone_ids: list[str] | None = None
    main_roi_score: float = 0.0
    sub_zone_scores: list[RoiSubZoneScore] = field(default_factory=list)
    # Размер карты diff/heatmap — сохранить с инспекцией для позднего POST /fp-zones.
    heatmap_w: int = 0
    heatmap_h: int = 0
    aligned_image: Optional[np.ndarray] = None
    diff_map: Optional[np.ndarray] = None
    # BGR colormap for API/base64; UI SHM expects gray_u8 (see heatmap_u8).
    heatmap: Optional[np.ndarray] = None
    heatmap_u8: Optional[np.ndarray] = None
    segmentation_mask: Optional[np.ndarray] = None


@dataclass
class RoiSubZone:
    id: str
    product_type: str
    points: list[Tuple[float, float]]
    threshold: Optional[float] = None
    label: str = ""
    created_at: str = ""


@dataclass
class FPZone:
    """Зона ложного срабатывания: при создании запоминается baseline активности diff/маски."""

    id: str
    product_type: str
    points_norm_heatmap: list[Tuple[float, float]]
    points_norm_ref: list[Tuple[float, float]]
    heatmap_w: int
    heatmap_h: int
    created_at: str
    # Профиль «нормального» шума в зоне — сравнивается при fp-recheck.
    baseline_diff_q90: float = 0.0
    baseline_diff_max: float = 0.0
    baseline_active_ratio: float = 0.0
    baseline_score: float = 0.0
    note: str = ""
    # Опциональный путь к кадру/heatmap, по которому рисовали зону.
    source_frame_path: str = ""
