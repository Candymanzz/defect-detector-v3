import numpy as np
import pytest

from app.services.inspection_geometry import (
    polygon_area,
    validate_polygon_inside_parent,
    validate_polygon_points,
)
from app.services.inspection_service import InspectionService


def test_validate_polygon_points_rejects_out_of_range() -> None:
    with pytest.raises(ValueError, match="inside \\[0, 1\\]"):
        validate_polygon_points([(0.0, 0.0), (1.2, 0.5), (0.5, 1.0)], "ROI polygon")


def test_validate_polygon_inside_parent() -> None:
    parent = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
    child = [(0.1, 0.1), (0.9, 0.1), (0.9, 0.9)]
    normalized = validate_polygon_inside_parent(child, parent, "Sub-ROI polygon")
    assert len(normalized) == 3
    assert polygon_area(normalized) > 0.0


def test_inspect_identical_frames_passes(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    result = inspection_service.inspect_frame("bench", gray_frame.copy(), threshold=0.5)

    assert result.product_type == "bench"
    assert result.status == "ГОДЕН"
    assert result.anomaly_score < result.threshold


def test_inspect_without_reference_raises(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    with pytest.raises(ValueError, match="Reference for product_type"):
        inspection_service.inspect_frame("missing", gray_frame)


def test_inspect_detects_large_difference(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    defective = gray_frame.copy()
    defective[10:30, 10:50] = 255

    result = inspection_service.inspect_frame("bench", defective, threshold=0.01)

    assert result.status == "БРАК"
    assert result.anomaly_score >= result.threshold


def test_set_and_get_reference(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("product-a", gray_frame)
    stored = inspection_service.get_reference("product-a")

    assert stored is not None
    assert stored.shape == gray_frame.shape
    assert np.array_equal(stored, gray_frame)
