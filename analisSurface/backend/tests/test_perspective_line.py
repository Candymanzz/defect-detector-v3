import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import inspection_service as shared_service
from app.main import app
from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import (
    perspective_far_weights,
    perspective_roi_far_weights,
    validate_perspective_line,
)
from app.services.inspection_service import InspectionService


def test_weights_grow_along_line_direction() -> None:
    line = ((0.0, 1.0), (0.0, 0.0))  # near = bottom, far = top
    smooth, far = perspective_far_weights(100, 200, line, (0, 0, 100, 200), 0.35)

    assert smooth.shape == far.shape == (200, 100)
    assert smooth[0, 0] == pytest.approx(1.0)
    assert smooth[-1, 0] == pytest.approx(0.0)
    assert far[0, 0] == pytest.approx(1.0)
    assert far[100, 0] == pytest.approx(0.0)  # far-edge weight is limited to the last 35%
    assert np.all(np.diff(smooth[:, 0]) <= 1e-6)


def test_weights_follow_a_diagonal_line_and_respect_bbox() -> None:
    line = ((0.0, 0.0), (1.0, 1.0))
    full, _ = perspective_far_weights(101, 101, line, (0, 0, 101, 101), 0.35)
    crop, _ = perspective_far_weights(101, 101, line, (40, 30, 20, 10), 0.35)

    assert full[0, 100] == pytest.approx(0.5, abs=0.02)
    assert full[100, 100] == pytest.approx(1.0)
    np.testing.assert_allclose(crop, full[30:40, 40:60], atol=1e-5)


def test_roi_width_compensation_only_activates_in_last_35_percent() -> None:
    line = ((0.5, 1.0), (0.5, 0.0))
    # Trapezoid narrows towards the far (top) edge.
    roi = ((0.35, 0.0), (0.65, 0.0), (0.95, 1.0), (0.05, 1.0))
    far, scale = perspective_roi_far_weights(201, 201, line, roi, (0, 0, 201, 201), 0.35)

    assert far[150, 100] == 0.0  # ordinary ROI area remains unchanged
    assert scale[150, 100] == 1.0
    assert 0.0 < far[35, 100] < 1.0
    assert scale[5, 100] > scale[60, 100] >= 1.0
    assert far[5, 5] == 0.0  # never boost outside the ROI
    assert scale[5, 5] == 1.0


def test_roi_width_compensation_is_neutral_for_constant_width_roi() -> None:
    line = ((0.5, 1.0), (0.5, 0.0))
    roi = ((0.2, 0.0), (0.8, 0.0), (0.8, 1.0), (0.2, 1.0))
    far, scale = perspective_roi_far_weights(201, 201, line, roi, (0, 0, 201, 201), 0.35)

    assert far[5, 100] > 0.9
    assert scale[5, 100] == pytest.approx(1.0, abs=0.03)


@pytest.mark.parametrize("horizontal", [False, True])
def test_far_compensation_does_not_disappear_beyond_drawn_endpoint(horizontal):
    # A hand-drawn endpoint may lie slightly inside the ROI. Pixels farther
    # away must retain maximum compensation rather than become neutral again.
    line = ((0.9, 0.5), (0.1, 0.5)) if horizontal else ((0.5, 0.9), (0.5, 0.1))
    roi = ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0))
    far, scale = perspective_roi_far_weights(201, 201, line, roi, (0, 0, 201, 201), 0.35)
    trace = far[100, :] if horizontal else far[:, 100]
    assert trace[5] == pytest.approx(1.0)
    assert trace[20] == pytest.approx(1.0)
    assert np.all(np.diff(trace) <= 1e-6)
    assert np.all(scale >= 1.0)


def test_perspective_roi_weights_match_cropped_and_scaled_frames():
    line = ((0.5, 0.85), (0.5, 0.2))
    roi = ((0.35, 0.15), (0.65, 0.15), (0.85, 0.9), (0.15, 0.9))
    from app.services.inspection_geometry import polygon_bbox_from_norm_points
    bbox = polygon_bbox_from_norm_points(401, 301, list(roi))
    full = perspective_roi_far_weights(401, 301, line, roi, (0, 0, 401, 301), 0.35)
    cropped = perspective_roi_far_weights(401, 301, line, roi, bbox, 0.35)
    x, y, w, h = bbox
    for full_map, crop_map in zip(full, cropped):
        np.testing.assert_allclose(crop_map, full_map[y:y+h, x:x+w], atol=1e-6)
    scaled = perspective_roi_far_weights(201, 151, line, roi, (0, 0, 201, 151), 0.35)
    # Compare inside the ROI: rasterization can move its boundary by one pixel
    # when a vertex lands halfway between pixels after resizing.
    np.testing.assert_allclose(scaled[0][25:130, 100], full[0][50:260:2, 200], atol=1e-5)


