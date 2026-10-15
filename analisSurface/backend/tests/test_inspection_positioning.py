import cv2
import numpy as np
import pytest

from app.services.inspection_positioning import FramePositioner


def _textured(width=640, height=480, seed=3):
    rng = np.random.default_rng(seed)
    base = rng.integers(0, 255, (height // 8, width // 8, 3), dtype=np.uint8)
    img = cv2.resize(base, (width, height), interpolation=cv2.INTER_CUBIC)
    return cv2.GaussianBlur(img, (0, 0), 1.2)


def _transform(img, angle_deg, dx, dy):
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle_deg, 1.0)
    m[:, 2] += (dx, dy)
    return cv2.warpAffine(img, m, (w, h), borderMode=cv2.BORDER_CONSTANT, borderValue=0)


@pytest.mark.parametrize("dx,dy", [(5, -3), (-7, 4), (0.7, -1.3), (12, 0)])
def test_small_shift_is_locked_to_reference(dx, dy):
    ref = _textured()
    cur = _transform(ref, 0.0, dx, dy)
    result = FramePositioner().position(cur, ref, product_type="t")
    assert result.matrix is not None, result.diagnostics
    inner = (slice(40, -40), slice(40, -40))
    before = float(np.mean(cv2.absdiff(cur, ref)[inner]))
    after = float(np.mean(cv2.absdiff(result.aligned, ref)[inner]))
    assert after < before * 0.25
    assert np.allclose(result.matrix[:, :2], np.eye(2))
    assert abs(result.matrix[0, 2] + dx) < 0.4
    assert abs(result.matrix[1, 2] + dy) < 0.4


def test_no_positioning_strips_are_masked_or_replaced_with_reference():
    ref = _textured()
    cur = _transform(ref, 0.0, 6.0, 0.0)  # content moved right -> left strip of current is empty
    result = FramePositioner().position(cur, ref, product_type="t")
    assert result.matrix is not None
    expected = cv2.warpAffine(cur, result.matrix, (ref.shape[1], ref.shape[0]),
                              borderMode=cv2.BORDER_REPLICATE)
    assert np.array_equal(result.aligned, expected)
    assert not np.array_equal(result.aligned[:, -3:], ref[:, -3:])
    assert result.valid_mask is None
    assert result.dropped_polygon == []


def test_identity_frame_skips_warp_and_masks():
    ref = _textured()
    cur = ref.copy()
    result = FramePositioner().position(cur, ref, product_type="t")
    assert result.matrix is None
    assert result.aligned is cur
    assert result.valid_mask is None


def test_featureless_frame_falls_back_without_mask():
    ref = np.full((240, 320, 3), 90, np.uint8)
    result = FramePositioner().position(ref.copy(), ref, product_type="flat")
    assert result.matrix is None
    assert result.valid_mask is None
    assert result.diagnostics["status"] == "low_texture"


def test_large_shift_is_not_guessed_or_clamped():
    ref = _textured()
    cur = _transform(ref, 0, 70, 0)
    result = FramePositioner().position(cur, ref)
    assert result.matrix is None
    assert result.aligned is cur


def test_positioning_preserves_a_new_defect():
    ref = _textured()
    defective = ref.copy()
    defective[200:220, 300:325] = 0
    cur = _transform(defective, 0, 5, -3)
    result = FramePositioner().position(cur, ref)
    assert result.matrix is not None
    assert result.aligned[203:217, 303:322].max() < 10
    assert cv2.absdiff(result.aligned, ref)[200:220, 300:325].mean() > 50


def test_reference_and_roi_changes_invalidate_position_cache():
    positioner = FramePositioner()
    ref = _textured()
    positioner.position(ref, ref, product_type="t")
    prepared = positioner._cache["t"]
    snapshot = prepared.windowed.copy()
    positioner.position(_transform(ref, 0, 4, 2), ref, product_type="t")
    assert positioner._cache["t"] is prepared
    assert np.array_equal(prepared.windowed, snapshot)
    positioner.position(ref, ref, product_type="t", roi_polygon=[(.2,.2),(.8,.2),(.8,.8),(.2,.8)])
    assert positioner._cache["t"] is not prepared
    other = _textured(seed=8)
    result = positioner.position(_transform(other, 0, -4, 2), other, product_type="t")
    assert result.matrix is not None
    assert abs(result.matrix[0, 2] - 4) < .4
