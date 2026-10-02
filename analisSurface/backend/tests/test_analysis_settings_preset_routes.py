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


def test_legacy_pro_endpoint_roundtrips_new_strengths() -> None:
    profile = "test-legacy-pro-preset"
    body = {
        "threshold": 0.31,
        "noise_tolerance": 20,
        "scratch_sensitivity": 80,
        "edge_suppression": 45,
        "text_handling": 60,
        "preprocess_strength": 75,
    }

    put_response = client.put(f"/analysis-settings/{profile}/pro", json=body)
    assert put_response.status_code == 200, put_response.text
    assert put_response.json()["knobs"] == body

    get_response = client.get(f"/analysis-settings/{profile}/pro")
    assert get_response.status_code == 200
    assert get_response.json()["knobs"] == body

    client.delete(f"/analysis-settings/{profile}")


def test_detailed_settings_put_get_roundtrip() -> None:
    profile = "test-detailed-preset"
    body = {
        "noise_tolerance": 0,
        "scratch_sensitivity": 50,
        "edge_suppression": 50,
        "text_handling": 50,
        "preprocess_strength": 50,
    }

    client.put(
        f"/analysis-settings/{profile}/simple",
        json={"threshold": 0.28, "sensitivity": 1.0},
    )
    put_response = client.put(f"/analysis-settings/{profile}/detailed", json=body)
    assert put_response.status_code == 200
    payload = put_response.json()
    assert payload["knobs"] == body
    assert payload["settings"]["min_diff_signal"] == 40.0

    get_response = client.get(f"/analysis-settings/{profile}/detailed")
    assert get_response.status_code == 200
    assert get_response.json()["knobs"] == body

    # simple и detailed хранятся вместе
    simple_get = client.get(f"/analysis-settings/{profile}/simple")
    assert simple_get.status_code == 200
    assert simple_get.json()["knobs"] == {"threshold": 0.28, "sensitivity": 1.0}

    client.delete(f"/analysis-settings/{profile}")


def test_strength_knobs_get_defaults_when_not_saved() -> None:
    profile = "test-strengths-defaults"
    response = client.get(f"/analysis-settings/{profile}/strengths")
    assert response.status_code == 200
    payload = response.json()
    assert payload["saved"] is False
    assert payload["strengths"] == {
        "noise_tolerance": 100.0,
        "scratch_sensitivity": 100.0,
        "edge_suppression": 100.0,
        "text_handling": 100.0,
        "preprocess_strength": 100.0,
    }


def test_strength_knobs_put_get_roundtrip() -> None:
    profile = "test-strengths-preset"
    body = {
        "noise_tolerance": 0,
        "scratch_sensitivity": 80,
        "edge_suppression": 50,
        "text_handling": 25,
        "preprocess_strength": 100,
    }
    client.put(
        f"/analysis-settings/{profile}/simple",
        json={"threshold": 0.28, "sensitivity": 0.75},
    )
    put_response = client.put(f"/analysis-settings/{profile}/strengths", json=body)
    assert put_response.status_code == 200
    payload = put_response.json()
    assert payload["saved"] is True
    assert payload["strengths"] == body

    get_response = client.get(f"/analysis-settings/{profile}/strengths")
    assert get_response.status_code == 200
    assert get_response.json()["strengths"] == body

    full = client.get(f"/analysis-settings/{profile}")
    assert full.status_code == 200
    assert full.json()["strength_knobs"] == body
    assert full.json()["simple_knobs"] == {"threshold": 0.28, "sensitivity": 0.75}

    client.delete(f"/analysis-settings/{profile}")


def test_fractional_strength_is_preserved_and_scaled() -> None:
    profile = "test-fractional-strength"
    body = {
        "noise_tolerance": 90.1,
        "scratch_sensitivity": 100,
        "edge_suppression": 100,
        "text_handling": 100,
        "preprocess_strength": 100,
    }
    try:
        simple = client.put(
            f"/analysis-settings/{profile}/simple",
            json={"threshold": 0.25, "sensitivity": 0.8},
        )
        assert simple.status_code == 200
        detailed = client.put(f"/analysis-settings/{profile}/detailed", json=body)
        assert detailed.status_code == 200
        assert detailed.json()["knobs"]["noise_tolerance"] == 90.1
        assert detailed.json()["settings"]["min_diff_signal"] == expand_simple(0.25, 0.7208)["min_diff_signal"]
        saved = client.get(f"/analysis-settings/{profile}/strengths")
        assert saved.json()["strengths"]["noise_tolerance"] == 90.1
    finally:
        client.delete(f"/analysis-settings/{profile}")


def test_strengths_above_100_roundtrip_and_limit() -> None:
    profile = "test-boosted-strengths"
    body = {
        "noise_tolerance": 150,
        "scratch_sensitivity": 125.5,
        "edge_suppression": 200,
        "text_handling": 100,
        "preprocess_strength": 100,
    }
    try:
        simple = client.put(
            f"/analysis-settings/{profile}/simple",
            json={"threshold": 0.25, "sensitivity": 0.5},
        )
        assert simple.status_code == 200
        response = client.put(f"/analysis-settings/{profile}/strengths", json=body)
        assert response.status_code == 200, response.text
        assert response.json()["strengths"] == body
        assert client.get(f"/analysis-settings/{profile}/strengths").json()["strengths"] == body
        expanded = client.get(f"/analysis-settings/{profile}").json()["settings"]
        assert expanded["min_diff_signal"] == expand_simple(0.25, 0.75)["min_diff_signal"]
        assert expanded["edge_suppress_factor"] == expand_simple(0.25, 1.0)["edge_suppress_factor"]

        invalid = client.put(
            f"/analysis-settings/{profile}/strengths",
            json={**body, "noise_tolerance": 200.1},
        )
        assert invalid.status_code == 422
    finally:
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
