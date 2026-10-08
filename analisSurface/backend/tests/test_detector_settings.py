from __future__ import annotations

from pathlib import Path

import pytest

from app import detector_settings
from app.detector_settings import is_file_logging_enabled
from app.file_logging import file_logging_enabled


@pytest.fixture(autouse=True)
def _clear_settings_cache() -> None:
    detector_settings.get_python_detector_settings.cache_clear()
    detector_settings.is_file_logging_enabled.cache_clear()
    yield
    detector_settings.get_python_detector_settings.cache_clear()
    detector_settings.is_file_logging_enabled.cache_clear()


def test_file_logging_reads_python_detector_yaml(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config_dir = tmp_path / "config"
    blocks = config_dir / "blocks"
    blocks.mkdir(parents=True)
    (config_dir / "config.yaml").write_text(
        'imports:\n  - "blocks/20-detectors.yaml"\n',
        encoding="utf-8",
    )
    (blocks / "20-detectors.yaml").write_text(
        "python_detector:\n  file_logging: false\n",
        encoding="utf-8",
    )
    monkeypatch.delenv("ANALIS_SURFACE_FILE_LOGGING", raising=False)
    monkeypatch.delenv("ANALIS_SURFACE_DISABLE_FILE_LOG", raising=False)
    monkeypatch.setenv("ANALIS_SURFACE_CONFIG", str(config_dir))

    assert is_file_logging_enabled() is False
    assert file_logging_enabled() is False


def test_env_overrides_yaml(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config_dir = tmp_path / "config"
    blocks = config_dir / "blocks"
    blocks.mkdir(parents=True)
    (config_dir / "config.yaml").write_text(
        'imports:\n  - "blocks/20-detectors.yaml"\n',
        encoding="utf-8",
    )
    (blocks / "20-detectors.yaml").write_text(
        "python_detector:\n  file_logging: false\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("ANALIS_SURFACE_CONFIG", str(config_dir))
    monkeypatch.setenv("ANALIS_SURFACE_FILE_LOGGING", "true")

    assert is_file_logging_enabled() is True


def test_legacy_disable_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANALIS_SURFACE_FILE_LOGGING", raising=False)
    monkeypatch.setenv("ANALIS_SURFACE_DISABLE_FILE_LOG", "1")

    assert is_file_logging_enabled() is False


def test_defaults_to_enabled_without_config(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANALIS_SURFACE_FILE_LOGGING", raising=False)
    monkeypatch.delenv("ANALIS_SURFACE_DISABLE_FILE_LOG", raising=False)
    monkeypatch.setenv("ANALIS_SURFACE_CONFIG", str(Path("/nonexistent/config")))

    assert is_file_logging_enabled() is True
