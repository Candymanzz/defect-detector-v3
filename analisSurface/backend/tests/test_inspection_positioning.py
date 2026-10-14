import cv2
import numpy as np

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


def test_small_rotation_and_shift_is_locked_to_reference():
    ref = _textured()
    cur = _transform(ref, 0.1, 5.0, -3.0)
    result = FramePositioner().position(cur, ref, product_type="t")
    assert result.matrix is not None, result.diagnostics
    inner = (slice(40, -40), slice(40, -40))
    before = float(np.mean(cv2.absdiff(cur, ref)[inner]))
    after = float(np.mean(cv2.absdiff(result.aligned, ref)[inner]))
    assert after < before * 0.25


def test_void_zone_is_excluded_on_the_side_that_lost_content():
    ref = _textured()
    cur = _transform(ref, 0.0, 6.0, 0.0)  # content moved right -> left strip of current is empty
    result = FramePositioner().position(cur, ref, product_type="t")
    assert result.matrix is not None
    # aligned back: right strip of the reference has no source pixels in current
    assert (result.valid_mask[:, -3:] == 0).all()
    assert (result.valid_mask[:, 20:-20] == 255).all() or (result.valid_mask[:, 20:-40] == 255).all()
    assert len(result.dropped_polygon) == 4


def test_identity_frame_has_full_valid_mask():
    ref = _textured()
    result = FramePositioner().position(ref.copy(), ref, product_type="t")
    assert (result.valid_mask == 255).all() or result.valid_mask[10:-10, 10:-10].min() == 255


def test_featureless_frame_falls_back_without_mask():
    ref = np.full((240, 320, 3), 90, np.uint8)
    result = FramePositioner().position(ref.copy(), ref, product_type="flat")
    assert result.matrix is None
    assert (result.valid_mask == 255).all()
