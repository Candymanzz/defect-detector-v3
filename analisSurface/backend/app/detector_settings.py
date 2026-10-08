"""Настройки python_detector из config оркестратора (только Python, без Java)."""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping, Optional

try:
    import yaml
except ImportError:  # pragma: no cover - optional until requirements install
    yaml = None  # type: ignore[assignment]


def _parse_bool(value: object, *, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return default


def _find_config_yaml() -> Optional[Path]:
    explicit = os.environ.get("ANALIS_SURFACE_CONFIG", "").strip()
    if explicit:
        path = Path(explicit)
        if path.is_dir():
            candidate = path / "config.yaml"
            if candidate.is_file():
                return candidate
        if path.is_file():
            return path

    config_dir = os.environ.get("ANALIS_SURFACE_CONFIG_DIR", "").strip()
    if config_dir:
        candidate = Path(config_dir) / "config.yaml"
        if candidate.is_file():
            return candidate

    cwd_candidate = Path.cwd() / "config" / "config.yaml"
    if cwd_candidate.is_file():
        return cwd_candidate

    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "config" / "config.yaml"
        if candidate.is_file():
            return candidate
    return None


def _load_merged_orchestrator_config() -> dict[str, Any]:
    if yaml is None:
        return {}
    config_yaml = _find_config_yaml()
    if config_yaml is None:
        return {}
    try:
        root = yaml.safe_load(config_yaml.read_text(encoding="utf-8")) or {}
    except OSError:
        return {}
    imports = root.get("imports") or []
    if not isinstance(imports, list):
        return dict(root) if isinstance(root, dict) else {}

    merged: dict[str, Any] = {}
    config_dir = config_yaml.parent
    for entry in imports:
        rel = str(entry).strip().strip('"').strip("'")
        if not rel:
            continue
        block_path = config_dir / rel
        if not block_path.is_file():
            continue
        try:
            block = yaml.safe_load(block_path.read_text(encoding="utf-8")) or {}
        except OSError:
            continue
        if isinstance(block, dict):
            merged.update(block)
    if isinstance(root, dict):
        for key, value in root.items():
            if key != "imports" and isinstance(value, dict) and key in merged and isinstance(merged[key], dict):
                merged[key] = {**merged[key], **value}
            elif key != "imports":
                merged[key] = value
    return merged


@lru_cache(maxsize=1)
def get_python_detector_settings() -> Mapping[str, Any]:
    merged = _load_merged_orchestrator_config()
    section = merged.get("python_detector")
    if isinstance(section, dict):
        return section
    return {}


@lru_cache(maxsize=1)
def is_file_logging_enabled() -> bool:
    """Файловое логирование (requests/responses/analysis/errors/slow).

    Приоритет: ANALIS_SURFACE_FILE_LOGGING → ANALIS_SURFACE_DISABLE_FILE_LOG →
    python_detector.file_logging в config → включено по умолчанию.
    """
    explicit = os.environ.get("ANALIS_SURFACE_FILE_LOGGING", "").strip()
    if explicit:
        return _parse_bool(explicit, default=True)

    legacy_disable = os.environ.get("ANALIS_SURFACE_DISABLE_FILE_LOG", "").strip().lower()
    if legacy_disable in {"1", "true", "yes", "on"}:
        return False

    from_config = get_python_detector_settings().get("file_logging")
    if from_config is not None:
        return _parse_bool(from_config, default=True)
    return True
