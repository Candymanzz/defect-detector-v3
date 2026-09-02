from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

from app.api.dependencies import inspection_service
from app.api.inspection_routes import (
    _inspect_shm_sync,
    _settings_from_test_knobs,
    load_test_frame_bgr,
    reset_test_frame_bgr_cache,
)
from app.api.schemas import ShmFrameRequest, TestFrameInspectRequest as InspectTestFrameBody
from app.main import app
from app.services.analysis_settings_presets import expand_merged


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


def test_inspect_test_frame_accepts_legacy_pro_knobs() -> None:
    payload = InspectTestFrameBody(
        cache_key="pro-preview",
        file_path="frame.jpg",
        product_type="pro-preview",
        pro={
            "threshold": 0.37,
            "noise_tolerance": 10,
            "scratch_sensitivity": 85,
            "edge_suppression": 30,
            "text_handling": 65,
            "preprocess_strength": 90,
        },
    )

    settings = _settings_from_test_knobs(payload)
    expected = expand_merged(
        0.37,
        0.5,
        noise_tolerance=10,
        scratch_sensitivity=85,
        edge_suppression=30,
        text_handling=65,
        preprocess_strength=90,
    )
    assert settings.default_threshold == expected["default_threshold"]
    assert settings.min_diff_signal == expected["min_diff_signal"]
    assert settings.min_scratch_aspect == expected["min_scratch_aspect"]


def test_inspect_shm_applies_temporary_pro_knobs(monkeypatch) -> None:
    captured = {}
    frame = np.zeros((8, 8, 3), dtype=np.uint8)
    monkeypatch.setitem(
        inspection_service._analysis_settings_simple_knobs,
        "pro-live",
        {"threshold": 0.25, "sensitivity": 0.9},
    )

    monkeypatch.setattr("app.api.inspection_routes._copy_shm_bgr_frame", lambda payload: frame)

    def fake_inspect_frame(**kwargs):
        captured.update(kwargs)
        return "ok"

    monkeypatch.setattr(inspection_service, "inspect_frame", fake_inspect_frame)
    payload = ShmFrameRequest(
        product_type="pro-live",
        shm_name="unused",
        width=8,
        height=8,
        analysis_test_settings={
            "mode": "pro",
            "knobs": {
                "threshold": 0.42,
                "noise_tolerance": 15,
                "scratch_sensitivity": 90,
                "edge_suppression": 35,
                "text_handling": 70,
                "preprocess_strength": 80,
            },
        },
    )

    assert _inspect_shm_sync(payload, include_visuals=False, include_heatmap_u8=False) == "ok"
    overrides = captured["temporary_analysis_overrides"]
    assert overrides["default_threshold"] == 0.42
    assert overrides["min_diff_signal"] != 12.0


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


def test_inspect_test_frame_passes_inspect_scale(tmp_path: Path, monkeypatch) -> None:
    reset_test_frame_bgr_cache()
    jpeg = tmp_path / "frame.jpg"
    frame = np.full((1024, 1224, 3), 40, dtype=np.uint8)
    assert cv2.imwrite(str(jpeg), frame)
    inspection_service.set_reference_frame("scale-test", frame.copy())

    captured: dict[str, object] = {}
    original = inspection_service.inspect_frame

    def wrapping_inspect_frame(**kwargs):
        captured.update(kwargs)
        return original(**kwargs)

    monkeypatch.setattr(inspection_service, "inspect_frame", wrapping_inspect_frame)
    response = client.post(
        "/inspect-test-frame",
        json={
            "cache_key": "2:100",
            "file_path": str(jpeg),
            "product_type": "scale-test",
            "analysis_profile": "scale-test",
            "simple": {"threshold": 0.25, "sensitivity": 0.5},
            "inspect_scale": 0.5,
        },
    )
    assert response.status_code == 200, response.text
    assert captured.get("inspect_scale_after_align") == 0.5
