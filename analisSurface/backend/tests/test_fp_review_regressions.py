"""FP memory must preserve defects and use the inspection being reviewed."""

import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import polygon_mask_from_norm_points
from app.services.learned_normals import decode_review_arrays


@pytest.mark.parametrize("invalid_reason", ["reference_changed", "unavailable_comparison"])
def test_unchecked_fp_zone_does_not_erase_a_defect(inspection_service, monkeypatch, invalid_reason):
    service = inspection_service
    current = np.full((120, 160, 3), 120, np.uint8)
    points = [(0.2, 0.2), (0.6, 0.2), (0.6, 0.6), (0.2, 0.6)]
    zone = service.add_fp_zone(
        "fp-regression", points, 160, 120, aligned=current, reference_hash="old",
    )
    diff = np.zeros_like(current)
    diff[35:55, 45:65] = 120
    settings = AnalysisSettings.from_overrides({"use_patchcore": False})
    score, mask = service._run_anomaly_model(diff, settings)
    if invalid_reason == "unavailable_comparison":
        monkeypatch.setattr(service, "_polygon_region_difference", lambda *args, **kwargs: None)
    result = service._recheck_fp_zones(
        "fp-regression", current, current, diff, mask, score, settings,
        reference_hash="new" if invalid_reason == "reference_changed" else "old",
    )
    np.testing.assert_array_equal(result["filtered_diff_map"], diff)
    assert result["final_score"] >= .387
    assert result["fp_zone_scores"][0].zone_id == zone.id
    assert result["fp_zone_scores"][0].status == "SKIPPED"
    assert not result["fp_zone_scores"][0].applied_fp_etalon


def test_accepting_older_review_uses_its_image_for_fp_crop(inspection_service):
    service = inspection_service
    product = "fp-history-regression"
    reference = np.full((120, 160, 3), 40, np.uint8)
    old_frame = reference.copy()
    old_frame[20:50, 20:70] = 230
    service.set_reference_frame(product, reference)
    settings = AnalysisSettings.from_overrides({"use_patchcore": False})
    old_result = service.inspect_frame(product, old_frame, settings=settings, include_visuals=False)
    review = service._learning_reviews.get(old_result.inspection_id)
    assert review.defects
    reviewed_frame, _, _ = decode_review_arrays(review)

    service.inspect_frame(product, np.full_like(reference, 90), settings=settings, include_visuals=False)
    accepted = service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    zone = service.get_fp_zone(accepted["fp_zone_ids"][0])
    x, y, width, height = zone.crop_bbox
    np.testing.assert_array_equal(zone.fp_crop, reviewed_frame[y:y + height, x:x + width])


def test_overlapping_fp_zones_preserve_defect_regardless_of_order(inspection_service, monkeypatch):
    service = inspection_service
    reference = np.full((160, 200, 3), 120, np.uint8)
    current = reference.copy()
    current[65:95, 90:93] = 20
    alternate_normal = np.full_like(reference, 160)
    alternate_normal[65:95, 90:93] = 20
    points = [(0.3, 0.3), (0.7, 0.3), (0.7, 0.7), (0.3, 0.7)]
    zones = [service.add_fp_zone(
        "overlap", points, 200, 160, aligned=frame,
    ) for frame in (reference, alternate_normal)]
    settings = AnalysisSettings.from_overrides({"use_patchcore": False, "enable_clahe": False})
    diff = service._compute_advanced_difference(current, reference, settings)
    raw_score, mask = service._run_anomaly_model(diff, settings)
    results = []
    for order in (zones, zones[::-1]):
        monkeypatch.setattr(service, "get_fp_zones", lambda product, order=order: order)
        result = service._recheck_fp_zones(
            "overlap", current, reference, diff, mask, raw_score, settings,
            inspection_threshold=.387,
        )
        assert result["final_score"] >= .387
        results.append(result)
    np.testing.assert_array_equal(results[0]["filtered_diff_map"], results[1]["filtered_diff_map"])
    np.testing.assert_array_equal(results[0]["filtered_mask"], results[1]["filtered_mask"])
    assert results[0]["final_score"] == pytest.approx(results[1]["final_score"])


