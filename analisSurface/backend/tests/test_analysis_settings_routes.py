from fastapi.testclient import TestClient
import pytest

from app.main import app


client = TestClient(app)
pytestmark = pytest.mark.usefixtures("isolated_analysis_settings_api")


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


def test_edge_suppression_flag_survives_simple_and_strength_saves() -> None:
    profile = "test-profile-edge-flag"
    client.put(f"/analysis-settings/{profile}/simple", json={"threshold": 0.3, "sensitivity": 0.6})

    assert client.get(f"/analysis-settings/{profile}").json()["settings"]["enable_edge_suppression"] is True

    off = client.put(f"/analysis-settings/{profile}", json={"enable_edge_suppression": False})
    assert off.status_code == 200
    assert off.json()["settings"]["enable_edge_suppression"] is False
    # Флаг не сбрасывает simple-knobs.
    assert off.json()["simple_knobs"] == {"threshold": 0.3, "sensitivity": 0.6}

    client.put(f"/analysis-settings/{profile}/simple", json={"threshold": 0.2, "sensitivity": 0.4})
    client.put(
        f"/analysis-settings/{profile}/strengths",
        json={
            "noise_tolerance": 60,
            "scratch_sensitivity": 60,
            "edge_suppression": 60,
            "text_handling": 60,
            "preprocess_strength": 60,
        },
    )
    after = client.get(f"/analysis-settings/{profile}").json()
    assert after["settings"]["enable_edge_suppression"] is False
    assert after["settings"]["default_threshold"] == 0.2