@pytest.mark.parametrize(
    "points",
    [[(0.1, 0.1)], [(0.1, 0.1), (0.1, 0.1)], [(0.0, 0.0), (1.2, 0.5)]],
)
def test_invalid_lines_are_rejected(points) -> None:
    with pytest.raises(ValueError):
        validate_perspective_line(points)


def test_line_amplifies_far_side_more_than_near_side(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((120, 200, 3), 120, dtype=np.uint8)
    current = reference.copy()
    current[50:70, 10:30] = 150  # identical faint defect on the left...
    current[50:70, 170:190] = 150  # ...and on the right
    settings = AnalysisSettings()
    weights = perspective_roi_far_weights(200, 120, ((1.0, 0.5), (0.0, 0.5)),
        ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)), (0, 0, 200, 120), 0.35)

    plain = inspection_service._compute_advanced_difference(current, reference, settings)
    with_line = inspection_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights
    )

    left = with_line[50:70, 10:30].mean() / max(plain[50:70, 10:30].mean(), 1e-6)
    right = with_line[50:70, 170:190].mean() / max(plain[50:70, 170:190].mean(), 1e-6)
    assert left > right  # left is the far end of the line


def test_roi_route_stores_and_returns_perspective_line() -> None:
    client = TestClient(app)
    product_type = "perspective-line-route-test"
    shared_service.set_reference_frame(product_type, np.full((60, 80, 3), 100, dtype=np.uint8))
    square = [{"x": 0.0, "y": 0.0}, {"x": 1.0, "y": 0.0}, {"x": 1.0, "y": 1.0}, {"x": 0.0, "y": 1.0}]
    line = [{"x": 0.5, "y": 0.9}, {"x": 0.5, "y": 0.1}]
    try:
        ok = client.post(
            "/roi-polygon",
            json={"product_type": product_type, "points": square, "perspective_line": line},
        )
        assert ok.status_code == 200
        assert client.get(f"/roi-polygon/{product_type}").json()["perspective_line"] == line

        bad = client.post(
            "/roi-polygon",
            json={"product_type": product_type, "points": square, "perspective_line": line[:1]},
        )
        assert bad.status_code == 400

        cleared = client.post("/roi-polygon", json={"product_type": product_type, "points": square})
        assert cleared.status_code == 200
        assert client.get(f"/roi-polygon/{product_type}").json()["perspective_line"] is None
    finally:
        shared_service.perspective_lines.pop(product_type, None)
        shared_service.roi_polygons.pop(product_type, None)
        shared_service.references.pop(product_type, None)


def test_far_edge_settings_are_validated_and_scale_the_far_side(
    inspection_service: InspectionService,
) -> None:
    with pytest.raises(ValueError):
        AnalysisSettings.from_overrides({"far_edge_max_gain": 0.5})
    with pytest.raises(ValueError):
        AnalysisSettings.from_overrides({"far_edge_edge_suppress_factor": 1.5})

    reference = np.full((120, 200, 3), 120, dtype=np.uint8)
    current = reference.copy()
    current[50:70, 10:30] = 150
    weights = perspective_roi_far_weights(200, 120, ((1.0, 0.5), (0.0, 0.5)),
        ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)), (0, 0, 200, 120), 0.35)

    def far_side_mean(gain: float) -> float:
        settings = AnalysisSettings.from_overrides({"far_edge_max_gain": gain})
        diff = inspection_service._compute_advanced_difference(
            current, reference, settings, vertical_compensation=True, perspective_weights=weights
        )
        return float(diff[50:70, 10:30].mean())

    assert far_side_mean(2.5) > far_side_mean(1.35)
    assert far_side_mean(4.0) > far_side_mean(2.5)


def test_far_detail_branch_preserves_small_defect_and_leaves_near_zone_unchanged(inspection_service):
    reference = np.full((120, 200, 3), 120, dtype=np.uint8)
    current = reference.copy()
    current[20:23, 95:105] = 165
    current[95:98, 95:105] = 165
    settings = AnalysisSettings(enable_clahe=False, far_edge_max_gain=1.0)
    weights = perspective_roi_far_weights(200, 120, ((0.5, 1.0), (0.5, 0.0)),
        ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)), (0, 0, 200, 120), 0.35)
    neutral = (np.zeros((120, 200), np.float32), np.ones((120, 200), np.float32))
    baseline = inspection_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=neutral)
    improved = inspection_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights)
    assert improved[20:23, 95:105].mean() > baseline[20:23, 95:105].mean()
    np.testing.assert_array_equal(improved[90:], baseline[90:])
    stable = inspection_service._compute_advanced_difference(
        reference, reference, settings, vertical_compensation=True, perspective_weights=weights)
    assert not np.any(stable)


