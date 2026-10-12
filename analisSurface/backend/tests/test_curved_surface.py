"""Projected surface evidence and its reference/FP/review lifecycle."""
import json
from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_curvature import (
    CurvatureModel, SurfaceWeights, crop_weights, estimate_curvature, pad_weights, with_curvature,
)
from app.services.inspection_evidence import compact_residual_evidence
from app.services.inspection_geometry import perspective_roi_far_weights, polygon_mask_from_norm_points
from app.services.inspection_service import InspectionService
from app.services.learned_normals import InspectionReviewStore, reference_fingerprint

SETTINGS = AnalysisSettings(use_patchcore=False, enable_clahe=False, min_diff_signal=10, min_defect_area=5)


def bucket_scene(angle=0):
    arc = np.linspace(np.pi, 0, 9)
    points = np.vstack((np.column_stack((160 + 100 * np.cos(arc), 100 - 65 * np.sin(arc))),
                        [[290, 270], [30, 270]]))
    line = np.array([[160., 265.], [160., 35.]])
    width, height = 320, 300
    if angle:
        radians = np.deg2rad(angle)
        rotation = np.array([[np.cos(radians), -np.sin(radians)],
                             [np.sin(radians), np.cos(radians)]])
        points = (points - [160, 150]) @ rotation.T + [240, 240]
        line = (line - [160, 150]) @ rotation.T + [240, 240]
        width = height = 480
    polygon = (points / [width - 1, height - 1]).tolist()
    line = (line / [width - 1, height - 1]).tolist()
    reference = np.full((height, width, 3), 20, np.uint8)
    reference[polygon_mask_from_norm_points(width, height, polygon) > 0] = 180
    return reference, polygon, line


def scene_weights(reference, polygon, line):
    height, width = reference.shape[:2]
    legacy = perspective_roi_far_weights(width, height, tuple(map(tuple, line)), tuple(map(tuple, polygon)),
                                         (0, 0, width, height), .35)
    model = estimate_curvature(reference, polygon, line)
    assert model is not None
    return legacy, with_curvature(legacy, model, width, height, polygon)


