import mmap
import os
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Optional

import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.runtime import get_application_id
from app.services.inspection_service import InspectionService


router = APIRouter()
inspection_service = InspectionService()


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


def _to_inspect_response(result) -> InspectResponse:
    return InspectResponse(
        product_type=result.product_type,
        status=result.status,
        anomaly_score=result.anomaly_score,
        threshold=result.threshold,
        detector_id=result.detector_id,
        raw_anomaly_score=result.raw_anomaly_score,
        rechecked_zones_count=result.rechecked_zones_count,
        recheck_adjustment=result.recheck_adjustment,
        rechecked_zone_ids=result.rechecked_zone_ids or [],
    )


def _to_visuals_response(result, visual_outputs: dict[str, ShmImageOutput]) -> ShmVisualsResponse:
    return ShmVisualsResponse(
        product_type=result.product_type,
        detector_id=result.detector_id,
        aligned_image_u8=visual_outputs.get("aligned_image"),
        diff_map_u8=visual_outputs.get("diff_map"),
        heatmap_u8=visual_outputs.get("heatmap"),
        segmentation_mask_u8=visual_outputs.get("segmentation_mask"),
    )


def _resolve_shm_path(shm_name: str) -> Path:
    normalized = shm_name.strip()
    if not normalized:
        raise ValueError("shm_name must not be empty")

    if normalized.startswith("/dev/shm/"):
        path = Path(normalized)
    else:
        path = Path("/dev/shm") / normalized.lstrip("/")

    resolved = path.resolve(strict=False)
    shm_root = Path("/dev/shm").resolve(strict=False)
    if shm_root not in (resolved, *resolved.parents):
        raise ValueError("shm_name must resolve inside /dev/shm")
    return resolved


@contextmanager
def _open_bgr_shm_frame(
    shm_name: str,
    width: int,
    height: int,
    stride: Optional[int] = None,
    shm_offset: int = 0,
) -> Iterator[np.ndarray]:
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    if shm_offset < 0:
        raise ValueError("shm_offset must be >= 0")

    row_stride = stride if stride is not None else width * 3
    min_stride = width * 3
    if row_stride < min_stride:
        raise ValueError(f"stride must be at least width * 3 ({min_stride})")

    required_end = shm_offset + (height - 1) * row_stride + min_stride
    shm_path = _resolve_shm_path(shm_name)

    fd = os.open(shm_path, os.O_RDONLY)
    shm_map = None
    frame: Optional[np.ndarray] = None
    try:
        shm_size = os.fstat(fd).st_size
        if required_end > shm_size:
            raise ValueError(
                f"shared memory payload is too small: need end offset {required_end}, "
                f"but {shm_path} has {shm_size} bytes"
            )

        shm_map = mmap.mmap(fd, 0, access=mmap.ACCESS_READ)
        frame = np.ndarray(
            shape=(height, width, 3),
            dtype=np.uint8,
            buffer=shm_map,
            offset=shm_offset,
            strides=(row_stride, 3, 1),
        )
        yield frame
    finally:
        frame = None
        if shm_map is not None:
            shm_map.close()
        os.close(fd)


def _write_u8_image_to_shm(output_path: str, image: np.ndarray) -> ShmImageOutput:
    if image.dtype != np.uint8:
        image = np.clip(image, 0, 255).astype(np.uint8)

    contiguous = np.ascontiguousarray(image)
    if contiguous.ndim == 2:
        height, width = contiguous.shape
        channels = 1
        stride = width
    elif contiguous.ndim == 3:
        height, width, channels = contiguous.shape
        stride = width * channels
    else:
        raise ValueError("visual output must be a 2D or 3D uint8 image")

    shm_path = _resolve_shm_path(output_path)
    data = contiguous.tobytes()
    fd = os.open(shm_path, os.O_CREAT | os.O_RDWR, 0o666)
    try:
        os.ftruncate(fd, len(data))
        with mmap.mmap(fd, len(data), access=mmap.ACCESS_WRITE) as output_map:
            output_map.write(data)
            output_map.flush()
    finally:
        os.close(fd)

    return ShmImageOutput(
        path=str(shm_path),
        width=width,
        height=height,
        stride=stride,
        channels=channels,
    )


def _write_requested_visual_outputs(payload: ShmVisualsRequest, result) -> dict[str, ShmImageOutput]:
    requested = {
        "aligned_image": (payload.aligned_image_u8_output_path, result.aligned_image),
        "diff_map": (payload.diff_map_u8_output_path, result.diff_map),
        "heatmap": (payload.heatmap_u8_output_path, result.heatmap),
        "segmentation_mask": (payload.segmentation_mask_u8_output_path, result.segmentation_mask),
    }
    outputs: dict[str, ShmImageOutput] = {}
    for name, (output_path, image) in requested.items():
        if output_path is None:
            continue
        if image is None:
            raise ValueError(f"{name} output was requested but visuals are disabled")
        outputs[name] = _write_u8_image_to_shm(output_path, image)
    return outputs


