"""Файловое логирование в backend/logs/<дата_время>/ с раздельными файлами."""

from __future__ import annotations

import json
import os
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional

from app.detector_settings import is_file_logging_enabled

_LOGS_ROOT = Path(__file__).resolve().parent.parent / "logs"
_MAX_BODY_CHARS = 4000
_SLOW_REQUEST_MS = 1000.0
_LOCK = threading.Lock()
_SESSION_DIR: Optional[Path] = None
_INITIALIZED = False

_SKIP_PATHS = frozenset({"/health", "/detector/health"})


def _enabled() -> bool:
    return is_file_logging_enabled()


def file_logging_enabled() -> bool:
    """Публичная проверка: писать ли файловые логи и буферизовать HTTP в middleware."""
    return _enabled()


def _log_health_checks() -> bool:
    return os.environ.get("ANALIS_SURFACE_LOG_HEALTH", "").strip().lower() in {
        "1",
        "true",
        "yes",
    }


def _timestamp() -> str:
    return datetime.now().isoformat(timespec="milliseconds")


def _path_only(target: str) -> str:
    return target.split("?", 1)[0]


def _should_skip_http(path: str) -> bool:
    if _path_only(path) in _SKIP_PATHS and not _log_health_checks():
        return True
    return False


def _ensure_initialized() -> None:
    global _SESSION_DIR, _INITIALIZED
    if _INITIALIZED or not _enabled():
        _INITIALIZED = True
        return
    with _LOCK:
        if _INITIALIZED:
            return
        _LOGS_ROOT.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        _SESSION_DIR = _LOGS_ROOT / stamp
        _SESSION_DIR.mkdir(parents=True, exist_ok=True)
        session_meta = "\n".join(
            [
                f"started_at={_timestamp()}",
                f"session_dir={_SESSION_DIR}",
                f"log_health_checks={_log_health_checks()}",
                "files=requests.log,responses.log,analysis.log,errors.log,slow.log",
                "notes=health endpoints skipped unless ANALIS_SURFACE_LOG_HEALTH=1",
            ]
        )
        (_SESSION_DIR / "session.txt").write_text(session_meta + "\n", encoding="utf-8")
        for name in ("requests", "responses", "analysis", "errors", "slow"):
            (_SESSION_DIR / f"{name}.log").write_text(
                f"=== {name} log started: {_SESSION_DIR / f'{name}.log'} ===\n",
                encoding="utf-8",
            )
        _INITIALIZED = True


def _append(log_name: str, lines: list[str]) -> None:
    if not _enabled():
        return
    _ensure_initialized()
    if _SESSION_DIR is None:
        return
    payload = "\n".join(lines) + "\n"
    with _LOCK:
        with (_SESSION_DIR / f"{log_name}.log").open("a", encoding="utf-8") as handle:
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


def get_session_log_dir() -> Optional[Path]:
    """Путь к папке текущей сессии логов (или None, если логирование выключено)."""
    _ensure_initialized()
    return _SESSION_DIR


def log_http_request(
    method: str,
    path: str,
    headers: Mapping[str, str],
    body: bytes,
) -> None:
    if _should_skip_http(path):
        return
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _append(
        "requests",
        [
            f"{_timestamp()} {method} {path}",
            f"Headers: {_format_headers(headers)}",
            f"Body: {body_text}",
        ],
    )


def log_http_response(
    method: str,
    path: str,
    status_code: int,
    duration_ms: float,
    headers: Mapping[str, str],
    body: bytes,
) -> None:
    if _should_skip_http(path):
        return
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _append(
        "responses",
        [
            f"{_timestamp()} {method} {path} status={status_code} duration_ms={duration_ms:.1f}",
            f"Headers: {_format_headers(headers)}",
            f"Body: {body_text}",
        ],
    )
    if status_code >= 400:
        log_error(
            "http_error",
            f"{method} {path} -> {status_code} ({duration_ms:.1f} ms)",
            extra={"body": body_text},
        )
    if duration_ms >= _SLOW_REQUEST_MS:
        _append(
            "slow",
            [
                f"{_timestamp()} SLOW {method} {path} status={status_code} duration_ms={duration_ms:.1f}",
            ],
        )


def log_analysis_stage(
    stage: str,
    message: str,
    *,
    product_type: str = "",
    skipped: bool = False,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    headline = f"{_timestamp()} stage={stage}"
    if product_type:
        headline += f" product_type={product_type}"
    if skipped:
        headline += " SKIPPED"
    headline += f" {message}"
    lines = [headline]
    if extra:
        lines.append(" ".join(f"{key}={value}" for key, value in extra.items()))
    _append("analysis", lines)


def log_error(
    kind: str,
    message: str,
    *,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    lines = [f"{_timestamp()} {kind} {message}"]
    if extra:
        lines.append(json.dumps(dict(extra), ensure_ascii=False))
    _append("errors", lines)


def init_file_logging() -> None:
    """Вызывается при старте приложения — создаёт папку сессии и файлы логов."""
    _ensure_initialized()
