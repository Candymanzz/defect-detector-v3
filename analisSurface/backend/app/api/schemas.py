"""Pydantic-модели HTTP API. См. docstring на роутерах в app/api/*_routes.py."""

from typing import Optional

from pydantic import BaseModel, Field


class ShmImageOutput(BaseModel):
    """Метаданные изображения, записанного в SHM (ответ inspect-shm-visuals)."""

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


class FPZoneScoreResponse(BaseModel):
    zone_id: str
    triggered_vs_reference: bool
    applied_fp_etalon: bool
    residual_score: float
    status: str
    note: str = ""


class InspectResponse(BaseModel):
    """Результат инспекции без тяжёлых картинок (/inspect-shm, база для /inspect-shm-visuals)."""

    product_type: str
    status: str  # ГОДЕН | БРАК
    anomaly_score: float
    threshold: float
    detector_id: str
    raw_anomaly_score: float
    rechecked_zones_count: int
    recheck_adjustment: float
    rechecked_zone_ids: list[str] = Field(default_factory=list)
    main_roi_score: float = 0.0
    sub_zone_scores: list[RoiSubZoneScoreResponse] = Field(default_factory=list)
    inspection_id: Optional[str] = None
    learned_normal_matches_count: int = 0
    learned_normal_adjustment: float = 0.0
    matched_accepted_case_ids: list[str] = Field(default_factory=list)
    # Display-only polygons for saved-normal matches. They do not affect the
    # score or verdict and let production UI mark already excluded areas.
    excluded_normal_zones: list[dict] = Field(default_factory=list)
    fp_zone_scores: list[FPZoneScoreResponse] = Field(default_factory=list)


class InspectWithVisualsResponse(InspectResponse):
    """Как InspectResponse + base64-визуалы (/inspect multipart)."""

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
    enable_internal_alignment: bool = False


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
    enable_internal_alignment: Optional[bool] = None


class AnalysisSettingsResponse(BaseModel):
    analysis_profile: str
    settings: AnalysisSettingsValues
    defaults: AnalysisSettingsValues
    overrides: dict[str, float | int | bool] = Field(default_factory=dict)


class SimpleSettingsKnobs(BaseModel):
    threshold: float = Field(..., gt=0.0, le=1.0)
    sensitivity: float = Field(..., ge=0.0, le=1.0)


class ProSettingsKnobs(BaseModel):
    threshold: float = Field(..., gt=0.0, le=1.0)
    noise_tolerance: float = Field(..., ge=0.0, le=1.0)
    scratch_sensitivity: float = Field(..., ge=0.0, le=1.0)
    edge_suppression: float = Field(..., ge=0.0, le=1.0)
    text_handling: float = Field(..., ge=0.0, le=1.0)
    preprocess_strength: float = Field(..., ge=0.0, le=1.0)


class SimpleSettingsResponse(BaseModel):
    analysis_profile: str
    knobs: Optional[SimpleSettingsKnobs] = None
    settings: AnalysisSettingsValues
    defaults: AnalysisSettingsValues
    overrides: dict[str, float | int | bool] = Field(default_factory=dict)


class ProSettingsResponse(BaseModel):
    analysis_profile: str
    knobs: Optional[ProSettingsKnobs] = None
    settings: AnalysisSettingsValues
    defaults: AnalysisSettingsValues
    overrides: dict[str, float | int | bool] = Field(default_factory=dict)


class DetectorHealthResponse(BaseModel):
    status: str
    service: str
    detector_id: str


class ShmFrameRequest(BaseModel):
    """Вход для /upload-ref-shm, /inspect-shm: BGR-кадр в shared memory."""

    product_type: str
    shm_name: str
    width: int
    height: int
    stride: Optional[int] = None  # default: width * 3
    shm_offset: int = 0
    threshold: Optional[float] = None  # перекрывает analysis_settings.default_threshold
    detector_id: Optional[str] = None
    algorithm_params: Optional[dict] = None
    analysis_profile: Optional[str] = None
    analysis_test_settings: Optional[dict] = None
    alignment_h_ref_to_cur: Optional[list[float] | list[list[float]]] = None  # 3x3 от geometry
    skip_learning_review: bool = False
    test_analyze: bool = False


class ShmVisualsRequest(ShmFrameRequest):
    """Вход /inspect-shm-visuals: кадр + пути output SHM (только запрошенные поля пишутся)."""

    aligned_image_u8_output_path: Optional[str] = None
    diff_map_u8_output_path: Optional[str] = None
    heatmap_u8_output_path: Optional[str] = None
    heatmap_max_width: Optional[int] = None  # уменьшить heatmap перед записью
    segmentation_mask_u8_output_path: Optional[str] = None


class ShmVisualsResponse(InspectResponse):
    """Ответ /inspect-shm-visuals: вердикт + ShmImageOutput на каждый записанный визуал."""

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
    has_crop: bool = False
    reference_hash: str = ""


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
