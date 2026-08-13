import numpy as np
import pytest
from pathlib import Path

from app.services.inspection_service import InspectionService


@pytest.fixture
def inspection_service(tmp_path: Path) -> InspectionService:
    service = InspectionService(
        learned_normals_dir=tmp_path / "accepted_normals",
        reviews_dir=tmp_path / "learning_reviews",
        review_limit=10,
        session_wipe=True,
    )
    service._anomaly_engine = None
    return service


@pytest.fixture
def gray_frame() -> np.ndarray:
    height, width = 64, 80
    gradient = np.linspace(30, 200, width, dtype=np.uint8)
    frame = np.tile(gradient, (height, 1))
    return np.stack([frame, frame, frame], axis=-1)
