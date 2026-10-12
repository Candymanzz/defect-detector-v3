"""Файловое логирование в backend/logs/<дата_время>/ с раздельными файлами."""

from __future__ import annotations

import json
import logging
import os
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional

from app.detector_settings import is_file_logging_enabled

_LOGS_ROOT = Path(__file__).resolve().parent.parent / "logs"
_MAX_BODY_CHARS = 4000
_TRACE_BODY_CHARS = 65536
_SLOW_REQUEST_MS = 1000.0
_LOCK = threading.Lock()
_SESSION_DIR: Optional[Path] = None
_INITIALIZED = False
_WRITE_FAILURE_REPORTED = False

_SKIP_PATHS = frozenset({"/health", "/detector/health"})


def _enabled() -> bool:
    return bool(os.environ.get("IML_TRACE_SESSION_DIR", "").strip()) or is_file_logging_enabled()


def _trace_root() -> Optional[Path]:
    value = os.environ.get("IML_TRACE_SESSION_DIR", "").strip()
    return Path(value) if value else None


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
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _path_only(target: str) -> str:
    return target.split("?", 1)[0]


def _should_skip_http(path: str) -> bool:
    if _trace_root() is not None:
        return False
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
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        trace_root = _trace_root()
        if trace_root is not None:
            _SESSION_DIR = trace_root / "analisSurface" / f"worker-{os.getpid()}-{stamp}"
        else:
            _SESSION_DIR = _LOGS_ROOT / stamp
        _SESSION_DIR.mkdir(parents=True, exist_ok=True)
        session_meta = "\n".join(
            [
                f"started_at={_timestamp()}",
                f"session_dir={_SESSION_DIR}",
                f"log_health_checks={_log_health_checks()}",
                "files=requests.log,responses.log,analysis.log,errors.log,slow.log,http.jsonl",
                "notes=all HTTP endpoints logged" if trace_root is not None
                else "notes=health endpoints skipped unless ANALIS_SURFACE_LOG_HEALTH=1",
            ]
        )
        (_SESSION_DIR / "session.txt").write_text(session_meta + "\n", encoding="utf-8")
        for name in ("requests", "responses", "analysis", "errors", "slow"):
            (_SESSION_DIR / f"{name}.log").write_text(
                f"=== {name} log started: {_SESSION_DIR / f'{name}.log'} ===\n",
                encoding="utf-8",
            )
        (_SESSION_DIR / "http.jsonl").touch()
        _INITIALIZED = True


def _append(log_name: str, lines: list[str]) -> None:
    if not _enabled():
        return
    try:
        _ensure_initialized()
    except OSError as exc:
        _report_write_failure(exc)
        return
    session_dir = _SESSION_DIR
    if session_dir is None:
        return
    payload = "\n".join(lines) + "\n"
    filename = "http.jsonl" if log_name == "http" else f"{log_name}.log"
    try:
        with _LOCK:
            with (session_dir / filename).open("a", encoding="utf-8") as handle:
                handle.write(payload)
    except OSError as exc:
        _report_write_failure(exc)


def _report_write_failure(error: OSError) -> None:
    global _WRITE_FAILURE_REPORTED, _INITIALIZED, _SESSION_DIR
    if not _WRITE_FAILURE_REPORTED:
        logging.getLogger(__name__).warning("Service trace logging disabled: %s", error)
        _WRITE_FAILURE_REPORTED = True
    _SESSION_DIR = None
    _INITIALIZED = True


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
    if content_type.startswith(("image/", "video/", "application/octet-stream")):
        return f"[binary body omitted, {len(body)} bytes, content-type={content_type}]"
    try:
        text = body.decode("utf-8", errors="replace")
    except Exception:
        return f"[body decode failed, {len(body)} bytes]"
    stripped = text.strip()
    if not stripped:
        return ""
    limit = _TRACE_BODY_CHARS if _trace_root() is not None else _MAX_BODY_CHARS
    if "application/json" in content_type or stripped.startswith(("{", "[")):
        try:
            return _truncate_text(json.dumps(_safe_json_value(json.loads(stripped)), ensure_ascii=False), limit)
        except Exception:
            pass
    return _truncate_text(text.replace("\r\n", "\n").replace("\n", "\\n"), limit)


def _safe_json_value(value: Any) -> Any:
    """Keep ordinary JSON fields; omit large embedded images and binary strings."""
    if isinstance(value, dict):
        return {key: _safe_json_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_safe_json_value(item) for item in value]
    if isinstance(value, str) and len(value) > 8192:
        return f"[large value omitted, {len(value)} characters]"
    return value


def _format_headers(headers: Mapping[str, str]) -> str:
    safe_headers = {
        key: ("[omitted]" if key.lower() in {"authorization", "cookie", "set-cookie", "x-api-key"} else value)
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
    request_id: str = "",
) -> None:
    if _should_skip_http(path):
        return
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _append(
        "requests",
        [
            f"{_timestamp()} {method} {path} request_id={request_id}",
            f"Headers: {_format_headers(headers)}",
            f"Bytes: {len(body)}",
            f"Body: {body_text}",
        ],
    )
    if _trace_root() is not None:
        _append("http", [json.dumps({
            "timestamp": _timestamp(), "event": "request", "request_id": request_id,
            "method": method, "path": path, "headers": json.loads(_format_headers(headers)),
            "body_bytes": len(body), "body": body_text,
        }, ensure_ascii=False)])


def log_http_response(
    method: str,
    path: str,
    status_code: int,
    duration_ms: float,
    headers: Mapping[str, str],
    body: bytes,
    request_id: str = "",
) -> None:
    if _should_skip_http(path):
        return
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    body_text = _format_body_for_log(body, content_type)
    _append(
        "responses",
        [
            f"{_timestamp()} {method} {path} request_id={request_id} status={status_code} duration_ms={duration_ms:.1f}",
            f"Headers: {_format_headers(headers)}",
            f"Bytes: {len(body)}",
            f"Body: {body_text}",
        ],
    )
    if _trace_root() is not None:
        _append("http", [json.dumps({
            "timestamp": _timestamp(), "event": "response", "request_id": request_id,
            "method": method, "path": path, "status": status_code,
            "duration_ms": round(duration_ms, 3), "headers": json.loads(_format_headers(headers)),
            "body_bytes": len(body), "body": body_text,
        }, ensure_ascii=False)])
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


def log_http_exception(method: str, path: str, duration_ms: float, error: Exception, request_id: str) -> None:
    details = f"{type(error).__name__}: {error}"
    _append("responses", [
        f"{_timestamp()} {method} {path} request_id={request_id} status=exception duration_ms={duration_ms:.1f}",
        f"Error: {details}",
    ])
    if _trace_root() is not None:
        _append("http", [json.dumps({
            "timestamp": _timestamp(), "event": "exception", "request_id": request_id,
            "method": method, "path": path, "duration_ms": round(duration_ms, 3),
            "error": details,
        }, ensure_ascii=False)])
    log_error("http_exception", f"{method} {path} ({duration_ms:.1f} ms)", extra={"error": details})


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
    try:
        _ensure_initialized()
    except OSError as exc:
        _report_write_failure(exc)
