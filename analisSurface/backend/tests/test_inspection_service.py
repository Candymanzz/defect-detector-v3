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


def test_identity_homography_skips_realign(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    assert inspection_service._is_identity_homography(identity)
    assert not inspection_service._is_identity_homography([1.0, 0.0, 5.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0])

    aligned = inspection_service._align_to_reference(
        gray_frame.copy(),
        gray_frame,
        "bench",
        alignment_h_ref_to_cur=identity,
        enable_internal_alignment=True,
    )
    assert np.array_equal(aligned, gray_frame)


def test_internal_alignment_disabled_skips_homography_warp(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    """По умолчанию align выключен: даже не-identity H не применяется."""
    shift_h = [1.0, 0.0, 12.0, 0.0, 1.0, 8.0, 0.0, 0.0, 1.0]
    aligned = inspection_service._align_to_reference(
        gray_frame.copy(),
        gray_frame,
        "bench",
        alignment_h_ref_to_cur=shift_h,
        enable_internal_alignment=False,
    )
    assert np.array_equal(aligned, gray_frame)


def test_activity_score_does_not_saturate_on_moderate_mask() -> None:
    # Раньше active_ratio*1.2 давал 1.0 уже при ~0.84 покрытия маски.
    score = InspectionService._activity_score(diff_q90=40.0, diff_max=80.0, active_ratio=0.85)
    assert score < 1.0
    assert score > 0.3


def test_score_region_uses_real_mask_not_bbox(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    h, w = gray_frame.shape[:2]
    region_mask = np.zeros((h, w), dtype=bool)
    # Узкий треугольник: bbox намного больше самой ROI.
    region_mask[10:50, 20:25] = True
    region_mask[10:30, 25:40] = True

    diff_map = np.zeros_like(gray_frame)
    # Сильный diff только ВНЕ треугольника, но ВНУТРИ bbox — раньше раздувал score.
    diff_map[10:50, 40:70] = (0, 0, 255)
    segmentation_mask = diff_map.copy()

    score = inspection_service._score_region(
        diff_map,
        segmentation_mask,
        region_mask,
        inspection_service.get_analysis_settings("bench"),
    )
    assert score < 0.2


def test_set_and_get_reference(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("product-a", gray_frame)
    stored = inspection_service.get_reference("product-a")

    assert stored is not None
    assert stored.shape == gray_frame.shape
    assert np.array_equal(stored, gray_frame)
