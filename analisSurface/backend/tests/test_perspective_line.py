import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import inspection_service as shared_service
from app.main import app
from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import perspective_far_weights, validate_perspective_line
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
    weights = perspective_far_weights(200, 120, ((1.0, 0.5), (0.0, 0.5)), (0, 0, 200, 120), 0.35)

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
    weights = perspective_far_weights(200, 120, ((1.0, 0.5), (0.0, 0.5)), (0, 0, 200, 120), 0.35)

    def far_side_mean(gain: float) -> float:
        settings = AnalysisSettings.from_overrides({"far_edge_max_gain": gain})
        diff = inspection_service._compute_advanced_difference(
            current, reference, settings, vertical_compensation=True, perspective_weights=weights
        )
        return float(diff[50:70, 10:30].mean())

    assert far_side_mean(2.5) > far_side_mean(1.35)


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
