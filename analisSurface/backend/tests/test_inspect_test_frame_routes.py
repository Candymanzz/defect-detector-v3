from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

from app.api.dependencies import inspection_service
from app.api.inspection_routes import load_test_frame_bgr, reset_test_frame_bgr_cache
from app.api.schemas import TestFrameInspectRequest as InspectTestFrameBody
from app.main import app


client = TestClient(app)


def _write_jpeg(path: Path, value: int) -> None:
    image = np.full((48, 64, 3), value, dtype=np.uint8)
    assert cv2.imwrite(str(path), image)


def test_inspect_test_frame_cache_hit_skips_reread(tmp_path: Path, monkeypatch) -> None:
    reset_test_frame_bgr_cache()
    jpeg = tmp_path / "frame.jpg"
    _write_jpeg(jpeg, 40)
    reads = {"count": 0}
    original = cv2.imread

    def counting_imread(path, flags=None):
        reads["count"] += 1
        return original(path, flags)

    monkeypatch.setattr("app.api.inspection_routes.cv2.imread", counting_imread)
    payload = InspectTestFrameBody(
        cache_key="0:42",
        file_path=str(jpeg),
        image_url="/api/frame-archive/cameras/0/frames/42/frame.jpg",
        product_type="bench",
        simple={"threshold": 0.25, "sensitivity": 0.5},
    )
    first = load_test_frame_bgr(payload)
    second = load_test_frame_bgr(payload)
    assert reads["count"] == 1
    assert first is second


def test_inspect_test_frame_new_cache_key_rereads(tmp_path: Path, monkeypatch) -> None:
    reset_test_frame_bgr_cache()
    jpeg = tmp_path / "frame.jpg"
    _write_jpeg(jpeg, 80)
    reads = {"count": 0}
    original = cv2.imread

    def counting_imread(path, flags=None):
        reads["count"] += 1
        return original(path, flags)

    monkeypatch.setattr("app.api.inspection_routes.cv2.imread", counting_imread)
    first = InspectTestFrameBody(
        cache_key="0:1",
        file_path=str(jpeg),
        product_type="bench",
        simple={"threshold": 0.25, "sensitivity": 0.5},
    )
    second = InspectTestFrameBody(
        cache_key="0:2",
        file_path=str(jpeg),
        product_type="bench",
        simple={"threshold": 0.25, "sensitivity": 0.5},
    )
    load_test_frame_bgr(first)
    load_test_frame_bgr(second)
    assert reads["count"] == 2


def test_inspect_test_frame_does_not_persist_knobs(tmp_path: Path) -> None:
    reset_test_frame_bgr_cache()
    jpeg = tmp_path / "frame.jpg"
    _write_jpeg(jpeg, 90)
    frame = cv2.imread(str(jpeg))
    inspection_service.set_reference_frame("local-test", frame)
    inspection_service._analysis_settings_file = tmp_path / "analysis_settings.json"
    inspection_service._analysis_settings_overrides = {}
    inspection_service._analysis_settings_simple_knobs = {}
    before = inspection_service.get_analysis_settings_overrides("local-test")

    response = client.post(
        "/inspect-test-frame",
        json={
            "cache_key": "7:9",
            "file_path": str(jpeg),
            "image_url": "/api/frame-archive/cameras/7/frames/9/frame.jpg",
            "product_type": "local-test",
            "analysis_profile": "local-test",
            "simple": {"threshold": 0.41, "sensitivity": 0.2},
        },
    )
    assert response.status_code == 200, response.text
    after = inspection_service.get_analysis_settings_overrides("local-test")
    assert after == before
    assert inspection_service.get_simple_knobs("local-test") is None


def test_inspect_test_frame_resizes_to_reference_resolution(tmp_path: Path) -> None:
    reset_test_frame_bgr_cache()
    reference = np.full((96, 128, 3), 30, dtype=np.uint8)
    inspection_service.set_reference_frame("resize-test", reference)
    small = tmp_path / "small.jpg"
    assert cv2.imwrite(str(small), np.full((24, 32, 3), 200, dtype=np.uint8))

    seen: dict[str, tuple[int, int]] = {}
    original = inspection_service.inspect_frame

    def wrapping_inspect_frame(**kwargs):
        seen["shape"] = kwargs["frame"].shape[:2]
        return original(**kwargs)

    inspection_service.inspect_frame = wrapping_inspect_frame  # type: ignore[method-assign]
    try:
        response = client.post(
            "/inspect-test-frame",
            json={
                "cache_key": "0:99",
                "file_path": str(small),
                "product_type": "resize-test",
                "analysis_profile": "resize-test",
                "simple": {"threshold": 0.25, "sensitivity": 0.5},
            },
        )
        assert response.status_code == 200, response.text
        assert seen["shape"] == (96, 128)
    finally:
        inspection_service.inspect_frame = original  # type: ignore[method-assign]
        inspection_service.clear_inspection_context()
