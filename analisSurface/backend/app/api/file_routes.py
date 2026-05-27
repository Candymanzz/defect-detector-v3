from typing import Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from app.api.dependencies import inspection_service
from app.api.mappers import to_inspect_with_visuals_response
from app.api.schemas import InspectWithVisualsResponse, ReferenceResponse, UploadRefResponse


router = APIRouter()


def _is_image_upload(file: UploadFile) -> bool:
    if file.content_type and file.content_type.startswith("image/"):
        return True
    filename = (file.filename or "").lower()
    return filename.endswith((".png", ".jpg", ".jpeg", ".bmp", ".webp", ".tif", ".tiff"))


async def _read_uploaded_image(file: UploadFile) -> bytes:
    if not _is_image_upload(file):
        raise HTTPException(status_code=400, detail="Uploaded file must be an image")
    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Uploaded file is empty")
    return content


@router.post("/upload-ref", response_model=UploadRefResponse)
async def upload_reference(
    product_type: str = Form(...),
    file: UploadFile = File(...),
) -> UploadRefResponse:
    content = await _read_uploaded_image(file)
    try:
        inspection_service.set_reference(product_type=product_type, image_bytes=content)
        reference = inspection_service.get_reference(product_type)
        if reference is None:
            raise ValueError("Failed to store reference image")
        reference_b64 = inspection_service.encode_image_b64(reference)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return UploadRefResponse(
        message="Reference uploaded",
        product_type=product_type,
        reference_b64=reference_b64,
    )


@router.get("/reference/{product_type}", response_model=ReferenceResponse)
async def get_reference(product_type: str) -> ReferenceResponse:
    reference = inspection_service.get_reference(product_type)
    if reference is None:
        raise HTTPException(status_code=404, detail="Reference is not set for this product_type")
    return ReferenceResponse(
        product_type=product_type,
        reference_b64=inspection_service.encode_image_b64(reference),
    )


@router.post("/inspect", response_model=InspectWithVisualsResponse)
async def inspect_image(
    product_type: str = Form(...),
    file: UploadFile = File(...),
    threshold: Optional[float] = Form(None),
) -> InspectWithVisualsResponse:
    content = await _read_uploaded_image(file)
    try:
        result = inspection_service.inspect(
            product_type=product_type,
            image_bytes=content,
            threshold=threshold,
            include_visuals=True,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return to_inspect_with_visuals_response(result)
