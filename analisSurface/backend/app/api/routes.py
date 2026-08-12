"""Сборка всех API-роутеров в один router (подключается в main.py)."""

from fastapi import APIRouter

from app.api.analysis_settings_preset_routes import router as analysis_settings_preset_router
from app.api.analysis_settings_routes import router as analysis_settings_router
from app.api.file_routes import router as file_router
from app.api.fp_zone_routes import router as fp_zone_router
from app.api.inspection_routes import router as inspection_router
from app.api.learned_normal_routes import router as learned_normal_router
from app.api.local_inspection_test_routes import router as local_inspection_test_router
from app.api.roi_routes import router as roi_router
from app.api.roi_sub_zone_routes import router as roi_sub_zone_router


router = APIRouter()
router.include_router(inspection_router)
router.include_router(analysis_settings_preset_router)
router.include_router(analysis_settings_router)
router.include_router(file_router)
router.include_router(roi_router)
router.include_router(roi_sub_zone_router)
router.include_router(fp_zone_router)
router.include_router(learned_normal_router)
router.include_router(local_inspection_test_router)
