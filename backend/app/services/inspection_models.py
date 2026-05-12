from dataclasses import dataclass
from typing import Optional, Tuple

import numpy as np


@dataclass
class InspectionResult:
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    detector_id: str = ""
    raw_anomaly_score: float = 0.0
    rechecked_zones_count: int = 0
    recheck_adjustment: float = 0.0
    rechecked_zone_ids: list[str] | None = None
    aligned_image: Optional[np.ndarray] = None
    diff_map: Optional[np.ndarray] = None
    heatmap: Optional[np.ndarray] = None
    segmentation_mask: Optional[np.ndarray] = None


@dataclass
class FPZone:
    id: str
    product_type: str
    points_norm_heatmap: list[Tuple[float, float]]
    points_norm_ref: list[Tuple[float, float]]
    heatmap_w: int
    heatmap_h: int
    created_at: str
    baseline_diff_q90: float = 0.0
    baseline_diff_max: float = 0.0
    baseline_active_ratio: float = 0.0
    baseline_score: float = 0.0
    note: str = ""
