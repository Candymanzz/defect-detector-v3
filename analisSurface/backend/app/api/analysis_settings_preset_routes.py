"""Упрощённые эндпоинты analysis-settings: simple (2 ручки) и pro (6 ручек)."""

import logging

from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_pro_settings_response, to_simple_settings_response
from app.api.schemas import (
    ProSettingsKnobs,
    ProSettingsResponse,
    SimpleSettingsKnobs,
    SimpleSettingsResponse,
)
from app.services.analysis_settings_presets import expand_pro, expand_simple


router = APIRouter()
logger = logging.getLogger(__name__)


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
        expanded = expand_simple(payload.threshold, payload.sensitivity)
        knobs = payload.model_dump()
        overrides = inspection_service.apply_simple_settings(analysis_profile, expanded, knobs)
        logger.info(
            "ui analysis-test python settings saved profile=%s mode=simple knobs=%s effective_overrides=%s",
            analysis_profile,
            knobs,
            overrides,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_simple_settings_response(analysis_profile, overrides, knobs)


@router.get(
    "/analysis-settings/{analysis_profile}/pro",
    response_model=ProSettingsResponse,
)
async def get_pro_analysis_settings(analysis_profile: str) -> ProSettingsResponse:
    """GET — последние pro-knobs (если есть) + эффективные settings."""
    overrides = inspection_service.get_analysis_settings_overrides(analysis_profile)
    knobs = inspection_service.get_pro_knobs(analysis_profile)
    return to_pro_settings_response(analysis_profile, overrides, knobs)


@router.put(
    "/analysis-settings/{analysis_profile}/pro",
    response_model=ProSettingsResponse,
)
async def put_pro_analysis_settings(
    analysis_profile: str,
    payload: ProSettingsKnobs,
) -> ProSettingsResponse:
    """PUT — развернуть pro-ручки и записать через InspectionService."""
    try:
        expanded = expand_pro(
            payload.threshold,
            payload.noise_tolerance,
            payload.scratch_sensitivity,
            payload.edge_suppression,
            payload.text_handling,
            payload.preprocess_strength,
        )
        knobs = payload.model_dump()
        overrides = inspection_service.apply_pro_settings(analysis_profile, expanded, knobs)
        logger.info(
            "ui analysis-test python settings saved profile=%s mode=pro knobs=%s effective_overrides=%s",
            analysis_profile,
            knobs,
            overrides,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_pro_settings_response(analysis_profile, overrides, knobs)