def test_far_edge_boost_slider_maps_to_gain_and_edge_factor() -> None:
    from app.services.analysis_settings_presets import expand_merged

    default = expand_merged(0.25, 0.5)
    assert default["far_edge_max_gain"] == pytest.approx(1.35)
    assert default["far_edge_edge_suppress_factor"] == pytest.approx(0.35)

    weak = expand_merged(0.25, 0.5, far_edge_boost=0)
    strong = expand_merged(0.25, 0.5, far_edge_boost=100)
    assert weak["far_edge_max_gain"] == pytest.approx(1.2)
    assert strong["far_edge_max_gain"] == pytest.approx(2.2)
    assert strong["far_edge_edge_suppress_factor"] > default["far_edge_edge_suppress_factor"]
    # Общая чувствительность не должна двигать дальний край.
    assert expand_merged(0.25, 1.0)["far_edge_max_gain"] == pytest.approx(1.35)


def test_far_component_area_uses_scale_without_accepting_isolated_noise(inspection_service):
    diff = np.zeros((80, 100, 3), dtype=np.uint8)
    diff[20:25, 20:25] = 100
    settings = AnalysisSettings(use_patchcore=False, min_defect_area=60)
    neutral = (np.zeros((80, 100), np.float32), np.ones((80, 100), np.float32))
    far = (np.ones((80, 100), np.float32), np.full((80, 100), 2.0, np.float32))
    _, baseline = inspection_service._run_anomaly_model(diff, settings, perspective_weights=neutral)
    _, improved = inspection_service._run_anomaly_model(diff, settings, perspective_weights=far)
    assert not np.any(baseline)
    assert np.any(improved)
    noise = np.zeros_like(diff)
    noise[20, 20] = 100
    _, noise_mask = inspection_service._run_anomaly_model(noise, settings, perspective_weights=far)
    assert not np.any(noise_mask)


def test_regional_verdict_keeps_small_far_defect(inspection_service):
    diff = np.zeros((80, 100, 3), dtype=np.uint8)
    diff[20:25, 20:25] = 100
    settings = AnalysisSettings(use_patchcore=False, min_defect_area=60)
    weights = (np.ones((80, 100), np.float32), np.full((80, 100), 2.0, np.float32))
    raw_score, mask = inspection_service._run_anomaly_model(
        diff, settings, perspective_weights=weights)
    assert np.any(mask)
    _, _, regional_score, _ = inspection_service._score_inspection_regions(
        filtered_diff_map=diff, segmentation_mask=mask, inspection_threshold=0.15,
        settings=settings, polygon=None, sub_zones=[], perspective_weights=weights)
    assert regional_score >= raw_score


@pytest.mark.parametrize("value", [100, 255])
def test_far_scale_does_not_turn_single_pixel_into_defect(inspection_service, value):
    diff = np.zeros((80, 100, 3), dtype=np.uint8)
    diff[20, 20] = value
    settings = AnalysisSettings(use_patchcore=False, min_defect_area=6)
    weights = (np.ones((80, 100), np.float32), np.full((80, 100), 2.0, np.float32))
    _, mask = inspection_service._run_anomaly_model(diff, settings, perspective_weights=weights)
    assert not np.any(mask)


def test_small_fp_crop_preserves_far_detail_with_padding(inspection_service):
    reference = np.full((100, 100, 3), 120, np.uint8)
    current = reference.copy()
    current[20:23, 18:26] = 180
    points = [(0.15, 0.15), (0.3, 0.15), (0.3, 0.3), (0.15, 0.3)]
    settings = AnalysisSettings(enable_clahe=False, use_patchcore=False)
    weights = (np.ones((100, 100), np.float32), np.full((100, 100), 2.0, np.float32))
    neutral = inspection_service._polygon_region_difference(
        current, reference, points, settings, expected_is_full_frame=True)
    compensated = inspection_service._polygon_region_difference(
        current, reference, points, settings, expected_is_full_frame=True,
        perspective_weights=weights, decision_threshold=0.2)
    assert compensated["diff"].max() > neutral["diff"].max()
    assert compensated["activity"]["active_ratio"] > 0.0
    unchanged = inspection_service._polygon_region_difference(
        reference, reference, points, settings, expected_is_full_frame=True,
        perspective_weights=weights, decision_threshold=0.2)
    assert unchanged["score"] == 0.0


@pytest.mark.parametrize("inspect_scale", [1.0, 0.75])
def test_perspective_inspection_with_cropped_roi(inspection_service, inspect_scale):
    reference = np.full((180, 240, 3), 120, dtype=np.uint8)
    product = "cropped-perspective"
    inspection_service.set_reference_frame(product, reference)
    inspection_service.set_roi_polygon(product, [(0.3, 0.3), (0.7, 0.3), (0.8, 0.8), (0.2, 0.8)])
    inspection_service.set_perspective_line(product, [(0.5, 0.8), (0.5, 0.3)])
    inspection_service.inspect_frame(product, reference.copy(), include_visuals=False,
        inspect_scale_after_align=inspect_scale,
        alignment_h_ref_to_cur=[1., 0., 0., 0., 1., 0., 0., 0., 1.])
    assert not np.any(inspection_service._last_segmentation_masks[product])
