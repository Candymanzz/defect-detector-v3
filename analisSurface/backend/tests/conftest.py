import numpy as np
import pytest
from pathlib import Path

from app.services.inspection_service import InspectionService
from app.services.learned_normals import AcceptedNormalMemory, InspectionReviewStore


@pytest.fixture(scope="session", autouse=True)
def isolate_default_service_storage(tmp_path_factory):
    """Route tests must never clear or publish the operator's persisted data."""
    from app.api.dependencies import inspection_service as default_service

    storage = tmp_path_factory.mktemp("default-inspection-service")
    with pytest.MonkeyPatch.context() as patch:
        for attribute, value in {
            "_fp_zones_file": storage / "fp_zones.json",
            "_fp_crops_dir": storage / "fp_zone_crops",
            "_fp_zones_generation": None,
            "fp_zones": {},
            "_roi_sub_zones_file": storage / "roi_sub_zones.json",
            "roi_sub_zones": {},
            "_analysis_settings_file": storage / "analysis_settings.json",
            "_analysis_settings_overrides": {},
            "_analysis_settings_simple_knobs": {},
            "_analysis_settings_detailed_knobs": {},
            "_analysis_settings_mtime_ns": -1,
            "_analysis_settings_signature": None,
            "_accepted_normals": AcceptedNormalMemory(storage / "accepted_normals", session_wipe=False),
            "_learning_reviews": InspectionReviewStore(storage_dir=storage / "learning_reviews", session_wipe=False),
        }.items():
            patch.setattr(default_service, attribute, value)
        patch.setattr(default_service._deferred_learning_reviews, "_store", default_service._learning_reviews)
        yield
        default_service._deferred_learning_reviews._queue.join()


@pytest.fixture
def inspection_service(tmp_path: Path) -> InspectionService:
    service = InspectionService(
        learned_normals_dir=tmp_path / "accepted_normals",
        reviews_dir=tmp_path / "learning_reviews",
        review_limit=10,
        session_wipe=True,
    )
    service._anomaly_engine = None
    service._fp_zones_file = tmp_path / "fp_zones.json"
    service._fp_crops_dir = tmp_path / "fp_zone_crops"
    service.fp_zones = {}
    service._roi_sub_zones_file = tmp_path / "roi_sub_zones.json"
    service.roi_sub_zones = {}
    service._analysis_settings_file = tmp_path / "analysis_settings.json"
    service._analysis_settings_overrides = {}
    service._analysis_settings_simple_knobs = {}
    service._analysis_settings_detailed_knobs = {}
    service._stamp_analysis_settings_mtime()
    return service


@pytest.fixture
def isolated_analysis_settings_api(inspection_service, monkeypatch):
    from app.api import analysis_settings_routes, analysis_settings_preset_routes, mappers

    for module in (analysis_settings_routes, analysis_settings_preset_routes, mappers):
        monkeypatch.setattr(module, "inspection_service", inspection_service)
    return inspection_service


@pytest.fixture
def gray_frame() -> np.ndarray:
    height, width = 64, 80
    gradient = np.linspace(30, 200, width, dtype=np.uint8)
    frame = np.tile(gradient, (height, 1))
    return np.stack([frame, frame, frame], axis=-1)
