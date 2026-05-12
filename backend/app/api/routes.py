import base64
import time
from pathlib import Path
from typing import Optional

import cv2
import httpx
import numpy as np
from fastapi import APIRouter, Body, File, Form, HTTPException, Query, UploadFile
from pydantic import BaseModel, Field

from app.runtime import get_application_id
from app.services.inspection_service import InspectionService


router = APIRouter()
inspection_service = InspectionService()


class InspectResponse(BaseModel):
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    detector_id: str
    aligned_image_b64: str
    diff_map_b64: str
    heatmap_b64: str
    segmentation_mask_b64: str
    raw_anomaly_score: float
    rechecked_zones_count: int
    recheck_adjustment: float
    rechecked_zone_ids: list[str] = Field(default_factory=list)


class TestLogEntry(BaseModel):
    filename: str
    score: float
    status: str
    duration_ms: float


class TestSummary(BaseModel):
    total: int
    good_count: int
    defect_count: int
    defect_percent: float


class TestRunResponse(BaseModel):
    product_type: str
    threshold: float
    dataset: str
    logs: list[TestLogEntry] = Field(default_factory=list)
    summary: TestSummary


class InspectFromCameraResponse(InspectResponse):
    original_image_b64: str
    camera_source: str
    camera_duration_ms: float


class DetectorHealthResponse(BaseModel):
    status: str
    service: str
    detector_id: str


class RoiConfig(BaseModel):
    x: float
    y: float
    w: float
    h: float


class RoiResponse(BaseModel):
    product_type: str
    roi: RoiConfig


class RoiPoint(BaseModel):
    x: float
    y: float


class RoiPolygonRequest(BaseModel):
    product_type: str
    points: list[RoiPoint]


class RoiPolygonResponse(BaseModel):
    product_type: str
    points: list[RoiPoint]


class UploadRefFromCameraResponse(BaseModel):
    message: str
    product_type: str
    camera_source: str
    camera_duration_ms: float
    reference_b64: str


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


def _decode_camera_payload(camera_response: httpx.Response, camera_server_url: str) -> tuple[bytes, str, float]:
    content_type = camera_response.headers.get("content-type", "").lower()

    if content_type.startswith("image/"):
        return camera_response.content, camera_server_url, 0.0

    payload = camera_response.json()
    original_b64 = payload["image_b64"]
    camera_source = payload.get("source", camera_server_url)
    camera_duration_ms = float(payload.get("duration_ms", 0.0))
    image_bytes = base64.b64decode(original_b64)
    return image_bytes, camera_source, camera_duration_ms


def _to_inspect_response(result) -> InspectResponse:
    return InspectResponse(
        product_type=result.product_type,
        status=result.status,
        anomaly_score=result.anomaly_score,
        threshold=result.threshold,
        detector_id=result.detector_id,
        aligned_image_b64=result.aligned_image_b64,
        diff_map_b64=result.diff_map_b64,
        heatmap_b64=result.heatmap_b64,
        segmentation_mask_b64=result.segmentation_mask_b64,
        raw_anomaly_score=result.raw_anomaly_score,
        rechecked_zones_count=result.rechecked_zones_count,
        recheck_adjustment=result.recheck_adjustment,
        rechecked_zone_ids=result.rechecked_zone_ids or [],
    )


def _decode_bgr_frame(frame_bytes: bytes, width: int, height: int, channels: int = 3) -> np.ndarray:
    if width <= 0 or height <= 0:
        raise ValueError("width and height must be positive")
    if channels != 3:
        raise ValueError("Only 3-channel BGR uint8 frames are supported")

    expected_size = width * height * channels
    actual_size = len(frame_bytes)
    if actual_size != expected_size:
        raise ValueError(
            f"BGR uint8 payload size mismatch: expected {expected_size} bytes "
            f"for {width}x{height}x{channels}, got {actual_size}"
        )

    return np.frombuffer(frame_bytes, dtype=np.uint8).reshape((height, width, channels))


