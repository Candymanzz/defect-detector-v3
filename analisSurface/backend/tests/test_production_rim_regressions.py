"""Regressions for rounded ROI caps and review-derived mini-etalons."""

import cv2
import numpy as np
import pytest

from app.services.inspection_geometry import perspective_roi_far_weights
from app.services.learned_normals import InspectionReviewStore, decode_review_arrays


@pytest.mark.parametrize("horizontal", [False, True])
@pytest.mark.parametrize("taper", [0.0, 0.2])
def test_rounded_end_cap_uses_side_taper_not_corner_width(horizontal, taper):
    # Straight sides terminate in a curved cap drawn with several ROI points.
    # The cap approaches a point even when the surface has constant width.
    roi = np.array([
        (0.15, 0.95), (0.15 + taper, 0.25),
        (0.30 + taper / 2, 0.10), (0.45, 0.05), (0.55, 0.05),
        (0.70 - taper / 2, 0.10), (0.85 - taper, 0.25), (0.85, 0.95),
    ])
    line = ((0.5, 0.95), (0.5, 0.05))
    if horizontal:
        roi = roi[:, ::-1]
        line = tuple((y, x) for x, y in line)
    far, scale = perspective_roi_far_weights(
        301, 301, line, tuple(map(tuple, roi)), (0, 0, 301, 301), .35,
    )
    far_tip = (150, 16) if horizontal else (16, 150)
    assert far[far_tip] > .99
    if taper == 0:
        np.testing.assert_allclose(scale, 1.0, atol=1e-6)
    else:
        # Real side convergence remains active, but cap curvature cannot
        # inflate the scale to the former 2.5x ceiling.
        def side_width(y):
            return .7 - (2 * taper / .7) * (.95 - y)
        expected = side_width(.95 - .9 * .65) / side_width(16 / 300)
        assert scale[far_tip] == pytest.approx(expected, abs=.04)


def test_review_learning_uses_lossless_pixels_and_reads_legacy_jpeg(tmp_path):
    store = InspectionReviewStore(storage_dir=tmp_path / "reviews", session_wipe=False)
    image = np.random.default_rng(17).integers(0, 256, (48, 64, 3), dtype=np.uint8)
    zeros = np.zeros_like(image)
    review = store.add(
        inspection_id="lossless", product_type="part", reference_hash="ref",
        status="ГОДЕН", score=0, threshold=.31, aligned=image,
        diff_map=zeros, raw_mask=zeros, candidates=[],
    )
    restored = store.get(review.inspection_id)
    np.testing.assert_array_equal(decode_review_arrays(restored)[0], image)
    assert restored.image("aligned")[1] == "image/jpeg"
    jpeg = cv2.imdecode(np.frombuffer(review.aligned_jpeg, np.uint8), cv2.IMREAD_COLOR)
    assert not np.array_equal(jpeg, image)
    (store.storage_dir / review.inspection_id / "aligned.png").unlink()
    legacy = store.get(review.inspection_id)
    np.testing.assert_array_equal(decode_review_arrays(legacy)[0], jpeg)


@pytest.mark.parametrize("with_line", [False, True])
@pytest.mark.parametrize("with_scratch", [False, True])
def test_fp_mini_etalon_compensates_light_and_keeps_new_scratch(
    inspection_service, with_line, with_scratch,
):
    from app.services.analysis_settings import AnalysisSettings

    h, w = 180, 240
    y, x = np.mgrid[:h, :w]
    reference_gray = 100 + 12 * np.sin(x / 8) + 8 * np.cos(y / 9)
    reference = np.repeat(reference_gray[..., None], 3, axis=2).astype(np.uint8)
    shift = 65 * np.exp(-((x - 120) ** 2 / 45 ** 2 + (y - 75) ** 2 / 35 ** 2))
    current = np.clip(reference.astype(float) + shift[..., None], 0, 255).astype(np.uint8)
    if with_scratch:
        cv2.line(current, (105, 70), (135, 70), (5, 5, 5), 2)
    points = [(0.25, .2), (.75, .2), (.75, .65), (.25, .65)]
    weights = (np.ones((h, w), np.float32), np.ones((h, w), np.float32)) if with_line else None
    result = inspection_service._polygon_region_difference(
        current, reference, points,
        AnalysisSettings(use_patchcore=False, enable_clahe=True),
        expected_is_full_frame=True, perspective_weights=weights,
    )
    if with_scratch:
        assert result["score"] >= .31
        assert np.any(result["diff"])
    else:
        assert result["score"] < .31