@router.get("/detector/health", response_model=DetectorHealthResponse)
async def detector_health() -> DetectorHealthResponse:
    return DetectorHealthResponse(
        status="ok",
        service="python-detectors",
        detector_id=get_application_id(),
    )


@router.post("/roi-polygon", response_model=RoiPolygonResponse)
async def set_roi_polygon(payload: RoiPolygonRequest) -> RoiPolygonResponse:
    if inspection_service.get_reference(payload.product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")
    points = [(p.x, p.y) for p in payload.points]
    try:
        inspection_service.set_roi_polygon(product_type=payload.product_type, points=points)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return RoiPolygonResponse(product_type=payload.product_type, points=payload.points)


@router.post("/fp-zones", response_model=FPZoneResponse)
async def add_fp_zone(payload: FPZoneCreateRequest) -> FPZoneResponse:
    points = [(p.x, p.y) for p in payload.points]
    try:
        zone = inspection_service.add_fp_zone(
            product_type=payload.product_type,
            points_norm_heatmap=points,
            heatmap_w=payload.heatmap_w,
            heatmap_h=payload.heatmap_h,
            note=payload.note,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return FPZoneResponse(
        id=zone.id,
        product_type=zone.product_type,
        points_norm_heatmap=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_heatmap],
        points_norm_ref=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_ref],
        heatmap_w=zone.heatmap_w,
        heatmap_h=zone.heatmap_h,
        created_at=zone.created_at,
        note=zone.note,
    )


@router.get("/fp-zones/{product_type}", response_model=FPZoneListResponse)
async def get_fp_zones(product_type: str) -> FPZoneListResponse:
    zones = inspection_service.get_fp_zones(product_type)
    return FPZoneListResponse(
        product_type=product_type,
        zones=[
            FPZoneResponse(
                id=zone.id,
                product_type=zone.product_type,
                points_norm_heatmap=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_heatmap],
                points_norm_ref=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_ref],
                heatmap_w=zone.heatmap_w,
                heatmap_h=zone.heatmap_h,
                created_at=zone.created_at,
                note=zone.note,
            )
            for zone in zones
        ],
    )


@router.delete("/fp-zones/{zone_id}")
async def delete_fp_zone(zone_id: str) -> dict:
    deleted = inspection_service.delete_fp_zone(zone_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="FP zone not found")
    return {"deleted": True, "zone_id": zone_id}


@router.get("/roi-polygon/{product_type}", response_model=RoiPolygonResponse)
async def get_roi_polygon(product_type: str) -> RoiPolygonResponse:
    points = inspection_service.get_roi_polygon(product_type)
    if points is None:
        raise HTTPException(status_code=404, detail="ROI polygon not set for this product_type")
    return RoiPolygonResponse(
        product_type=product_type,
        points=[RoiPoint(x=x, y=y) for x, y in points],
    )


@router.post("/upload-ref-shm")
async def upload_reference_shm(payload: ShmFrameRequest) -> dict:
    try:
        with _open_bgr_shm_frame(
            shm_name=payload.shm_name,
            width=payload.width,
            height=payload.height,
            stride=payload.stride,
            shm_offset=payload.shm_offset,
        ) as bgr_frame:
            try:
                inspection_service.set_reference_frame(product_type=payload.product_type, frame=bgr_frame)
            finally:
                del bgr_frame
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {"message": "Reference uploaded from shared memory", "product_type": payload.product_type}


@router.post("/inspect-shm", response_model=InspectResponse)
async def inspect_shm(payload: ShmFrameRequest) -> InspectResponse:
    try:
        with _open_bgr_shm_frame(
            shm_name=payload.shm_name,
            width=payload.width,
            height=payload.height,
            stride=payload.stride,
            shm_offset=payload.shm_offset,
        ) as bgr_frame:
            try:
                result = inspection_service.inspect_frame(
                    product_type=payload.product_type,
                    frame=bgr_frame,
                    threshold=payload.threshold,
                    include_visuals=False,
                    detector_id=payload.detector_id,
                )
            finally:
                del bgr_frame
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return _to_inspect_response(result)


@router.post("/inspect-shm-visuals", response_model=ShmVisualsResponse)
async def inspect_shm_visuals(payload: ShmVisualsRequest) -> ShmVisualsResponse:
    try:
        with _open_bgr_shm_frame(
            shm_name=payload.shm_name,
            width=payload.width,
            height=payload.height,
            stride=payload.stride,
            shm_offset=payload.shm_offset,
        ) as bgr_frame:
            try:
                result = inspection_service.inspect_frame(
                    product_type=payload.product_type,
                    frame=bgr_frame,
                    threshold=payload.threshold,
                    include_visuals=True,
                    detector_id=payload.detector_id,
                )
            finally:
                del bgr_frame
        visual_outputs = _write_requested_visual_outputs(payload, result)
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return _to_visuals_response(result, visual_outputs)
