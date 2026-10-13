"""Adaptive residual detection: weak compact defects versus nuisance evidence."""

from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_evidence import compact_residual_evidence
from app.services.inspection_service import InspectionService
from app.services.learned_normals import reference_fingerprint


SETTINGS = AnalysisSettings(use_patchcore=False, min_diff_signal=12, min_defect_area=6)


def evidence(gray, settings=SETTINGS, weights=None):
    return compact_residual_evidence(gray, cv2.GaussianBlur(gray, (3, 3), 0), settings, weights)


def numeric_service():
    service = InspectionService.__new__(InspectionService)
    service._anomaly_engine = None
    return service


def test_connected_weak_patch_below_absolute_cutoff_can_reject():
    gray = np.zeros((120, 160), np.uint8)
    gray[40:64, 60:84] = 11  # The original branch returns zero at max < 12.
    score, mask = numeric_service()._run_anomaly_model(cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR), SETTINGS)
    assert .25 < score < .65
    assert np.any(mask[42:62, 62:82])
    assert not np.any(mask[:30])


def test_area_keeps_contributing_for_compact_defects():
    scores = []
    for size in (6, 10, 16, 24):
        gray = np.zeros((120, 160), np.uint8)
        gray[40:40 + size, 60:60 + size] = 16
        scores.append(evidence(gray)[0])
    assert all(b > a + .025 for a, b in zip(scores, scores[1:]))


def test_same_signal_is_less_significant_against_local_texture():
    clean = np.zeros((120, 160), np.uint8)
    noisy = np.random.default_rng(12).integers(0, 12, clean.shape, dtype=np.uint8)
    for gray in (clean, noisy):
        gray[40:64, 60:84] = 11
    clean_score, clean_mask = evidence(clean)
    noisy_score, _ = evidence(noisy)
    assert clean_score > .25
    assert np.any(clean_mask)
    assert noisy_score < .25


def test_noise_tolerance_setting_still_limits_adaptive_detection():
    gray = np.zeros((120, 160), np.uint8)
    gray[40:64, 60:84] = 11
    assert evidence(gray)[0] > .25
    score, mask = evidence(gray, replace(SETTINGS, min_diff_signal=40))
    assert score == 0
    assert not np.any(mask)


@pytest.mark.parametrize("kind", ["speckles", "diagonal_strip", "curved_edge", "uniform"])
def test_noise_and_boundary_traces_do_not_get_compact_boost(kind):
    gray = np.zeros((120, 160), np.uint8)
    if kind == "speckles":
        gray[::8, ::8] = 80
    elif kind == "diagonal_strip":
        cv2.line(gray, (35, 30), (85, 70), 20, 4)
    elif kind == "curved_edge":
        cv2.ellipse(gray, (70, 60), (22, 22), 0, 190, 270, 20, 3)
    else:
        gray[:] = 11
    score, mask = evidence(gray)
    assert score == 0
    assert not np.any(mask)


def test_local_score_is_independent_of_frame_crop_and_decision_threshold():
    gray = np.zeros((120, 160), np.uint8)
    gray[40:64, 60:84] = 11
    full_score, full_mask = evidence(gray)
    score, mask = evidence(gray[25:85, 45:105])
    assert score == pytest.approx(full_score)
    np.testing.assert_array_equal(mask, full_mask[25:85, 45:105])
    service = numeric_service()
    diff = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    assert service._run_anomaly_model(diff, SETTINGS, decision_threshold=.25)[0] == pytest.approx(
        service._run_anomaly_model(diff, SETTINGS, decision_threshold=.8)[0]
    )


def test_perspective_compensates_supported_area_but_does_not_create_noise():
    gray = np.zeros((120, 160), np.uint8)
    gray[40:56, 60:76] = 16
    weights = (np.ones_like(gray, np.float32), np.full_like(gray, 2, np.float32))
    assert evidence(gray, weights=weights)[0] > evidence(gray)[0]
    gray[:] = 0
    gray[48, 68] = 255
    score, mask = evidence(gray, weights=weights)
    assert score == 0 and not np.any(mask)


@pytest.mark.parametrize("inside_fp", [False, True])
def test_weak_defect_survives_full_inspection_and_large_fp_zone(inspection_service, inside_fp):
    settings = replace(SETTINGS, enable_clahe=False, min_diff_signal=10.57088, min_defect_area=5)
    reference = np.full((480, 640, 3), 130, np.uint8)
    current = reference.copy()
    cv2.circle(current, (320, 240), 14, (90, 90, 90), -1)
    product = "weak-compact-in-fp"
    inspection_service.set_reference_frame(product, reference)
    if inside_fp:
        inspection_service.add_fp_zone(
            product, [(0., 0.), (1., 0.), (1., 1.), (0., 1.)], 640, 480,
            aligned=reference, reference_hash=reference_fingerprint(reference),
        )
    result = inspection_service.inspect_frame(
        product, current, settings=settings, threshold=.25,
        alignment_h_ref_to_cur=[1., 0., 0., 0., 1., 0., 0., 0., 1.],
        include_visuals=True, store_learning_review=False,
    )
    assert result.anomaly_score > .25
    assert result.status == "БРАК"
    assert np.any(result.segmentation_mask[226:255, 306:335])
    if inside_fp:
        assert result.rechecked_zones_count == 1


def test_weak_normal_can_be_accepted_without_hiding_a_separate_weak_defect(inspection_service):
    service = inspection_service
    product = "weak-normal-plus-new-defect"
    settings = replace(SETTINGS, enable_clahe=False, min_diff_signal=10.57088, min_defect_area=5)
    service.update_analysis_settings(product, settings.to_dict())
    reference = np.full((180, 260, 3), 130, np.uint8)
    normal = reference.copy()
    cv2.circle(normal, (60, 80), 14, (90, 90, 90), -1)
    service.set_reference_frame(product, reference)
    kwargs = dict(settings=settings, threshold=.25, include_visuals=True,
                  alignment_h_ref_to_cur=[1., 0., 0., 0., 1., 0., 0., 0., 1.])
    first = service.inspect_frame(product, normal, **kwargs)
    assert first.status == "БРАК"
    review = service.get_learning_review(first.inspection_id)
    assert review["defects"]
    service.accept_all_review_defects_as_normal(first.inspection_id)
    accepted = service.inspect_frame(product, normal, **kwargs)
    assert accepted.status == "ГОДЕН"
    changed = normal.copy()
    cv2.circle(changed, (195, 80), 14, (90, 90, 90), -1)
    result = service.inspect_frame(product, changed, **kwargs)
    assert result.status == "БРАК"
    assert result.anomaly_score > .25
    assert np.any(result.segmentation_mask[66:95, 181:210])


def test_saved_195_defects_gain_local_evidence_without_boosting_rim_and_printing():
    fixture = Path(__file__).resolve().parents[1] / "test_data/compact_residuals/residuals.npz"
    settings = replace(SETTINGS, min_diff_signal=10.57088, min_defect_area=5)
    with np.load(fixture, allow_pickle=False) as cases:
        for name in ("defect_cam1_195", "defect_cam3_195"):
            score, mask = evidence(cases[name], settings)
            assert score > .25, name
            assert np.any(mask), name
        for name in ("rim_cam6_192", "printing_cam0_206", "printing_cam4_196"):
            assert evidence(cases[name], settings)[0] < .25, name
