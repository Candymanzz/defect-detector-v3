"""Far-tip sensitivity must respect global geometry and the normal/FP lifecycle."""
import json
from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import far_tip_weights, perspective_roi_far_weights
from app.services.inspection_service import InspectionService
from app.services.learned_normals import InspectionReviewStore, reference_fingerprint


@pytest.fixture
def edge_scene():
    # A real reference crop; the black spots in these tests are synthetic.
    path = Path(__file__).resolve().parents[1] / "test_data/compact_residuals/far_tip.npz"
    with np.load(path, allow_pickle=False) as f:
        yield (f["reference"], (f["far"], f["scale"]), f["roi_mask"],
               tuple(int(v) for v in f["defect_center"]),
               AnalysisSettings.from_overrides(json.loads(f["settings_json"].item())))


def numeric_service():
    service = InspectionService.__new__(InspectionService)
    service._anomaly_engine = None
    return service


@pytest.mark.parametrize("line", [((.5, 1.), (.5, 0.)), ((1., .5), (0., .5)), ((0., 0.), (1., 1.))])
def test_terminal_band_follows_the_last_tenth_of_the_drawn_axis(line):
    roi = ((0., 0.), (1., 0.), (1., 1.), (0., 1.))
    far, _ = perspective_roi_far_weights(401, 401, line, roi, (0, 0, 401, 401), .35)
    tip = far_tip_weights(far)
    for progress, expected in ((.65, 0.), (.85, 0.), (.90, 0.), (.95, .5), (1., 1.)):
        x, y = np.rint((np.array(line[0]) + progress * (np.array(line[1]) - line[0])) * 400).astype(int)
        assert tip[y, x] == pytest.approx(expected, abs=2e-5)


def test_terminal_band_respects_roi_crops_padding_and_endpoint_saturation():
    roi = ((.2, .05), (.8, .05), (.8, .95), (.2, .95))
    line = ((.5, .9), (.5, .1))
    far, _ = perspective_roi_far_weights(401, 301, line, roi, (0, 0, 401, 301), .35)
    tip = far_tip_weights(far)
    assert tip[18, 200] == 1.0  # farther than the endpoint, still inside the ROI
    assert tip[0, 200] == 0.0   # outside the ROI
    assert tip[30, 20] == 0.0
    cropped, _ = perspective_roi_far_weights(401, 301, line, roi, (170, 18, 60, 50), .35)
    padded = np.pad(cropped, ((10, 12), (8, 9)), constant_values=0)
    np.testing.assert_allclose(far_tip_weights(padded)[10:60, 8:68], tip[18:68, 170:230])
    assert not np.any(far_tip_weights(padded)[:10])
    assert not tip.flags.writeable


@pytest.mark.parametrize("radius", [2, 3])
def test_injected_black_spot_at_real_far_edge_crosses_reject_threshold(edge_scene, monkeypatch, radius):
    ref, weights, roi, center, settings = edge_scene
    current = ref.copy()
    cv2.circle(current, center, radius, (10, 10, 10), -1)
    service = numeric_service()

    def score():
        diff = service._compute_advanced_difference(
            current, ref, settings, vertical_compensation=True, perspective_weights=weights,
        )
        diff[roi == 0] = 0
        return service._run_anomaly_model(diff, settings, perspective_weights=weights)

    with monkeypatch.context() as patch:
        patch.setattr("app.services.inspection_service._FAR_TIP_EXTRA_GAIN_LIMIT", 0.)
        baseline, _ = score()
    improved, mask = score()
    assert baseline < .25 < improved
    x, y = center
    assert np.any(mask[y-3:y+4, x-3:x+4])


@pytest.mark.parametrize("kind", ["jitter", "smooth_light", "noise", "hot_pixel"])
def test_extra_sensitivity_does_not_promote_far_edge_nuisance(edge_scene, kind):
    ref, weights, roi, _, settings = edge_scene
    h, w = ref.shape[:2]
    if kind == "jitter":
        current = cv2.warpAffine(ref, np.float32([[1, 0, 2], [0, 1, 0]]), (w, h), borderMode=cv2.BORDER_REPLICATE)
    elif kind == "smooth_light":
        yy, xx = np.mgrid[:h, :w]
        light = 40 * np.exp(-((xx-200)**2/45**2 + (yy-65)**2/20**2))
        current = np.clip(ref.astype(float) + light[..., None], 0, 255).astype(np.uint8)
    elif kind == "noise":
        noise = np.random.default_rng(9).normal(0, 2, ref.shape[:2])
        current = np.clip(ref.astype(float) + noise[..., None], 0, 255).astype(np.uint8)
    else:
        current = ref.copy()
        current[65, 200] = 255
    service = numeric_service()
    diff = service._compute_advanced_difference(
        current, ref, settings, vertical_compensation=True, perspective_weights=weights,
    )
    diff[roi == 0] = 0
    assert service._run_anomaly_model(diff, settings, perspective_weights=weights)[0] < .25