def test_accepting_textured_normal_does_not_create_a_new_fp_residual(inspection_service):
    service = inspection_service
    reference = np.full((160, 200, 3), 70, np.uint8)
    normal = reference.copy()
    normal[40:100, 50:130] = np.random.default_rng(4).integers(
        90, 230, (60, 80, 3), dtype=np.uint8,
    )
    product = "textured-mini-etalon"
    service.set_reference_frame(product, reference)
    service.update_analysis_settings(product, {"use_patchcore": False, "default_threshold": .31})
    first = service.inspect_frame(product, normal, include_visuals=False)
    assert first.status == "БРАК"
    accepted = service.accept_all_review_defects_as_normal(first.inspection_id)
    assert accepted["saved"]
    second = service.inspect_frame(product, normal, include_visuals=False)
    assert second.status == "ГОДЕН"
    assert second.anomaly_score < .31
    assert all(zone.residual_score == 0 for zone in second.fp_zone_scores)


@pytest.mark.parametrize("defect", [None, "scratch", "bright_patch", "dark_patch"])
def test_moving_specular_band_keeps_local_defects(inspection_service, defect):
    from app.services.analysis_settings import AnalysisSettings

    h, w = 300, 400
    y, x = np.mgrid[:h, :w]
    reference_gray = 95 + 105 * np.exp(-((y - 100) / 10) ** 2)
    current_gray = 95 + 105 * np.exp(-((y - 105) / 10) ** 2)
    reference = np.repeat(reference_gray[..., None], 3, axis=2).astype(np.uint8)
    current = np.repeat(current_gray[..., None], 3, axis=2).astype(np.uint8)
    if defect == "scratch":
        cv2.line(current, (190, 103), (215, 103), (25, 25, 25), 2)
    elif defect == "bright_patch":
        current[102:105, 190:200] = 250
    elif defect == "dark_patch":
        current[102:105, 190:200] = 145
    settings = AnalysisSettings.from_overrides({
        "use_patchcore": False, "min_diff_signal": 9.86688,
        "min_defect_area": 5, "diff_percentile": 97.20008,
        "clahe_clip_limit": 1.665408, "far_edge_max_gain": 1.843,
    })
    weights = (np.ones((h, w), np.float32), np.ones((h, w), np.float32))
    diff = inspection_service._compute_advanced_difference(
        current, reference, settings, vertical_compensation=True,
        perspective_weights=weights,
    )
    score, mask = inspection_service._run_anomaly_model(
        diff, settings, perspective_weights=weights,
    )
    if defect is None:
        assert score < .31
        assert not np.any(mask)
    else:
        assert score >= .31
        assert np.any(mask[100:108, 185:218])


def test_illumination_normalization_is_independent_of_crop_size(inspection_service):
    y, x = np.mgrid[:300, :400]
    reference = (95 + 105 * np.exp(-((y - 100) / 10) ** 2)).astype(np.uint8)
    current = (95 + 105 * np.exp(-((y - 105) / 10) ** 2)).astype(np.uint8)
    normalized = np.empty_like(current)
    inspection_service._suppress_smooth_illumination(
        cv2.absdiff(current, reference), reference, current,
        normalized_current=normalized,
    )
    cropped_ref = reference[60:145, 150:250]
    cropped_cur = current[60:145, 150:250]
    cropped_normalized = np.empty_like(cropped_cur)
    inspection_service._suppress_smooth_illumination(
        cv2.absdiff(cropped_cur, cropped_ref), cropped_ref, cropped_cur,
        normalized_current=cropped_normalized,
    )
    np.testing.assert_array_equal(
        normalized[80:125, 170:230], cropped_normalized[20:65, 20:80],
    )
