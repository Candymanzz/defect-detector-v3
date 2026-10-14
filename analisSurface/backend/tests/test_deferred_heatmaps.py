from types import SimpleNamespace

import numpy as np
from fastapi.testclient import TestClient

from app.api import inspection_routes
from app.main import app
from app.services.deferred_heatmaps import DeferredHeatmapCache
from app.services.inspection_models import InspectionResult


def snapshot(value=100):
    mask = np.ones((12, 16), dtype=np.uint8) * 255
    diff = np.ones((12, 16), dtype=np.float32) * value
    return InspectionResult(
        product_type="bench#cam=0", status="БРАК", anomaly_score=0.9, threshold=0.5,
        deferred_heatmap_data=(mask, diff, None),
    )


def test_cache_bounds_memory_and_expires_without_read_refresh():
    now = [0.0]
    result = snapshot()
    size = sum(x.nbytes for x in result.deferred_heatmap_data[:2])
    cache = DeferredHeatmapCache(max_bytes=size, ttl_seconds=5, clock=lambda: now[0])
    first = cache.put(result)
    second = cache.put(snapshot(20))
    assert cache.get(first) is None
    assert cache.get(second).anomaly_score == 0.9
    now[0] = 4.0
    assert cache.get(second) is not None
    now[0] = 5.0
    assert cache.get(second) is None


def test_deferred_route_renders_same_result_without_reinspection(tmp_path, monkeypatch):
    monkeypatch.setattr("app.services.shm_io.sys", SimpleNamespace(platform="win32"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    cache = DeferredHeatmapCache()
    monkeypatch.setattr(inspection_routes, "deferred_heatmaps", cache)
    result = snapshot()
    # The ROI belongs to this result, even if operator settings later change.
    result.deferred_heatmap_data = (*result.deferred_heatmap_data[:2], ((0, 0), (0.5, 0), (0.5, 1), (0, 1)))
    def no_inspection(*args, **kwargs):
        raise AssertionError("rendering must not run inspection again")
    monkeypatch.setattr(inspection_routes.inspection_service, "inspect_frame", no_inspection)
    monkeypatch.setattr(inspection_routes.inspection_service, "get_roi_polygon", no_inspection)
    token = cache.put(result)
    output = tmp_path / "iml_shm" / "heatmap"
    client = TestClient(app)
    response = client.post("/render-heatmap", json={
        "token": token, "heatmap_u8_output_path": str(output), "heatmap_max_width": 8,
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["anomaly_score"] == result.anomaly_score
    assert body["status"] == result.status
    assert body["heatmap_u8"]["width"] == 8
    heatmap = np.frombuffer(output.read_bytes(), dtype=np.uint8).reshape(6, 8)
    assert heatmap[:, 6:].max() == 0
    assert heatmap[:, :3].max() > 0
    # Idempotent rendering supports a transport retry without a new verdict.
    assert client.post("/render-heatmap", json={
        "token": token, "heatmap_u8_output_path": str(output),
    }).status_code == 200
    assert client.post("/render-heatmap", json={
        "token": "expired", "heatmap_u8_output_path": str(output),
    }).status_code == 410


def test_primary_response_retains_snapshot_without_building_heatmap(monkeypatch):
    cache = DeferredHeatmapCache()
    monkeypatch.setattr(inspection_routes, "deferred_heatmaps", cache)
    result = snapshot()
    async def inspect(payload, **kwargs):
        assert payload.defer_heatmap
        assert not kwargs["include_heatmap_u8"]
        return result
    monkeypatch.setattr(inspection_routes, "_inspect_shm_parallel", inspect)
    response = TestClient(app).post("/inspect-shm", json={
        "product_type": result.product_type, "shm_name": "unused", "width": 16, "height": 12,
        "defer_heatmap": True,
    })
    assert response.status_code == 200, response.text
    assert response.json()["heatmap_u8"] is None
    assert cache.get(response.json()["deferred_heatmap_token"]) is result