@pytest.mark.parametrize("level", [0, 20, 40])
def test_fp_border_does_not_suppress_cut_outside_polygon(inspection_service, level):
    service = inspection_service
    reference = np.full((160, 200, 3), 120, np.uint8)
    current = reference.copy()
    current[60:85, 58:60] = level
    points = [(0.3, 0.3), (0.7, 0.3), (0.7, 0.7), (0.3, 0.7)]
    service.add_fp_zone("border", points, 200, 160, aligned=reference)
    settings = AnalysisSettings.from_overrides({"use_patchcore": False, "enable_clahe": False})
    diff = service._compute_advanced_difference(current, reference, settings)
    raw_score, mask = service._run_anomaly_model(diff, settings)
    result = service._recheck_fp_zones(
        "border", current, reference, diff, mask, raw_score, settings,
        inspection_threshold=.387,
    )
    outside = polygon_mask_from_norm_points(200, 160, points) == 0
    np.testing.assert_array_equal(result["filtered_diff_map"][outside], diff[outside])
    assert result["final_score"] >= .387


def test_tightly_drawn_matching_fp_zone_removes_verified_halo(inspection_service):
    service = inspection_service
    reference = np.full((120, 160, 3), 40, np.uint8)
    normal = reference.copy()
    normal[20:50, 20:70] = 255
    points = [(20 / 159, 20 / 119), (69 / 159, 20 / 119),
              (69 / 159, 49 / 119), (20 / 159, 49 / 119)]
    service.add_fp_zone("tight", points, 160, 120, aligned=normal)
    settings = AnalysisSettings.from_overrides({"use_patchcore": False, "enable_clahe": False})
    diff = service._compute_advanced_difference(normal, reference, settings, vertical_compensation=True)
    raw_score, mask = service._run_anomaly_model(diff, settings)
    assert raw_score >= .387
    result = service._recheck_fp_zones("tight", normal, reference, diff, mask, raw_score, settings)
    assert result["fp_zone_scores"][0].residual_score == 0
    assert result["final_score"] == 0
    assert not np.any(result["filtered_mask"])


@pytest.mark.parametrize("side", ["left", "right", "top", "bottom"])
@pytest.mark.parametrize("gap", [0, 2])
def test_new_cut_beside_tight_fp_zone_keeps_full_frame_response(inspection_service, side, gap):
    service = inspection_service
    reference = np.full((120, 160, 3), 120, np.uint8)
    normal = reference.copy()
    normal[30:80, 40:100] = 255
    points = [(40 / 159, 30 / 119), (99 / 159, 30 / 119),
              (99 / 159, 79 / 119), (40 / 159, 79 / 119)]
    service.add_fp_zone("tight-cut", points, 160, 120, aligned=normal)
    current = normal.copy()
    cuts = {"left": (slice(40, 70), slice(38 - gap, 40 - gap)),
            "right": (slice(40, 70), slice(100 + gap, 102 + gap)),
            "top": (slice(28 - gap, 30 - gap), slice(50, 90)),
            "bottom": (slice(80 + gap, 82 + gap), slice(50, 90))}
    current[cuts[side]] = 0
    settings = AnalysisSettings.from_overrides({"use_patchcore": False, "enable_clahe": False})
    diff = service._compute_advanced_difference(current, reference, settings, vertical_compensation=True)
    raw_score, mask = service._run_anomaly_model(diff, settings)
    result = service._recheck_fp_zones("tight-cut", current, reference, diff, mask, raw_score, settings)
    np.testing.assert_array_equal(result["filtered_diff_map"][cuts[side]], diff[cuts[side]])
    assert result["final_score"] >= .387
