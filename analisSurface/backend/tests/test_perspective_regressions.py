"""Photometric and geometric regressions independent of persisted profiles."""

from dataclasses import replace

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import perspective_roi_far_weights
from app.services.inspection_service import InspectionService


ROI = ((0.35, 0.0), (0.65, 0.0), (0.95, 1.0), (0.05, 1.0))
LINE = ((0.5, 1.0), (0.5, 0.0))


@pytest.fixture
def numeric_service():
    service = InspectionService.__new__(InspectionService)
    service._anomaly_engine = None
    return service


@pytest.fixture
def settings():
    return AnalysisSettings.from_overrides({
        "use_patchcore": False, "min_diff_signal": 9.86688,
        "min_defect_area": 5, "diff_percentile": 97.20008,
        "min_scratch_aspect": 2.35764, "scratch_score_floor": 0.446354,
        "scratch_aspect_floor": 3.53646, "clahe_clip_limit": 1.665408,
        "far_edge_max_gain": 1.843, "far_edge_edge_suppress_factor": 0.611,
    })


def scene(light_shift=0, textured=False):
    height, width = 240, 320
    reference = np.full((height, width, 3), 120, np.uint8)
    if textured:
        cv2.putText(reference, "BATCH 123", (110, 45), cv2.FONT_HERSHEY_SIMPLEX, .6, (95, 95, 95), 2)
    y, x = np.mgrid[:height, :width]
    light = light_shift * np.exp(-((x - 160) ** 2 / 70 ** 2 + (y - 45) ** 2 / 30 ** 2))
    current = np.clip(reference.astype(float) + light[..., None], 0, 255).astype(np.uint8)
    weights = perspective_roi_far_weights(width, height, LINE, ROI, (0, 0, width, height), .35)
    return reference, current, weights


@pytest.mark.parametrize("light_shift", [-70, 70])
@pytest.mark.parametrize("textured", [False, True])
def test_local_smooth_light_is_not_a_defect(numeric_service, settings, light_shift, textured):
    reference, current, weights = scene(light_shift, textured)
    diff = numeric_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights,
    )
    score, mask = numeric_service._run_anomaly_model(diff, settings, perspective_weights=weights)
    assert score < .387
    assert not np.any(mask)


@pytest.mark.parametrize("gain", [1.0, 1.843, 4.0])
@pytest.mark.parametrize("light_shift", [-70, 70])
@pytest.mark.parametrize("with_line", [False, True])
def test_far_gain_does_not_reintroduce_smooth_light(
    numeric_service, settings, gain, light_shift, with_line,
):
    reference, _, _ = scene()
    height, width = reference.shape[:2]
    y, x = np.mgrid[:height, :width]
    light = light_shift * np.exp(-((x - 160) ** 2 / 24 ** 2 + (y - 45) ** 2 / 12 ** 2))
    current = np.clip(reference.astype(float) + light[..., None], 0, 255).astype(np.uint8)
    # Isolate the farthest surface point from ROI taper and direction geometry.
    weights = (np.ones((height, width), np.float32), np.ones((height, width), np.float32)) if with_line else None
    settings = replace(settings, far_edge_max_gain=gain)
    diff = numeric_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True,
        perspective_weights=weights, roi_vertical_bounds=(0, height),
    )
    score, mask = numeric_service._run_anomaly_model(diff, settings, perspective_weights=weights)
    assert score < .387
    assert not np.any(mask)


@pytest.mark.parametrize("gain", [1.0, 4.0])
@pytest.mark.parametrize("light_shift", [-70, 70])
def test_far_scratch_survives_gain_dependent_light_guard(
    numeric_service, settings, gain, light_shift,
):
    reference, current, weights = scene(light_shift)
    cv2.line(current, (155, 25), (175, 25), (20, 20, 20), 2)
    settings = replace(settings, far_edge_max_gain=gain)
    diff = numeric_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights,
    )
    score, mask = numeric_service._run_anomaly_model(diff, settings, perspective_weights=weights)
    assert score >= .387
    assert np.any(mask[23:28, 153:178])


def test_saturated_far_glare_keeps_evidence(numeric_service, settings):
    reference, current, weights = scene(170)
    diffs = [numeric_service._compute_advanced_difference(
        current, reference, replace(settings, far_edge_max_gain=gain),
        vertical_compensation=True, perspective_weights=weights,
    ) for gain in (1.0, 4.0)]
    assert current[45, 160, 0] == 255
    assert diffs[1][45, 160, 0] > diffs[0][45, 160, 0]
    score, mask = numeric_service._run_anomaly_model(diffs[1], settings, perspective_weights=weights)
    assert score >= .387
    assert np.any(mask[40:50, 155:165])


