from typing import Optional

from pydantic import BaseModel, Field


class ShmImageOutput(BaseModel):
    path: str
    width: int
    height: int
    stride: int
    channels: int
    dtype: str = "uint8"


class RoiSubZoneScoreResponse(BaseModel):
    zone_id: str
    label: str
    anomaly_score: float
    threshold: float
    status: str


class InspectResponse(BaseModel):
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    detector_id: str
    raw_anomaly_score: float
    rechecked_zones_count: int
    recheck_adjustment: float
    rechecked_zone_ids: list[str] = Field(default_factory=list)
    main_roi_score: float = 0.0
    sub_zone_scores: list[RoiSubZoneScoreResponse] = Field(default_factory=list)


class InspectWithVisualsResponse(InspectResponse):
    aligned_image_b64: Optional[str] = None
    diff_map_b64: Optional[str] = None
    heatmap_b64: Optional[str] = None
    segmentation_mask_b64: Optional[str] = None


class UploadRefResponse(BaseModel):
    message: str
    product_type: str
    reference_b64: str


class ReferenceResponse(BaseModel):
    product_type: str
    reference_b64: str


class AnalysisSettingsValues(BaseModel):
    default_threshold: float = 0.25
    use_patchcore: bool = True
    min_defect_area: int = 6
    min_scratch_aspect: float = 3.0
    min_diff_signal: float = 12.0
    diff_percentile: float = 98.0
    scratch_score_floor: float = 0.35
    scratch_aspect_floor: float = 4.5
    edge_suppress_factor: float = 0.2
    text_min_contrast: int = 55
    text_structure_threshold: int = 30
    contrast_loss_boost: float = 2.0
    contrast_loss_ref_grad: float = 40.0
    contrast_loss_cur_grad: float = 15.0
    enable_clahe: bool = True
    clahe_clip_limit: float = 1.2
    fp_recheck_enabled: bool = True
    fp_trigger_diff_q90: float = 22.0


class AnalysisSettingsUpdateRequest(BaseModel):
    default_threshold: Optional[float] = None
    use_patchcore: Optional[bool] = None
    min_defect_area: Optional[int] = None
    min_scratch_aspect: Optional[float] = None
    min_diff_signal: Optional[float] = None
    diff_percentile: Optional[float] = None
    scratch_score_floor: Optional[float] = None
    scratch_aspect_floor: Optional[float] = None
    edge_suppress_factor: Optional[float] = None
    text_min_contrast: Optional[int] = None
    text_structure_threshold: Optional[int] = None
    contrast_loss_boost: Optional[float] = None
    contrast_loss_ref_grad: Optional[float] = None
    contrast_loss_cur_grad: Optional[float] = None
    enable_clahe: Optional[bool] = None
    clahe_clip_limit: Optional[float] = None
    fp_recheck_enabled: Optional[bool] = None
    fp_trigger_diff_q90: Optional[float] = None


class AnalysisSettingsResponse(BaseModel):
    analysis_profile: str
    settings: AnalysisSettingsValues
    defaults: AnalysisSettingsValues
    overrides: dict[str, float | int | bool] = Field(default_factory=dict)


class DetectorHealthResponse(BaseModel):
    status: str
    service: str
    detector_id: str


class ShmFrameRequest(BaseModel):
    product_type: str
    shm_name: str
    width: int
    height: int
    stride: Optional[int] = None
    shm_offset: int = 0
    threshold: Optional[float] = None
    detector_id: Optional[str] = None
    algorithm_params: Optional[dict] = None


class ShmVisualsRequest(ShmFrameRequest):
    aligned_image_u8_output_path: Optional[str] = None
    diff_map_u8_output_path: Optional[str] = None
    heatmap_u8_output_path: Optional[str] = None
    segmentation_mask_u8_output_path: Optional[str] = None


class ShmVisualsResponse(BaseModel):
    product_type: str
    detector_id: str
    aligned_image_u8: Optional[ShmImageOutput] = None
    diff_map_u8: Optional[ShmImageOutput] = None
    heatmap_u8: Optional[ShmImageOutput] = None
    segmentation_mask_u8: Optional[ShmImageOutput] = None


class RoiPoint(BaseModel):
    x: float
    y: float


class RoiPolygonRequest(BaseModel):
    product_type: str
    points: list[RoiPoint]
    algorithm_params: Optional[dict] = None


class RoiPolygonResponse(BaseModel):
    product_type: str
    points: list[RoiPoint]


class FPZonePoint(BaseModel):
    x: float
    y: float


class FPZoneCreateRequest(BaseModel):
    product_type: str
    points: list[FPZonePoint]
    heatmap_w: int
    heatmap_h: int
    note: str = ""


class FPZoneResponse(BaseModel):
    id: str
    product_type: str
    points_norm_heatmap: list[FPZonePoint]
    points_norm_ref: list[FPZonePoint]
    heatmap_w: int
    heatmap_h: int
    created_at: str
    note: str


class FPZoneListResponse(BaseModel):
    product_type: str
    zones: list[FPZoneResponse] = Field(default_factory=list)


class RoiSubZonePoint(BaseModel):
    x: float
    y: float


class RoiSubZoneCreateRequest(BaseModel):
    product_type: str
    points: list[RoiSubZonePoint]
    threshold: Optional[float] = None
    label: str = ""


class RoiSubZoneUpdateRequest(BaseModel):
    threshold: Optional[float] = None
    label: Optional[str] = None
    points: Optional[list[RoiSubZonePoint]] = None


class RoiSubZoneResponse(BaseModel):
    id: str
    product_type: str
    points: list[RoiSubZonePoint]
    threshold: Optional[float] = None
    label: str = ""
    created_at: str


class RoiSubZoneListResponse(BaseModel):
    product_type: str
    zones: list[RoiSubZoneResponse] = Field(default_factory=list)
