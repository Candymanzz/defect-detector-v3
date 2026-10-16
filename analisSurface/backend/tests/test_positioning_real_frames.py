import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.inspection_positioning import FramePositioner

DATA = Path(__file__).resolve().parent.parent / "test_data" / "positioning_shift"
MANIFEST = DATA / "manifest.json"

pytestmark = pytest.mark.skipif(not MANIFEST.exists(), reason="real frame set is missing")


def _items():
    return json.loads(MANIFEST.read_text(encoding="utf-8"))["items"] if MANIFEST.exists() else []


def _grad(image):
    gray = cv2.GaussianBlur(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), (0, 0), 1.5).astype(np.float32)
    return cv2.magnitude(cv2.Sobel(gray, cv2.CV_32F, 1, 0), cv2.Sobel(gray, cv2.CV_32F, 0, 1))


def _mean_diff(a, b, valid=None, margin=40):
    """Разница градиентов: не зависит от разницы яркости между кадрами."""
    d = np.abs(_grad(a) - _grad(b))
    sel = np.zeros(d.shape, bool)
    sel[margin:-margin, margin:-margin] = True
    if valid is not None:
        sel &= valid > 0
    return float(d[sel].mean())


@pytest.mark.parametrize("item", _items(), ids=lambda i: i["frame"])
def test_real_frame_is_locked_to_reference(item):
    if not all((DATA / item[key]).is_file() for key in ("reference", "frame")):
        pytest.skip("real frame images are not included in the repository")
    ref = cv2.imread(str(DATA / item["reference"]))
    cur = cv2.imread(str(DATA / item["frame"]))
    result = FramePositioner().position(cur, ref, product_type=item["frame"])
    assert result.matrix is not None, result.diagnostics
    assert abs(result.diagnostics["shift_x_px"] - item["measured_shift_px"][0]) < 1.0
    assert abs(result.diagnostics["shift_y_px"] - item["measured_shift_px"][1]) < 1.0
    assert _mean_diff(result.aligned, ref, result.valid_mask) < _mean_diff(cur, ref)
    assert result.valid_mask is None
