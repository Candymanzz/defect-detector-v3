from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_analysis_settings_defaults() -> None:
    response = client.get("/analysis-settings/defaults")
    assert response.status_code == 200
    payload = response.json()
    assert payload["analysis_profile"] == "_defaults"
    assert payload["settings"]["default_threshold"] == 0.25
    assert "detector_id" in payload


def test_analysis_settings_update_and_reset() -> None:
    profile = "test-profile-unit"

    update = client.put(
        f"/analysis-settings/{profile}",
        json={"default_threshold": 0.35, "min_defect_area": 10},
    )
    assert update.status_code == 200
    assert update.json()["settings"]["default_threshold"] == 0.35
    assert update.json()["settings"]["min_defect_area"] == 10

    get_response = client.get(f"/analysis-settings/{profile}")
    assert get_response.status_code == 200
    assert get_response.json()["overrides"]["default_threshold"] == 0.35

    reset = client.delete(f"/analysis-settings/{profile}")
    assert reset.status_code == 200
    assert reset.json()["overrides"] == {}


def test_analysis_settings_update_requires_payload() -> None:
    response = client.put("/analysis-settings/empty", json={})
    assert response.status_code == 400
