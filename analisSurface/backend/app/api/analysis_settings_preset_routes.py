"""Упрощённые эндпоинты analysis-settings: simple, detailed/strengths."""

from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import (
    to_detailed_sensitivity_response,
    to_simple_settings_response,
    to_strength_knobs_response,
)
from app.api.schemas import (
    DetailedSensitivityKnobs,
    DetailedSensitivityResponse,
    SimpleSettingsKnobs,
    SimpleSettingsResponse,
    StrengthKnobsResponse,
)
from app.services.analysis_settings_presets import expand_merged, normalize_strengths


router = APIRouter()


@router.get(
    "/analysis-settings/{analysis_profile}/simple",
    response_model=SimpleSettingsResponse,
)
async def get_simple_analysis_settings(analysis_profile: str) -> SimpleSettingsResponse:
    """GET — последние simple-knobs (если есть) + эффективные settings."""
    overrides = inspection_service.get_analysis_settings_overrides(analysis_profile)
    knobs = inspection_service.get_simple_knobs(analysis_profile)
    return to_simple_settings_response(analysis_profile, overrides, knobs)


@router.put(
    "/analysis-settings/{analysis_profile}/simple",
    response_model=SimpleSettingsResponse,
)
async def put_simple_analysis_settings(
    analysis_profile: str,
    payload: SimpleSettingsKnobs,
) -> SimpleSettingsResponse:
    """PUT — развернуть threshold+sensitivity и записать через InspectionService."""
    try:
        strengths = normalize_strengths(inspection_service.get_detailed_knobs(analysis_profile))
        expanded = expand_merged(payload.threshold, payload.sensitivity, **strengths)
        knobs = payload.model_dump()
        overrides = inspection_service.apply_simple_settings(analysis_profile, expanded, knobs)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_simple_settings_response(analysis_profile, overrides, knobs)


@router.get(
    "/analysis-settings/{analysis_profile}/strengths",
    response_model=StrengthKnobsResponse,
)
async def get_strength_knobs(analysis_profile: str) -> StrengthKnobsResponse:
    """GET — силы групп (0–100). Если не сохранены — defaults 50, saved=false."""
    return to_strength_knobs_response(analysis_profile)


@router.put(
    "/analysis-settings/{analysis_profile}/strengths",
    response_model=StrengthKnobsResponse,
)
async def put_strength_knobs(
    analysis_profile: str,
    payload: DetailedSensitivityKnobs,
) -> StrengthKnobsResponse:
    """PUT — сохранить силы групп; пересчитать overrides с текущей simple-чувствительностью."""
    try:
        knobs = payload.model_dump()
        inspection_service.apply_detailed_settings(analysis_profile, knobs)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_strength_knobs_response(analysis_profile)


@router.get(
    "/analysis-settings/{analysis_profile}/detailed",
    response_model=DetailedSensitivityResponse,
)
async def get_detailed_analysis_settings(analysis_profile: str) -> DetailedSensitivityResponse:
    """GET — сохранённые силы (если есть) + эффективные settings."""
    overrides = inspection_service.get_analysis_settings_overrides(analysis_profile)
    knobs = inspection_service.get_detailed_knobs(analysis_profile)
    return to_detailed_sensitivity_response(analysis_profile, overrides, knobs)


@router.put(
    "/analysis-settings/{analysis_profile}/detailed",
    response_model=DetailedSensitivityResponse,
)
async def put_detailed_analysis_settings(
    analysis_profile: str,
    payload: DetailedSensitivityKnobs,
) -> DetailedSensitivityResponse:
    """PUT — alias для /strengths: сохранить силы групп."""
    try:
        knobs = payload.model_dump()
        overrides = inspection_service.apply_detailed_settings(analysis_profile, knobs)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_detailed_sensitivity_response(analysis_profile, overrides, knobs)
