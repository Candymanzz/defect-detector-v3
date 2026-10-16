"""Equivalent geometry/reference operations used by production inspection."""
import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_geometry import (
    cached_polygon_mask, cached_region_mask, combine_region_masks, polygon_mask_from_norm_points,
)
from app.services.inspection_evidence import pre_normalization_compact_signal
from app.services.inspection_service import InspectionService
from app.services.reference_preparation import prepare_illumination, prepare_raw_compact


def test_cached_masks_are_exact_immutable_and_follow_geometry_changes():
    polygon = [(0.1, 0.1), (.9, .1), (.8, .9), (.2, .9)]
    hole = [(.3, .3), (.6, .3), (.5, .7)]
    np.testing.assert_array_equal(cached_polygon_mask(160,120,polygon), polygon_mask_from_norm_points(160,120,polygon))
    mask = cached_region_mask(160,120,polygon,[hole])
    np.testing.assert_array_equal(mask, combine_region_masks(160,120,polygon,[hole]))
    with pytest.raises(ValueError): mask[0,0] = True
    polygon[0] = (.4,.1)
    np.testing.assert_array_equal(cached_region_mask(160,120,polygon,[hole]), combine_region_masks(160,120,polygon,[hole]))
    assert cached_region_mask(80,60,polygon,[hole]).shape == (60,80)


@pytest.mark.parametrize("seed", range(6))
def test_cached_raw_reference_and_illumination_preserve_current_frame(seed):
    rng = np.random.default_rng(seed)
    reference = rng.integers(10,230,(120,160),dtype=np.uint8)
    current = np.clip(reference.astype(np.int16)+rng.integers(-20,21,reference.shape),0,255).astype(np.uint8)
    cv2.circle(current,(70,60),10,30,-1)
    settings = AnalysisSettings(use_patchcore=False)
    expected = pre_normalization_compact_signal(reference,current,settings)
    actual = pre_normalization_compact_signal(reference,current,settings,prepared_reference=prepare_raw_compact(reference))
    np.testing.assert_array_equal(actual,expected)
    first, second = np.empty_like(current),np.empty_like(current)
    diag_before, diag_after = {},{}
    original = InspectionService._suppress_smooth_illumination(
        cv2.absdiff(current,reference),reference,current,normalized_current=first,diagnostics=diag_before)
    optimized = InspectionService._suppress_smooth_illumination(
        cv2.absdiff(current,reference),reference,current,normalized_current=second,diagnostics=diag_after,
        prepared_reference=prepare_illumination(reference),return_corrected=False)
    assert optimized[0] is None
    np.testing.assert_array_equal(first,second)
    # OpenCV SIMD magnitude can differ by one float ULP with array alignment.
    # Pixels/diagnostics remain exact; confidence is an internal float32 field.
    np.testing.assert_allclose(original[1],optimized[1],rtol=1e-6,atol=1e-12)
    assert diag_before == diag_after