@router.get("/detector/health", response_model=DetectorHealthResponse)
async def detector_health() -> DetectorHealthResponse:
    return DetectorHealthResponse(
        status="ok",
        service="python-detectors",
        detector_id=get_application_id(),
    )


@router.post("/roi", response_model=RoiResponse)
async def set_roi(product_type: str = Form(...), x: float = Form(...), y: float = Form(...), w: float = Form(...), h: float = Form(...)) -> RoiResponse:
    if inspection_service.get_reference(product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")
    try:
        inspection_service.set_roi(product_type=product_type, x=x, y=y, w=w, h=h)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return RoiResponse(product_type=product_type, roi=RoiConfig(x=x, y=y, w=w, h=h))


@router.get("/roi/{product_type}", response_model=RoiResponse)
async def get_roi(product_type: str) -> RoiResponse:
    roi = inspection_service.get_roi(product_type)
    if roi is None:
        raise HTTPException(status_code=404, detail="ROI not set for this product_type")
    x, y, w, h = roi
    return RoiResponse(product_type=product_type, roi=RoiConfig(x=x, y=y, w=w, h=h))


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


@router.post("/upload-ref-from-camera", response_model=UploadRefFromCameraResponse)
async def upload_reference_from_camera(
    product_type: str = Form(...),
    camera_server_url: str = Form("http://localhost:8080"),
) -> UploadRefFromCameraResponse:
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            camera_response = await client.get(camera_server_url)
        camera_response.raise_for_status()
        image_bytes, camera_source, camera_duration_ms = _decode_camera_payload(camera_response, camera_server_url)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Camera server error: {exc}") from exc

    try:
        inspection_service.set_reference(product_type=product_type, image_bytes=image_bytes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return UploadRefFromCameraResponse(
        message="Reference uploaded from camera",
        product_type=product_type,
        camera_source=camera_source,
        camera_duration_ms=camera_duration_ms,
        reference_b64=base64.b64encode(image_bytes).decode(),
    )


@router.post("/upload-ref")
async def upload_reference(
    product_type: str = Form(...),
    file: UploadFile = File(...),
) -> dict:
    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Empty file")

    try:
        inspection_service.set_reference(product_type=product_type, image_bytes=content)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {"message": "Reference uploaded", "product_type": product_type}


@router.post("/upload-ref-bgr")
async def upload_reference_bgr(
    product_type: str = Query(...),
    width: int = Query(...),
    height: int = Query(...),
    frame: bytes = Body(..., media_type="application/octet-stream"),
) -> dict:
    try:
        bgr_frame = _decode_bgr_frame(frame, width=width, height=height)
        inspection_service.set_reference_frame(product_type=product_type, frame=bgr_frame)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {"message": "Reference uploaded from BGR uint8 frame", "product_type": product_type}


@router.get("/reference/{product_type}")
async def get_reference(product_type: str) -> dict:
    reference = inspection_service.get_reference(product_type)
    if reference is None:
        raise HTTPException(status_code=404, detail="Reference not found")

    success, encoded = cv2.imencode(".png", reference)
    if not success:
        raise HTTPException(status_code=500, detail="Could not encode reference image")
    return {"product_type": product_type, "reference_b64": base64.b64encode(encoded).decode()}


@router.post("/inspect", response_model=InspectResponse)
async def inspect(
    product_type: str = Form(...),
    threshold: Optional[float] = Form(None),
    include_visuals: bool = Form(True),
    detector_id: Optional[str] = Form(None),
    file: UploadFile = File(...),
) -> InspectResponse:
    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Empty file")

    try:
        result = inspection_service.inspect(
            product_type=product_type,
            image_bytes=content,
            threshold=threshold,
            include_visuals=include_visuals,
            detector_id=detector_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return _to_inspect_response(result)


@router.post("/inspect-bgr", response_model=InspectResponse)
async def inspect_bgr(
    product_type: str = Query(...),
    width: int = Query(...),
    height: int = Query(...),
    threshold: Optional[float] = Query(None),
    include_visuals: bool = Query(True),
    detector_id: Optional[str] = Query(None),
    frame: bytes = Body(..., media_type="application/octet-stream"),
) -> InspectResponse:
    try:
        bgr_frame = _decode_bgr_frame(frame, width=width, height=height)
        result = inspection_service.inspect_frame(
            product_type=product_type,
            frame=bgr_frame,
            threshold=threshold,
            include_visuals=include_visuals,
            detector_id=detector_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return _to_inspect_response(result)


@router.post("/test-run", response_model=TestRunResponse)
async def test_run(
    product_type: str = Form(...),
    threshold: Optional[float] = Form(None),
    dataset: str = Form("normal"),
) -> TestRunResponse:
    if inspection_service.get_reference(product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")

    allowed_datasets = {"normal", "brack"}
    if dataset not in allowed_datasets:
        raise HTTPException(status_code=400, detail="dataset must be either 'normal' or 'brack'")

    target_dir = Path(__file__).resolve().parent.parent / dataset
    if not target_dir.exists() or not target_dir.is_dir():
        raise HTTPException(status_code=404, detail=f"Test folder backend/app/{dataset} not found")

    image_files = sorted(
        path for path in target_dir.iterdir() if path.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp"}
    )
    if not image_files:
        raise HTTPException(status_code=404, detail=f"No image files found in backend/app/{dataset}")

    active_threshold = threshold if threshold is not None else 0.25
    logs: list[TestLogEntry] = []

    for image_path in image_files:
        started = time.perf_counter()
        try:
            image_bytes = image_path.read_bytes()
            result = inspection_service.inspect(
                product_type=product_type,
                image_bytes=image_bytes,
                threshold=active_threshold,
                include_visuals=False,
            )
            duration_ms = (time.perf_counter() - started) * 1000.0
            logs.append(
                TestLogEntry(
                    filename=image_path.name,
                    score=result.anomaly_score,
                    status=result.status,
                    duration_ms=duration_ms,
                )
            )
        except Exception:
            duration_ms = (time.perf_counter() - started) * 1000.0
            logs.append(
                TestLogEntry(
                    filename=image_path.name,
                    score=1.0,
                    status="БРАК",
                    duration_ms=duration_ms,
                )
            )

    defect_count = sum(1 for entry in logs if entry.status == "БРАК")
    total = len(logs)
    good_count = total - defect_count
    defect_percent = (defect_count / total) * 100 if total else 0.0

    return TestRunResponse(
        product_type=product_type,
        threshold=active_threshold,
        dataset=dataset,
        logs=logs,
        summary=TestSummary(
            total=total,
            good_count=good_count,
            defect_count=defect_count,
            defect_percent=round(defect_percent, 2),
        ),
    )


@router.post("/inspect-from-camera", response_model=InspectFromCameraResponse)
async def inspect_from_camera(
    product_type: str = Form(...),
    threshold: Optional[float] = Form(None),
    include_visuals: bool = Form(True),
    detector_id: Optional[str] = Form(None),
    camera_server_url: str = Form("http://localhost:8080"),
) -> InspectFromCameraResponse:
    if inspection_service.get_reference(product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            camera_response = await client.get(camera_server_url)
        camera_response.raise_for_status()
        image_bytes, camera_source, camera_duration_ms = _decode_camera_payload(camera_response, camera_server_url)
        original_b64 = base64.b64encode(image_bytes).decode()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Camera server error: {exc}") from exc

    try:
        result = inspection_service.inspect(
            product_type=product_type,
            image_bytes=image_bytes,
            threshold=threshold,
            include_visuals=include_visuals,
            detector_id=detector_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    base_response = _to_inspect_response(result)
    return InspectFromCameraResponse(
        **base_response.dict(),
        original_image_b64=original_b64,
        camera_source=camera_source,
        camera_duration_ms=camera_duration_ms,
    )
