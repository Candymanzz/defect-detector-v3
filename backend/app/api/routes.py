import base64
import time
from pathlib import Path
from typing import Optional

import cv2
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.services.inspection_service import InspectionService


router = APIRouter()
inspection_service = InspectionService()


class InspectResponse(BaseModel):
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    aligned_image_b64: str
    diff_map_b64: str
    heatmap_b64: str
    segmentation_mask_b64: str


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
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return InspectResponse(
        product_type=result.product_type,
        status=result.status,
        anomaly_score=result.anomaly_score,
        threshold=result.threshold,
        aligned_image_b64=result.aligned_image_b64,
        diff_map_b64=result.diff_map_b64,
        heatmap_b64=result.heatmap_b64,
        segmentation_mask_b64=result.segmentation_mask_b64,
    )


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
