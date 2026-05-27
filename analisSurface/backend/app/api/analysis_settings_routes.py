from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_analysis_settings_response
from app.api.schemas import AnalysisSettingsResponse, AnalysisSettingsUpdateRequest


router = APIRouter()


@router.get("/analysis-settings/defaults", response_model=AnalysisSettingsResponse)
async def get_default_analysis_settings() -> AnalysisSettingsResponse:
    return to_analysis_settings_response(product_type="_defaults", overrides={})


@router.get("/analysis-settings/{product_type}", response_model=AnalysisSettingsResponse)
async def get_analysis_settings(product_type: str) -> AnalysisSettingsResponse:
    overrides = inspection_service.get_analysis_settings_overrides(product_type)
    return to_analysis_settings_response(product_type=product_type, overrides=overrides)


@router.put("/analysis-settings/{product_type}", response_model=AnalysisSettingsResponse)
async def update_analysis_settings(
    product_type: str,
    payload: AnalysisSettingsUpdateRequest,
) -> AnalysisSettingsResponse:
    partial = payload.model_dump(exclude_none=True)
    if not partial:
        raise HTTPException(status_code=400, detail="At least one setting must be provided")
    try:
        overrides = inspection_service.update_analysis_settings(product_type, partial)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_analysis_settings_response(product_type=product_type, overrides=overrides)


@router.delete("/analysis-settings/{product_type}", response_model=AnalysisSettingsResponse)
async def reset_analysis_settings(product_type: str) -> AnalysisSettingsResponse:
    overrides = inspection_service.reset_analysis_settings(product_type)
    return to_analysis_settings_response(product_type=product_type, overrides=overrides)