@pytest.mark.parametrize("light_shift", [-70, 0, 70])
@pytest.mark.parametrize("direction", ["horizontal", "vertical"])
def test_far_scratch_survives_light_compensation(numeric_service, settings, light_shift, direction):
    reference, current, weights = scene(light_shift)
    end = (175, 25) if direction == "horizontal" else (155, 45)
    cv2.line(current, (155, 25), end, (20, 20, 20), 2)
    diff = numeric_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights,
    )
    score, mask = numeric_service._run_anomaly_model(diff, settings, perspective_weights=weights)
    assert score >= .387
    assert np.any(mask[23:47, 153:177])


def test_noise_and_small_far_defect_are_distinguished(numeric_service, settings):
    reference, current, weights = scene()
    noise = np.random.default_rng(7).normal(0, 3, current.shape[:2])
    noisy = np.clip(current.astype(float) + noise[..., None], 0, 255).astype(np.uint8)
    noise_diff = numeric_service._compute_advanced_difference(
        noisy, reference, settings, vertical_compensation=True, perspective_weights=weights,
    )
    noise_score, _ = numeric_service._run_anomaly_model(noise_diff, settings, perspective_weights=weights)
    current[20:23, 150:160] = 190
    defect_diff = numeric_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True, perspective_weights=weights,
    )
    defect_score, _ = numeric_service._run_anomaly_model(defect_diff, settings, perspective_weights=weights)
    assert noise_score < .387 <= defect_score


def test_weak_elongated_signal_does_not_force_reject_at_any_threshold(numeric_service, settings):
    diff = np.zeros((80, 160, 3), np.uint8)
    diff[20:23, 50:70] = 11
    weights = (np.ones((80, 160), np.float32), np.full((80, 160), 2, np.float32))
    scores = [numeric_service._run_anomaly_model(
        diff, settings, perspective_weights=weights, decision_threshold=threshold,
    )[0] for threshold in (.25, .8)]
    assert scores[0] == pytest.approx(scores[1])
    assert scores[1] < .8


def test_diagonal_rectangle_does_not_invent_surface_taper():
    roi = ((0., 0.), (1., 0.), (1., 1.), (0., 1.))
    far, scale = perspective_roi_far_weights(201, 201, ((0., 1.), (1., 0.)), roi, (0, 0, 201, 201), .35)
    assert far[0, -1] == 1
    np.testing.assert_array_equal(scale, np.ones_like(scale))


def test_partial_crop_does_not_change_surface_scale():
    full = perspective_roi_far_weights(320, 240, LINE, ROI, (0, 0, 320, 240), .35)
    cropped = perspective_roi_far_weights(320, 240, LINE, ROI, (140, 0, 40, 60), .35)
    for full_map, crop_map in zip(full, cropped):
        np.testing.assert_allclose(crop_map, full_map[:60, 140:180], atol=1e-6)


def test_masked_roi_glare_is_not_rejected(inspection_service, settings):
    reference, current, _ = scene(70)
    inspection_service.set_reference_frame("glare-regression", reference)
    inspection_service.set_roi_polygon("glare-regression", list(ROI))
    inspection_service.set_perspective_line("glare-regression", list(LINE))
    result = inspection_service.inspect_frame(
        "glare-regression", current, settings=settings, threshold=.387,
        include_visuals=False, store_learning_review=False,
    )
    assert result.status == "ГОДЕН"


def test_heatmap_keeps_components_omitted_from_review(inspection_service, settings, monkeypatch):
    reference, current, _ = scene()
    cv2.line(current, (40, 70), (100, 70), (20, 20, 20), 3)
    cv2.line(current, (220, 160), (280, 160), (20, 20, 20), 3)
    candidate_counts = []

    def only_first_candidate(candidates, **kwargs):
        candidate_counts.append(len(candidates))
        return candidates[:1]

    monkeypatch.setattr(
        "app.services.inspection_service.filter_review_candidates", only_first_candidate,
    )
    inspection_service.set_reference_frame("heatmap-regression", reference)
    result = inspection_service.inspect_frame(
        "heatmap-regression", current, settings=settings,
        include_visuals=True, store_learning_review=True,
    )
    assert candidate_counts[-1] >= 2
    expected = inspection_service._build_heatmap_gray(result.segmentation_mask, result.diff_map)
    np.testing.assert_array_equal(result.heatmap_u8, expected)
