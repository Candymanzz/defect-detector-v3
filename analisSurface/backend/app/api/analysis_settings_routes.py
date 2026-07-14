"""Параметры алгоритма на product_type (analysis_profile). Подробности — docs/ANALYSIS_SETTINGS.md."""

from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_analysis_settings_response
from app.api.schemas import AnalysisSettingsResponse, AnalysisSettingsUpdateRequest


router = APIRouter()


@router.get("/analysis-settings/defaults", response_model=AnalysisSettingsResponse)
async def get_default_analysis_settings() -> AnalysisSettingsResponse:
    """GET /analysis-settings/defaults — заводские значения всех параметров."""
    return to_analysis_settings_response(analysis_profile="_defaults", overrides={})


@router.get("/analysis-settings/{analysis_profile}", response_model=AnalysisSettingsResponse)
async def get_analysis_settings(analysis_profile: str) -> AnalysisSettingsResponse:
    """GET /analysis-settings/{product_type} — эффективные настройки + список overrides."""
    overrides = inspection_service.get_analysis_settings_overrides(analysis_profile)
    return to_analysis_settings_response(analysis_profile=analysis_profile, overrides=overrides)


@router.put("/analysis-settings/{analysis_profile}", response_model=AnalysisSettingsResponse)
async def update_analysis_settings(
    analysis_profile: str,
    payload: AnalysisSettingsUpdateRequest,
) -> AnalysisSettingsResponse:
    """PUT /analysis-settings/{product_type} — частичное обновление (любое подмножество полей JSON)."""
    partial = payload.model_dump(exclude_none=True)
    if not partial:
        raise HTTPException(status_code=400, detail="At least one setting must be provided")
    try:
        overrides = inspection_service.update_analysis_settings(analysis_profile, partial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_analysis_settings_response(analysis_profile=analysis_profile, overrides=overrides)


@router.delete("/analysis-settings/{analysis_profile}", response_model=AnalysisSettingsResponse)
async def reset_analysis_settings(analysis_profile: str) -> AnalysisSettingsResponse:
    """DELETE /analysis-settings/{product_type} — сброс overrides к defaults."""
    overrides = inspection_service.reset_analysis_settings(analysis_profile)
    return to_analysis_settings_response(analysis_profile=analysis_profile, overrides=overrides)
