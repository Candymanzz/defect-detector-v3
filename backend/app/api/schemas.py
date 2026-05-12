from typing import Optional

from pydantic import BaseModel, Field


class ShmImageOutput(BaseModel):
    path: str
    width: int
    height: int
    stride: int
    channels: int
    dtype: str = "uint8"


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
