"""Файловое логирование HTTP-запросов и этапов анализа в backend/logs/."""

from __future__ import annotations

import json
import os
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional

_LOGS_DIR = Path(__file__).resolve().parent.parent / "logs"
_MAX_BODY_CHARS = 4000
_LOCK = threading.Lock()
_LOG_FILE: Optional[Path] = None
_INITIALIZED = False


def _enabled() -> bool:
    return os.environ.get("ANALIS_SURFACE_DISABLE_FILE_LOG", "").strip().lower() not in {
        "1",
        "true",
        "yes",
    }


def _timestamp() -> str:
    return datetime.now().isoformat(timespec="milliseconds")


def _ensure_initialized() -> None:
    global _LOG_FILE, _INITIALIZED
    if _INITIALIZED or not _enabled():
        _INITIALIZED = True
        return
    with _LOCK:
        if _INITIALIZED:
            return
        _LOGS_DIR.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        _LOG_FILE = _LOGS_DIR / f"{stamp}.log"
        header = f"=== HTTP log started: {_LOG_FILE} ==="
        _LOG_FILE.write_text(header + "\n", encoding="utf-8")
        _INITIALIZED = True


def _write_block(lines: list[str]) -> None:
    if not _enabled():
        return
    _ensure_initialized()
    if _LOG_FILE is None:
        return
    payload = "\n".join(lines) + "\n"
    with _LOCK:
        with _LOG_FILE.open("a", encoding="utf-8") as handle:
            handle.write(payload)


def _truncate_text(text: str, limit: int = _MAX_BODY_CHARS) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


def _format_body_for_log(body: bytes, content_type: str) -> str:
    content_type = (content_type or "").lower()
    if not body:
        return ""
    if "multipart/form-data" in content_type:
        return f"[multipart body omitted, {len(body)} bytes]"
    if content_type.startswith("image/") or content_type.startswith("video/"):
        return f"[binary body omitted, {len(body)} bytes, content-type={content_type}]"
    try:
        text = body.decode("utf-8", errors="replace")
    except Exception:
        return f"[body decode failed, {len(body)} bytes]"
    stripped = text.strip()
    if not stripped:
        return ""
    if "application/json" in content_type or stripped.startswith(("{", "[")):
        try:
            return _truncate_text(json.dumps(json.loads(stripped), ensure_ascii=False))
        except Exception:
            pass
    return _truncate_text(text.replace("\r\n", "\n").replace("\n", "\\n"))


def _format_headers(headers: Mapping[str, str]) -> str:
    safe_headers = {
        key: ("[omitted]" if key.lower() == "authorization" else value)
        for key, value in headers.items()
    }
    return json.dumps(safe_headers, ensure_ascii=False)


def log_http_request(
    method: str,
    path: str,
    headers: Mapping[str, str],
    body: bytes,
) -> None:
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _write_block(
        [
            f"{_timestamp()} REQUEST {method} {path}",
            f"Headers: {_format_headers(headers)}",
            f"Body: {body_text}",
        ]
    )


def log_http_response(
    method: str,
    path: str,
    status_code: int,
    duration_ms: float,
    headers: Mapping[str, str],
    body: bytes,
) -> None:
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _write_block(
        [
            f"{_timestamp()} RESPONSE {method} {path} status={status_code} duration_ms={duration_ms:.1f}",
            f"Headers: {_format_headers(headers)}",
            f"Body: {body_text}",
        ]
    )


def log_analysis_stage(
    stage: str,
    message: str,
    *,
    product_type: str = "",
    skipped: bool = False,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    parts = [f"{_timestamp()} ANALYSIS stage={stage}"]
    if product_type:
        parts[0] += f" product_type={product_type}"
    if skipped:
        parts[0] += " SKIPPED"
    parts[0] += f" {message}"
    if extra:
        rendered = " ".join(f"{key}={value}" for key, value in extra.items())
        parts.append(rendered)
    _write_block(parts)


def init_file_logging() -> None:
    """Вызывается при старте приложения — создаёт файл сессии."""
    _ensure_initialized()
