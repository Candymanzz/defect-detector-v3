from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_inspect_response, to_visuals_response
from app.api.schemas import (
    DetectorHealthResponse,
    InspectResponse,
    ShmFrameRequest,
    ShmVisualsRequest,
    ShmVisualsResponse,
)
from app.runtime import get_application_id
from app.services.shm_io import ShmImageOutputInfo, open_bgr_shm_frame, write_u8_image_to_shm


router = APIRouter()


def write_requested_visual_outputs(payload: ShmVisualsRequest, result) -> dict[str, ShmImageOutputInfo]:
    requested = {
        "aligned_image": (payload.aligned_image_u8_output_path, result.aligned_image),
        "diff_map": (payload.diff_map_u8_output_path, result.diff_map),
        "heatmap": (payload.heatmap_u8_output_path, result.heatmap),
        "segmentation_mask": (payload.segmentation_mask_u8_output_path, result.segmentation_mask),
    }
    outputs: dict[str, ShmImageOutputInfo] = {}
    for name, (output_path, image) in requested.items():
        if output_path is None:
            continue
        if image is None:
            raise ValueError(f"{name} output was requested but visuals are disabled")
        outputs[name] = write_u8_image_to_shm(output_path, image)
    return outputs


@router.get("/detector/health", response_model=DetectorHealthResponse)
async def detector_health() -> DetectorHealthResponse:
    return DetectorHealthResponse(
        status="ok",
        service="python-detectors",
        detector_id=get_application_id(),
    )


@router.post("/upload-ref-shm")
async def upload_reference_shm(payload: ShmFrameRequest) -> dict:
    try:
        with open_bgr_shm_frame(
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
        with open_bgr_shm_frame(
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

    return to_inspect_response(result)


@router.post("/inspect-shm-visuals", response_model=ShmVisualsResponse)
async def inspect_shm_visuals(payload: ShmVisualsRequest) -> ShmVisualsResponse:
    try:
        with open_bgr_shm_frame(
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
        visual_outputs = write_requested_visual_outputs(payload, result)
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return to_visuals_response(result, visual_outputs)
