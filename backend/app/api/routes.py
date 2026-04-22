import base64
from typing import Optional

import cv2
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

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
