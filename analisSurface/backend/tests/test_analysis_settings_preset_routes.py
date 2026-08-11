from fastapi.testclient import TestClient

from app.main import app
from app.services.analysis_settings_presets import expand_simple


client = TestClient(app)


def test_simple_settings_put_get_roundtrip() -> None:
    profile = "test-simple-preset"
    body = {"threshold": 0.3, "sensitivity": 0.0}
    expected = expand_simple(0.3, 0.0)

    put_response = client.put(f"/analysis-settings/{profile}/simple", json=body)
    assert put_response.status_code == 200
    payload = put_response.json()
    assert payload["knobs"] == body
    assert payload["settings"]["default_threshold"] == 0.3
    assert payload["settings"]["min_diff_signal"] == expected["min_diff_signal"]
    assert payload["overrides"]["min_diff_signal"] == expected["min_diff_signal"]

    get_response = client.get(f"/analysis-settings/{profile}/simple")
    assert get_response.status_code == 200
    assert get_response.json()["knobs"] == body

    full = client.get(f"/analysis-settings/{profile}")
    assert full.status_code == 200
    assert full.json()["settings"]["min_diff_signal"] == expected["min_diff_signal"]

    client.delete(f"/analysis-settings/{profile}")


def test_simple_settings_validation() -> None:
    response = client.put(
        "/analysis-settings/test-simple-bad/simple",
        json={"threshold": 0.0, "sensitivity": 0.5},
    )
    assert response.status_code == 422

    response = client.put(
        "/analysis-settings/test-simple-bad/simple",
        json={"threshold": 0.25, "sensitivity": 1.5},
    )
    assert response.status_code == 422


def test_pro_settings_put_get_roundtrip() -> None:
    profile = "test-pro-preset"
    body = {
        "threshold": 0.28,
        "noise_tolerance": 0.0,
        "scratch_sensitivity": 0.5,
        "edge_suppression": 0.5,
        "text_handling": 0.5,
        "preprocess_strength": 0.5,
    }

    put_response = client.put(f"/analysis-settings/{profile}/pro", json=body)
    assert put_response.status_code == 200
    payload = put_response.json()
    assert payload["knobs"] == body
    assert payload["settings"]["default_threshold"] == 0.28
    assert payload["settings"]["min_diff_signal"] == 40.0

    get_response = client.get(f"/analysis-settings/{profile}/pro")
    assert get_response.status_code == 200
    assert get_response.json()["knobs"] == body

    # simple knobs cleared after pro apply
    simple_get = client.get(f"/analysis-settings/{profile}/simple")
    assert simple_get.status_code == 200
    assert simple_get.json()["knobs"] is None

    client.delete(f"/analysis-settings/{profile}")


def test_full_api_clears_preset_knobs() -> None:
    profile = "test-preset-clear"
    client.put(
        f"/analysis-settings/{profile}/simple",
        json={"threshold": 0.25, "sensitivity": 0.5},
    )
    assert client.get(f"/analysis-settings/{profile}/simple").json()["knobs"] is not None

    client.put(f"/analysis-settings/{profile}", json={"default_threshold": 0.4})
    assert client.get(f"/analysis-settings/{profile}/simple").json()["knobs"] is None

    client.delete(f"/analysis-settings/{profile}")