def test_small_fp_crop_does_not_receive_extra_gain_for_moving_glare():
    h, w = 300, 400
    yy, _ = np.mgrid[:h, :w]
    ref = np.repeat((95 + 105 * np.exp(-((yy-100)/10)**2))[..., None], 3, axis=2).astype(np.uint8)
    cur = np.repeat((95 + 105 * np.exp(-((yy-105)/10)**2))[..., None], 3, axis=2).astype(np.uint8)
    service = numeric_service()
    settings = AnalysisSettings(use_patchcore=False, far_edge_max_gain=1.843, clahe_clip_limit=1.665408)
    weights = (np.ones((h, w), np.float32), np.ones((h, w), np.float32))
    kwargs = dict(expected_is_full_frame=True, perspective_weights=weights)
    for points in (
        [(.475,.25),(.525,.25),(.525,.45),(.475,.45)],
        [(.45,.31),(.55,.31),(.55,.38),(.45,.38)],
    ):
        result = service._polygon_region_difference(cur, ref, points, settings, **kwargs)
        assert result["score"] < .25


def test_near_zone_and_explicit_neutral_gain_keep_their_previous_response(edge_scene, monkeypatch):
    ref, weights, _, center, settings = edge_scene
    current = ref.copy()
    cv2.circle(current, center, 3, (10, 10, 10), -1)
    service = numeric_service()
    for gain in (1., settings.far_edge_max_gain, 4.):
        params = replace(settings, far_edge_max_gain=gain)
        with monkeypatch.context() as patch:
            patch.setattr("app.services.inspection_service._FAR_TIP_EXTRA_GAIN_LIMIT", 0.)
            baseline = service._compute_advanced_difference(current, ref, params, vertical_compensation=True, perspective_weights=weights)
        improved = service._compute_advanced_difference(current, ref, params, vertical_compensation=True, perspective_weights=weights)
        outside = far_tip_weights(weights[0]) == 0
        np.testing.assert_array_equal(improved[outside], baseline[outside])
        if gain == 1.:
            np.testing.assert_array_equal(improved, baseline)


@pytest.mark.parametrize("deferred", [False, True])
def test_tip_defect_survives_fp_review_acceptance_and_reference_replacement(inspection_service, edge_scene, deferred):
    _, _, _, _, settings = edge_scene
    # A faint local defect near the far end exercises normal learning and FP
    # rechecking without relying on the real reference crop's masked context.
    reference = np.full((240, 320, 3), 160, np.uint8)
    product = "far-tip-lifecycle"
    roi = [(0., 0.), (1., 0.), (1., 1.), (0., 1.)]
    line = [(.5, 1.), (.5, 0.)]
    settings = replace(settings, enable_clahe=False)
    service = inspection_service
    service.update_analysis_settings(product, settings.to_dict())
    service.set_reference_frame(product, reference)
    service.set_roi_polygon(product, roi)
    service.set_perspective_line(product, line)
    service.add_fp_zone(product, roi, 320, 240, aligned=reference,
                        reference_hash=reference_fingerprint(reference))
    current = reference.copy()
    current[8:16, 70:78] = 125
    kwargs = dict(settings=settings, threshold=.25, include_visuals=True,
                  alignment_h_ref_to_cur=[1.,0.,0.,0.,1.,0.,0.,0.,1.],
                  defer_learning_review=deferred)
    first = service.inspect_frame(product, current, **kwargs)
    assert first.status == "БРАК" and first.anomaly_score > .25
    assert first.rechecked_zones_count > 0
    if deferred:service._deferred_learning_reviews._queue.join()
    store = InspectionReviewStore(storage_dir=service._learning_reviews.storage_dir, session_wipe=False)
    review = store.get(first.inspection_id)
    assert review.geometry_context["perspective_line"] == [list(p) for p in line]
    service.accept_all_review_defects_as_normal(first.inspection_id)
    assert service.inspect_frame(product, current, **kwargs).status == "ГОДЕН"
    changed = current.copy()
    changed[8:16, 230:238] = 125
    new = service.inspect_frame(product, changed, **kwargs)
    assert new.status == "БРАК" and new.anomaly_score > .25
    assert np.any(new.segmentation_mask[8:16, 230:238])
    # Persisted geometry, rather than a new line, controls an old review.
    if deferred:service._deferred_learning_reviews._queue.join()
    new_review = store.get(new.inspection_id)
    previous = service._review_counterfactual(new_review)[0]
    service.set_perspective_line(product, [(.0,.5),(1.,.5)])
    assert service._review_counterfactual(new_review)[0] == pytest.approx(previous)
    service.set_reference_frame(product, np.full_like(reference, 170))
    assert service.inspect_frame(product, changed, **kwargs).status == "БРАК"
