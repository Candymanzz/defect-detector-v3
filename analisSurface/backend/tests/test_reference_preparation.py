from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
import threading

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_service import InspectionService, _LOCAL_ENVELOPE_KERNEL, _EDGE_ZONE_KERNEL
from app.services.reference_preparation import ReferencePreparationCache, prepare_features


def _service(cache):
    service = InspectionService.__new__(InspectionService)
    service._reference_preparation = cache
    return service


@pytest.mark.parametrize("clip", [None, 1.2, 2.0])
def test_cached_features_match_original_operations(clip):
    gray = np.random.default_rng(8).integers(0, 256, (90, 120), np.uint8)
    prepared = prepare_features(gray, clip, True, _LOCAL_ENVELOPE_KERNEL, _EDGE_ZONE_KERNEL)
    expected = gray if clip is None else cv2.createCLAHE(clipLimit=clip, tileGridSize=(8, 8)).apply(gray)
    fine = cv2.GaussianBlur(expected, (3, 3), 0)
    expected = cv2.GaussianBlur(expected, (5, 5), 0)
    assert np.array_equal(prepared["gray"], expected)
    assert np.array_equal(prepared["detail_max"], cv2.dilate(fine, _LOCAL_ENVELOPE_KERNEL))
    assert np.array_equal(prepared["edges"], cv2.dilate(cv2.Canny(expected, 80, 160), _EDGE_ZONE_KERNEL, iterations=2))
    # SIMD float magnitude can differ by one ULP with memory alignment.
    np.testing.assert_allclose(prepared["gradient"], cv2.magnitude(cv2.Sobel(expected, cv2.CV_32F, 1, 0),
                                                               cv2.Sobel(expected, cv2.CV_32F, 0, 1)), rtol=1e-6)


def test_warm_cache_exactly_matches_disabled_with_changed_reference_roi_and_settings():
    cached = _service(ReferencePreparationCache())
    uncached = _service(ReferencePreparationCache(max_bytes=0))
    rng = np.random.default_rng(4)
    reference = rng.integers(10, 245, (120, 160, 3), dtype=np.uint8)
    current = reference.copy()
    current[25:60, 30:90] = 80
    settings = AnalysisSettings()
    for clip in [1.0, 1.2, 2.0, 1.2]:
        for region in [(slice(None), slice(None)), (slice(5, 100), slice(10, 140))]:
            for ref in [reference, np.roll(reference, 3, axis=0)]:
                for light in [False, True]:
                    kwargs = dict(settings=replace(settings, clahe_clip_limit=clip), vertical_compensation=light)
                    expected = uncached._compute_advanced_difference(current[region], ref[region], **kwargs)
                    for _ in range(2):
                        actual = cached._compute_advanced_difference(current[region], ref[region], **kwargs)
                        assert np.array_equal(actual, expected)


def test_memory_is_bounded_lru_and_cached_arrays_are_immutable():
    cache = ReferencePreparationCache(max_bytes=20)
    first = cache.get((1,), lambda: {"a": np.zeros(10, np.uint8)})
    cache.get((2,), lambda: {"a": np.ones(10, np.uint8)})
    assert cache.get((1,), lambda: pytest.fail("cache miss")) is first
    cache.get((3,), lambda: {"a": np.ones(10, np.uint8)})
    assert (2,) not in cache._entries
    assert cache._bytes == 20
    with pytest.raises(ValueError):
        first["a"][0] = 3
    cache.clear()
    assert cache._bytes == 0


def test_cache_does_not_serialize_reference_preparation():
    cache = ReferencePreparationCache()
    barrier = threading.Barrier(2)
    def build():
        barrier.wait(timeout=2)
        return {"a": np.zeros(10, np.uint8)}
    with ThreadPoolExecutor(2) as executor:
        futures = [executor.submit(cache.get, (1,), build) for _ in range(2)]
        assert futures[0].result() is futures[1].result()
