"""Инспекция через shared memory — основной путь для оркестратора.

Тело JSON: ShmFrameRequest (кадр BGR в SHM) или ShmVisualsRequest (+ пути output SHM).
Тяжёлый inspect выполняется в thread pool (inspect_executor).
"""

import asyncio
import logging
from functools import partial
from pathlib import Path

import cv2
import numpy as np
from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspect_executor, inspection_service
from app.api.mappers import to_inspect_response, to_visuals_response
from app.api.schemas import (
    DetectorHealthResponse,
    InspectResponse,
    ShmFrameRequest,
    ShmVisualsRequest,
    ShmVisualsResponse,
)
from app.runtime import get_application_id
from app.services.analysis_settings_presets import expand_pro, expand_simple
from app.services.shm_io import ShmImageOutputInfo, open_bgr_shm_frame, write_u8_image_to_shm


router = APIRouter()
logger = logging.getLogger(__name__)


def cleanup_requested_visual_outputs(payload: ShmVisualsRequest) -> None:
    for raw_path in (
        payload.aligned_image_u8_output_path,
        payload.diff_map_u8_output_path,
        payload.heatmap_u8_output_path,
        payload.segmentation_mask_u8_output_path,
    ):
        if not raw_path:
            continue
        try:
            Path(raw_path).unlink(missing_ok=True)
        except (OSError, ValueError):
            logger.debug("failed to clean partial visual output path=%s", raw_path)


def write_requested_visual_outputs(payload: ShmVisualsRequest, result) -> dict[str, ShmImageOutputInfo]:
    """Записать запрошенные визуалы в SHM; heatmap — gray_u8 (1 канал) для UI."""
    heatmap_u8 = result.heatmap_u8
    max_width = payload.heatmap_max_width or 0
    if heatmap_u8 is not None and max_width > 0 and heatmap_u8.shape[1] > max_width:
        target_height = max(1, round(heatmap_u8.shape[0] * max_width / heatmap_u8.shape[1]))
        heatmap_u8 = cv2.resize(heatmap_u8, (max_width, target_height), interpolation=cv2.INTER_AREA)
    requested = {
        "aligned_image": (payload.aligned_image_u8_output_path, result.aligned_image),
        "diff_map": (payload.diff_map_u8_output_path, result.diff_map),
        "heatmap": (payload.heatmap_u8_output_path, heatmap_u8),
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
    """GET /detector/health — liveness для оркестратора (service=analisSurface)."""
    return DetectorHealthResponse(
        status="ok",
        service="analisSurface",
        detector_id=get_application_id(),
    )


@router.post("/upload-ref-shm")
async def upload_reference_shm(payload: ShmFrameRequest) -> dict:
    """POST /upload-ref-shm — эталон из BGR-кадра в SHM (ShmFrameRequest).

    Поля shm_name, width, height, stride, shm_offset — описание буфера камеры.
    """
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


def _copy_shm_bgr_frame(payload: ShmFrameRequest) -> np.ndarray:
    with open_bgr_shm_frame(
        shm_name=payload.shm_name,
        width=payload.width,
        height=payload.height,
        stride=payload.stride,
        shm_offset=payload.shm_offset,
    ) as bgr_frame:
        return np.copy(bgr_frame)


def _inspect_shm_sync(
    payload: ShmFrameRequest,
    *,
    include_visuals: bool,
    include_heatmap_u8: bool,
):
    frame = _copy_shm_bgr_frame(payload)
    temporary_overrides = None
    if payload.analysis_test_settings:
        mode = str(payload.analysis_test_settings.get("mode", "")).strip().lower()
        knobs = payload.analysis_test_settings.get("knobs") or {}
        if mode == "simple":
            temporary_overrides = expand_simple(knobs["threshold"], knobs["sensitivity"])
        elif mode == "pro":
            temporary_overrides = expand_pro(
                knobs["threshold"], knobs["noise_tolerance"], knobs["scratch_sensitivity"],
                knobs["edge_suppression"], knobs["text_handling"], knobs["preprocess_strength"],
            )
    return inspection_service.inspect_frame(
        product_type=payload.product_type,
        frame=frame,
        threshold=payload.threshold,
        include_visuals=include_visuals,
        include_heatmap_u8=include_heatmap_u8,
        detector_id=payload.detector_id,
        alignment_h_ref_to_cur=payload.alignment_h_ref_to_cur,
        analysis_profile=payload.analysis_profile,
        temporary_analysis_overrides=temporary_overrides,
    )


async def _inspect_shm_parallel(
    payload: ShmFrameRequest,
    *,
    include_visuals: bool,
    include_heatmap_u8: bool,
):
    loop = asyncio.get_running_loop()
    job = partial(
        _inspect_shm_sync,
        payload,
        include_visuals=include_visuals,
        include_heatmap_u8=include_heatmap_u8,
    )
    return await loop.run_in_executor(inspect_executor, job)


@router.post("/inspect-shm", response_model=InspectResponse)
async def inspect_shm(payload: ShmFrameRequest) -> InspectResponse:
    """POST /inspect-shm — только вердикт и score, без визуалов (быстрый путь конвейера).

    Выход: InspectResponse — status, anomaly_score, threshold, sub_zone_scores, ...
    """
    try:
        result = await _inspect_shm_parallel(payload, include_visuals=False, include_heatmap_u8=False)
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return to_inspect_response(result)


@router.post("/inspect-shm-visuals", response_model=ShmVisualsResponse)
async def inspect_shm_visuals(payload: ShmVisualsRequest) -> ShmVisualsResponse:
    """POST /inspect-shm-visuals — инспекция + запись визуалов в output SHM.

    Доп. поля: *_u8_output_path (куда писать), heatmap_max_width (уменьшить heatmap).
    Выход: InspectResponse + ShmImageOutput (path, width, height, stride, channels) на каждый визуал.
    Ошибка записи визуалов не отменяет вердикт инспекции.
    """
    try:
        result = await _inspect_shm_parallel(
            payload,
            include_visuals=any(
                (
                    payload.aligned_image_u8_output_path,
                    payload.diff_map_u8_output_path,
                    payload.segmentation_mask_u8_output_path,
                )
            ),
            include_heatmap_u8=payload.heatmap_u8_output_path is not None,
        )
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    try:
        visual_outputs = write_requested_visual_outputs(payload, result)
    except Exception as exc:
        # UI artifacts are best-effort and must not invalidate a completed inspection.
        logger.warning("inspection visual output export failed: %s", exc)
        cleanup_requested_visual_outputs(payload)
        visual_outputs = {}

    return to_visuals_response(result, visual_outputs)


@router.post("/clear-inspection-context")
async def clear_inspection_context() -> dict:
    """POST /clear-inspection-context — сброс эталонов, ROI и FP-зон (полная остановка инспекции)."""
    cleared = inspection_service.clear_inspection_context()
    return {"ok": True, "cleared": cleared}
