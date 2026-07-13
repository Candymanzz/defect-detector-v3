from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_root_health() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ok"
    assert payload["service"] == "kopcheni-service"
    assert "detector_id" in payload


def test_detector_health() -> None:
    response = client.get("/detector/health")
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"] == "ok"
    assert payload["service"] == "analisSurface"
    assert "detector_id" in payload