@pytest.mark.parametrize("angle", [0, 35, -35, 90])
def test_constrained_arc_fit_handles_camera_rotation_and_resolution(angle):
    reference, polygon, line = bucket_scene(angle)
    legacy, geometry = scene_weights(reference, polygon, line)
    model = geometry.model
    assert model.source == "reference-refined"
    assert model.cap_radii[0] == pytest.approx(100)
    assert model.cap_radii[1] == pytest.approx(65, abs=3)
    assert 1 <= geometry.curvature.min() <= geometry.curvature.max() <= 2
    roi = polygon_mask_from_norm_points(reference.shape[1], reference.shape[0], polygon)
    assert np.all(geometry.curvature[roi == 0] == 1)
    assert not geometry.curvature.flags.writeable
    restored = CurvatureModel.restore(json.loads(json.dumps(model.snapshot())))
    assert restored == model
    smaller, _ = restored.weights(reference.shape[1] // 2, reference.shape[0] // 2, polygon)
    assert smaller.max() == pytest.approx(geometry.curvature.max(), abs=.01)


def test_missing_silhouette_uses_more_conservative_roi_model():
    reference, polygon, line = bucket_scene()
    reference[:] = 180
    model = estimate_curvature(reference, polygon, line)
    assert model is not None and model.source == "roi"
    stretch, _ = model.weights(320, 300, polygon)
    assert 1.4 < stretch.max() <= 1.6


def test_two_pixel_drawing_error_does_not_extrapolate_ellipse_axes():
    reference, polygon, line = bucket_scene()
    rng = np.random.default_rng(42)
    fitted = []
    for _ in range(30):
        jittered = np.asarray(polygon) + rng.normal(0, 2, (len(polygon), 2)) / [319, 299]
        model = estimate_curvature(reference, jittered.tolist(), line)
        assert model is not None
        fitted.append(model.cap_radii)
    spread = np.std(fitted, axis=0) / np.mean(fitted, axis=0)
    assert np.max(spread) < .06


@pytest.mark.parametrize("kind", ["rectangle", "flat", "irregular", "bottom_view", "missing_line"])
def test_unsupported_geometry_has_no_surface_compensation(kind):
    reference, polygon, line = bucket_scene()
    if kind == "rectangle":
        polygon = [[.1, .1], [.3, .1], [.5, .1], [.7, .1], [.9, .1], [.9, .9], [.1, .9]]
    elif kind == "flat":
        for p in polygon[:9]:
            p[1] = (100 - (100 - p[1] * 299) * .05) / 299
    elif kind == "irregular":
        polygon[4][1] = .7
    elif kind == "bottom_view":
        theta = np.linspace(0, 2 * np.pi, 16, endpoint=False)
        polygon = np.column_stack((.5 + .3 * np.cos(theta), .5 + .2 * np.sin(theta))).tolist()
    else:
        line = None
    assert estimate_curvature(reference, polygon, line) is None


@pytest.mark.parametrize("damage", [{"version": 2}, {"confidence": float("nan")},
                                    {"sides": []}, {"frame_size": [0, 300]},
                                    {"transverse": [0, 0]}])
def test_damaged_historical_geometry_falls_back(damage):
    reference, polygon, line = bucket_scene()
    data = estimate_curvature(reference, polygon, line).snapshot()
    data.update(damage)
    assert CurvatureModel.restore(data) is None


def test_fp_crop_and_padding_preserve_the_full_frame_metric():
    reference, polygon, line = bucket_scene()
    _, geometry = scene_weights(reference, polygon, line)
    local = crop_weights(geometry, (245, 130, 17, 41))
    padded = pad_weights(local, ((12, 11), (23, 24)))
    assert isinstance(padded, SurfaceWeights)
    assert padded.model is geometry.model
    assert padded.transverse == geometry.transverse
    np.testing.assert_array_equal(padded.curvature[12:53, 23:40], geometry.curvature[130:171, 245:262])
    assert np.all(padded.curvature[:12] == 1)
    assert np.all(padded.far[:12] == 0)
    # The rotated normal on the cap must use original coordinates after crop
    # and padding, rather than the centre of the FP mini-etalon.
    cap_crop = crop_weights(geometry, (140, 40, 40, 30))
    cap_padded = pad_weights(cap_crop, ((10, 10), (10, 10)))
    np.testing.assert_allclose(cap_padded.direction_at(30, 15), geometry.direction_at(160, 45))
    assert abs(cap_padded.direction_at(30, 15)[1]) > .99


def score_residual(gray, weights):
    return compact_residual_evidence(gray, cv2.GaussianBlur(gray, (3, 3), 0), SETTINGS, weights)


def test_equal_surface_spots_keep_similar_evidence_despite_projected_area():
    reference, polygon, line = bucket_scene()
    legacy, geometry = scene_weights(reference, polygon, line)
    center = np.zeros(reference.shape[:2], np.uint8)
    edge = center.copy()
    cv2.ellipse(center, (160, 150), (6, 6), 0, 0, 360, 15, -1)
    cv2.ellipse(edge, (255, 150), (3, 6), 0, 0, 360, 15, -1)
    center_score = score_residual(center, geometry)[0]
    assert score_residual(edge, legacy)[0] < center_score - .04
    assert score_residual(edge, geometry)[0] == pytest.approx(center_score, abs=.03)


def test_transverse_shape_is_corrected_before_compact_rejection():
    reference, polygon, line = bucket_scene()
    legacy, geometry = scene_weights(reference, polygon, line)
    gray = np.zeros(reference.shape[:2], np.uint8)
    cv2.ellipse(gray, (255, 150), (2, 10), 0, 0, 360, 15, -1)
    assert score_residual(gray, legacy)[0] == 0
    score, mask = score_residual(gray, geometry)
    assert score > .25 and mask[150, 255] > 0
    # Stretching along the trace must not make it compact.
    incorrect = replace(geometry, transverse=(0., 1.))
    assert score_residual(gray, incorrect)[0] == 0


def test_curved_far_cap_corrects_axial_projection_before_shape_filtering():
    reference, polygon, line = bucket_scene()
    legacy, geometry = scene_weights(reference, polygon, line)
    gray = np.zeros(reference.shape[:2], np.uint8)
    cv2.ellipse(gray, (160, 45), (10, 2), 0, 0, 360, 15, -1)
    assert score_residual(gray, legacy)[0] == 0
    assert score_residual(gray, geometry)[0] > .25
    current = reference.copy()
    current[gray > 0] = 164
    service = InspectionService.__new__(InspectionService)
    service._anomaly_engine = None
    scores = []
    for weights in (legacy, geometry):
        diff = service._compute_advanced_difference(current, reference, SETTINGS,
                                                    vertical_compensation=True, perspective_weights=weights)
        score, mask = service._run_anomaly_model(diff, SETTINGS, perspective_weights=weights)
        scores.append(score)
    assert scores[0] < .25 < scores[1]


def test_body_taper_does_not_use_far_cap_radius():
    reference, polygon, line = bucket_scene()
    _, geometry = scene_weights(reference, polygon, line)
    # Halfway from axis to wall in a wider body section: use its local width.
    body_x = 160 + (100 + 30 * 100 / 170) * .5
    assert geometry.curvature[200, round(body_x)] == pytest.approx(1 / np.sqrt(.75), abs=.01)


def test_combined_perspective_and_curvature_area_is_bounded():
    reference, polygon, line = bucket_scene()
    _, geometry = scene_weights(reference, polygon, line)
    gray = np.zeros(reference.shape[:2], np.uint8)
    cv2.circle(gray, (255, 150), 5, 15, -1)
    corrected = replace(geometry, far=np.ones_like(geometry.far),
                        width_scale=np.full_like(geometry.width_scale, 2.5))
    legacy = (corrected.far, corrected.width_scale)
    assert score_residual(gray, corrected)[0] == pytest.approx(score_residual(gray, legacy)[0])


@pytest.mark.parametrize("kind", ["hot_pixels", "long_rim", "noise", "smooth_light", "jitter"])
def test_curvature_does_not_promote_noise_light_or_registration(kind):
    reference, polygon, line = bucket_scene()
    _, geometry = scene_weights(reference, polygon, line)
    service = InspectionService.__new__(InspectionService)
    service._anomaly_engine = None
    if kind in ("hot_pixels", "long_rim"):
        gray = np.zeros(reference.shape[:2], np.uint8)
        if kind == "hot_pixels":
            gray[150, 255] = 255
            gray[160, 255] = 255
        else:
            gray[100:230, 255:258] = 15
        assert score_residual(gray, geometry)[0] == 0
        return
    if kind == "jitter":
        cv2.putText(reference, "PRINT", (205, 190), cv2.FONT_HERSHEY_SIMPLEX, .5, (40, 40, 40), 1)
        current = cv2.warpAffine(reference, np.float32([[1, 0, 2], [0, 1, 0]]), (320, 300),
                                 borderMode=cv2.BORDER_REPLICATE)
    elif kind == "noise":
        noise = np.random.default_rng(42).normal(0, 3, reference.shape[:2])
        current = np.clip(reference.astype(float) + noise[..., None], 0, 255).astype(np.uint8)
    else:
        yy, xx = np.mgrid[:300, :320]
        light = 40 * np.exp(-((xx - 255) ** 2 / 24 ** 2 + (yy - 150) ** 2 / 12 ** 2))
        current = np.clip(reference.astype(float) + light[..., None], 0, 255).astype(np.uint8)
    diff = service._compute_advanced_difference(current, reference, SETTINGS,
                                                vertical_compensation=True, perspective_weights=geometry)
    diff[polygon_mask_from_norm_points(320, 300, polygon) == 0] = 0
    score, mask = service._run_anomaly_model(diff, SETTINGS, perspective_weights=geometry)
    assert score < .25
    assert not np.any(mask)


@pytest.mark.parametrize("fp_zone", [False, True])
@pytest.mark.parametrize("deferred", [False, True])
def test_projected_defect_survives_fp_and_learning_lifecycle(inspection_service, fp_zone, deferred):
    service = inspection_service
    reference, polygon, line = bucket_scene()
    product = "curved-wall"
    service.update_analysis_settings(product, SETTINGS.to_dict())
    service.set_reference_frame(product, reference)
    service.set_roi_polygon(product, polygon)
    service.set_perspective_line(product, line)
    if fp_zone:
        points = (np.array([[230, 130], [262, 130], [262, 173], [230, 173]]) / [319, 299]).tolist()
        service.add_fp_zone(product, points, 320, 300, aligned=reference,
                            reference_hash=reference_fingerprint(reference))
    current = reference.copy()
    cv2.ellipse(current, (255, 150), (2, 10), 0, 0, 360, (164, 164, 164), -1)
    kwargs = dict(settings=SETTINGS, threshold=.25, defer_learning_review=deferred)
    result = service.inspect_frame(product, current, **kwargs)
    assert result.anomaly_score > .25
    assert np.any(result.segmentation_mask[145:155, 253:258])
    if deferred:
        service._deferred_learning_reviews._queue.join()
    store = InspectionReviewStore(storage_dir=service._learning_reviews.storage_dir, session_wipe=False)
    review = store.get(result.inspection_id)
    assert review is not None
    assert CurvatureModel.restore(review.geometry_context["curvature_model"]) is not None
    score_before = service._review_counterfactual(review)[0]
    service.accept_all_review_defects_as_normal(result.inspection_id)
    assert service.inspect_frame(product, current, **kwargs).anomaly_score < .25
    changed = current.copy()
    cv2.ellipse(changed, (65, 150), (2, 10), 0, 0, 360, (164, 164, 164), -1)
    changed_result = service.inspect_frame(product, changed, **kwargs)
    assert changed_result.anomaly_score > .25
    if deferred:
        service._deferred_learning_reviews._queue.join()
    unaccepted = service._learning_reviews.get(changed_result.inspection_id)
    assert unaccepted is not None
    # Keep an unaccepted positive in the historical preview: equality at zero
    # after accepting everything would not demonstrate frozen shape geometry.
    positive_before_reference_change = service._review_counterfactual(unaccepted)[0]
    assert positive_before_reference_change > .25
    # Use a separate case for the geometry preview: learning can change scores,
    # but replacing the reference/ROI cannot change a historical metric.
    score_after_learning = service._review_counterfactual(review)[0]
    service.set_reference_frame(product, np.full_like(reference, 175))
    service.set_roi_polygon(product, [[.05, .05], [.95, .05], [.95, .95], [.05, .95]])
    service.set_perspective_line(product, None)
    assert service._review_counterfactual(review)[0] == pytest.approx(score_after_learning)
    assert service._review_counterfactual(unaccepted)[0] == pytest.approx(positive_before_reference_change)
    assert score_before is not None


def test_cache_rebuilds_on_reference_roi_line_and_resolution_change(inspection_service, monkeypatch):
    import app.services.inspection_service as module
    service = inspection_service
    reference, polygon, line = bucket_scene()
    product = "curvature-cache"
    service.set_reference_frame(product, reference)
    service.set_roi_polygon(product, polygon)
    service.set_perspective_line(product, line)
    calls = []
    original = module.estimate_curvature
    def estimate(*args):
        calls.append(args[0].copy())
        return original(*args)
    monkeypatch.setattr(module, "estimate_curvature", estimate)
    def weights(shape=reference.shape):
        return service._perspective_weights_for(product, shape, None, shape, service.get_roi_polygon(product))
    first = weights()
    assert isinstance(first, SurfaceWeights)
    weights()
    assert len(calls) == 1
    weights((150, 160, 3))
    assert len(calls) == 2
    service.set_perspective_line(product, [[.5, .85], [.5, .1]])
    weights()
    assert len(calls) == 3
    service.set_roi_polygon(product, [[.1, .1], [.9, .1], [.9, .9], [.1, .9]])
    assert not isinstance(weights(), SurfaceWeights)
    weights()
    assert len(calls) == 4  # Invalid models are cached too.
    service.set_reference_frame(product, np.full_like(reference, 170))
    weights()
    assert len(calls) == 5
    assert np.all(calls[-1] == 170)
    service.clear_inspection_context()
    assert not service._surface_geometry_cache


def test_old_review_without_model_keeps_legacy_geometry(inspection_service, monkeypatch):
    service = inspection_service
    reference, polygon, line = bucket_scene()
    service.set_reference_frame("legacy-review", reference)
    service.set_roi_polygon("legacy-review", polygon)
    service.set_perspective_line("legacy-review", line)
    result = service.inspect_frame("legacy-review", reference, settings=SETTINGS)
    review = service._learning_reviews.get(result.inspection_id)
    review.geometry_context.pop("curvature_model")
    seen = []
    original = service._run_anomaly_model
    def run(*args, **kwargs):
        seen.append(kwargs.get("perspective_weights"))
        return original(*args, **kwargs)
    monkeypatch.setattr(service, "_run_anomaly_model", run)
    assert service._review_counterfactual(review)[0] == 0
    assert seen and all(not isinstance(w, SurfaceWeights) for w in seen)


def test_sub_roi_uses_shared_curvature_evidence(inspection_service):
    service = inspection_service
    reference, polygon, line = bucket_scene()
    product = "curved-sub-roi"
    service.set_reference_frame(product, reference)
    service.set_roi_polygon(product, polygon)
    service.set_perspective_line(product, line)
    points = (np.array([[230, 130], [262, 130], [262, 173], [230, 173]]) / [319, 299]).tolist()
    zone = service.add_roi_sub_zone(product, points, threshold=.25)
    current = reference.copy()
    cv2.ellipse(current, (255, 150), (2, 10), 0, 0, 360, (164, 164, 164), -1)
    result = service.inspect_frame(product, current, settings=SETTINGS, threshold=.25)
    assert result.anomaly_score > .25
    score = next(z for z in result.sub_zone_scores if z.zone_id == zone.id)
    assert score.anomaly_score > .25


@pytest.mark.parametrize("radius", [3, 5])
def test_saved_real_reference_preserves_control_and_raises_injected_spot_evidence(radius):
    fixtures = Path(__file__).resolve().parents[1] / "test_data/compact_residuals"
    with np.load(fixtures / "far_tip.npz", allow_pickle=False) as f, \
            np.load(fixtures / "curved_surface.npz", allow_pickle=False) as geometry:
        reference = f["reference"]
        settings = AnalysisSettings.from_overrides(json.loads(str(f["settings_json"])))
        model = CurvatureModel.restore(json.loads(str(geometry["model_json"])))
        assert model is not None
        legacy = (f["far"], f["scale"])
        weights = SurfaceWeights(*legacy, geometry["curvature"], tuple(geometry["transverse"]), model,
                                 tuple(geometry["frame_size"]), tuple(geometry["origin"]))
        service = InspectionService.__new__(InspectionService)
        service._anomaly_engine = None
        control = service._compute_advanced_difference(reference, reference, settings,
                                                        vertical_compensation=True, perspective_weights=weights)
        assert not np.any(control)
        current = reference.copy()
        cv2.circle(current, tuple(f["defect_center"]), radius, (10, 10, 10), -1)
        scores = []
        for maps in (legacy, weights):
            diff = service._compute_advanced_difference(current, reference, settings,
                                                        vertical_compensation=True, perspective_weights=maps)
            diff[f["roi_mask"] == 0] = 0
            scores.append(service._run_anomaly_model(diff, settings, perspective_weights=maps)[0])
        assert scores[1] > scores[0] + .03
