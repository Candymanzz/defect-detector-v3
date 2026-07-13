import os

import httpx
import pytest


ORCHESTRATOR_URL = os.environ.get("ORCHESTRATOR_HEALTH_URL", "http://127.0.0.1:8099/health")
PYTHON_URL = os.environ.get("PYTHON_HEALTH_URL", "http://127.0.0.1:8000/detector/health")
LIGHTSERVER_URL = os.environ.get("LIGHTSERVER_HEALTH_URL", "http://127.0.0.1:5080/health")


def _assert_health(url: str, expected_service: str | None = None) -> None:
    try:
        response = httpx.get(url, timeout=2.0)
    except httpx.HTTPError as exc:
        pytest.skip(f"service not reachable at {url}: {exc}")

    assert response.status_code == 200, f"{url} returned {response.status_code}"
    payload = response.json()
    assert payload.get("status") == "ok", payload
    if expected_service is not None:
        assert payload.get("service") == expected_service


@pytest.mark.integration
def test_orchestrator_health() -> None:
    _assert_health(ORCHESTRATOR_URL)


@pytest.mark.integration
def test_python_detector_health() -> None:
    _assert_health(PYTHON_URL, expected_service="analisSurface")


@pytest.mark.integration
def test_lightserver_health() -> None:
    _assert_health(LIGHTSERVER_URL)
