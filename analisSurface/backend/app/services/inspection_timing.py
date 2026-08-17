"""Structured timing records for production inspection requests."""

from __future__ import annotations

import json
import logging
import os
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any


_LOGGER_NAME = "inspection_timing"
_LOGGER: logging.Logger | None = None


def _get_logger() -> logging.Logger:
    global _LOGGER
    if _LOGGER is not None:
        return _LOGGER

    logger = logging.getLogger(_LOGGER_NAME)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    if not logger.handlers:
        default_path = Path(__file__).resolve().parent.parent / "data" / "inspection_timing.jsonl"
        log_path = Path(os.environ.get("ANALIS_INSPECTION_TIMING_LOG", str(default_path))).expanduser()
        log_path.parent.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(
            log_path,
            maxBytes=20 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        handler.setLevel(logging.INFO)
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)
    _LOGGER = logger
    return logger


def record_inspection_timing(**fields: Any) -> None:
    """Append one JSON object per inspection without changing the API response."""
    try:
        _get_logger().info(json.dumps(fields, ensure_ascii=False, separators=(",", ":")))
    except Exception:
        # Timing telemetry must never turn a completed inspection into an error.
        logging.getLogger(__name__).exception("failed to write inspection timing record")
