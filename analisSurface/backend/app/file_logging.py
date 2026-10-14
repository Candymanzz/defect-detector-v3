"""Файловое логирование в backend/logs/<дата_время>/ с раздельными файлами."""

from __future__ import annotations

import atexit
import contextvars
import logging
import logging.handlers
import queue
import json
from itertools import islice
import os
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional

from app.detector_settings import is_file_logging_enabled

_LOGS_ROOT = Path(__file__).resolve().parents[3] / "logs"
_MAX_BODY_CHARS = 4000
_SLOW_REQUEST_MS = 1000.0
_LOCK = threading.Lock()
_SESSION_DIR: Optional[Path] = None
_INITIALIZED = False
_LOG_QUEUE = queue.Queue(maxsize=4096)
_HANDLERS = {}
_LISTENER = None
_REQUEST_ID = contextvars.ContextVar("log_request_id", default="")
_DROPPED = 0
_RUNTIME_HANDLER = None

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
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _path_only(target: str) -> str:
    return target.split("?", 1)[0]


def _should_skip_http(path: str) -> bool:
    if _path_only(path) in _SKIP_PATHS and not _log_health_checks():
        return True
    return False


class _Writer(logging.Handler):
    def emit(self, record):
        try:
            if not hasattr(record, "log_name"):
                record = logging.makeLogRecord(record.__dict__.copy())
                stamp = datetime.fromtimestamp(record.created).astimezone().isoformat(timespec="milliseconds")
                record.msg = f"{stamp} {record.levelname} {record.name} {record.getMessage()[:_MAX_BODY_CHARS]}"
                record.args = ()
                record.log_name = "errors" if record.levelno >= logging.ERROR else "runtime"
            _HANDLERS[record.log_name].emit(record)
        except Exception:
            self.handleError(record)


class _RuntimeQueueHandler(logging.handlers.QueueHandler):
    def enqueue(self, record):
        global _DROPPED
        try:
            self.queue.put_nowait(record)
        except queue.Full:
            with _LOCK:
                _DROPPED += 1


def _shutdown():
    global _LISTENER, _RUNTIME_HANDLER
    if _LISTENER is not None:
        if _RUNTIME_HANDLER is not None:
            logging.getLogger().removeHandler(_RUNTIME_HANDLER)
            logging.getLogger("uvicorn.error").removeHandler(_RUNTIME_HANDLER)
            _RUNTIME_HANDLER = None
        # Only shutdown waits for disk, never the inspection pipeline.
        _LOG_QUEUE.put(None)
        _LISTENER._thread.join(timeout=5)
        if not _LISTENER._thread.is_alive():
            for handler in _HANDLERS.values():
                handler.close()
            _LISTENER = None


def _ensure_initialized() -> None:
    global _SESSION_DIR, _INITIALIZED, _LISTENER, _RUNTIME_HANDLER
    if _INITIALIZED or not _enabled():
        return
    with _LOCK:
        if _INITIALIZED:
            return
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S-%f") + f"-{os.getpid()}"
        session = os.environ.get("IML_LOG_SESSION_DIR", "")
        _SESSION_DIR = Path(session) if session else _LOGS_ROOT / stamp
        _SESSION_DIR.mkdir(parents=True, exist_ok=True)
        for name in ("requests", "responses", "analysis", "errors", "slow", "runtime"):
            handler = logging.handlers.RotatingFileHandler(
                _SESSION_DIR / f"python-{name}-{os.getpid()}.log",
                maxBytes=10 * 1024 * 1024, backupCount=5, encoding="utf-8")
            handler.setFormatter(logging.Formatter("%(message)s"))
            _HANDLERS[name] = handler
        _LISTENER = logging.handlers.QueueListener(_LOG_QUEUE, _Writer())
        _LISTENER.start()
        _RUNTIME_HANDLER = _RuntimeQueueHandler(_LOG_QUEUE)
        logging.getLogger().addHandler(_RUNTIME_HANDLER)
        if not logging.getLogger("uvicorn.error").propagate:
            logging.getLogger("uvicorn.error").addHandler(_RUNTIME_HANDLER)
        atexit.register(_shutdown)
        _INITIALIZED = True


def _append(log_name: str, lines: list[str]) -> None:
    global _DROPPED
    if not _enabled():
        return
    _ensure_initialized()
    if _SESSION_DIR is None:
        return
    # Bound output, escape newlines, keep every event on one timestamped line.
    payload = " | ".join(str(line).replace("\n", "\\n")[:_MAX_BODY_CHARS] for line in lines)
    payload += f" request_id={_REQUEST_ID.get()}"
    with _LOCK:
        if _DROPPED:
            payload += f" dropped_before={_DROPPED}"
        record = logging.LogRecord("session", logging.INFO, "", 0, payload, (), None)
        record.log_name = log_name
        try:
            _LOG_QUEUE.put_nowait(record)
            _DROPPED = 0
        except queue.Full:
            _DROPPED += 1


def _truncate_text(text: str, limit: int = _MAX_BODY_CHARS) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


def _format_body_for_log(body: bytes, content_type: str) -> str:
    content_type = (content_type or "").lower()
    if not body:
        return ""
    if len(body) > _MAX_BODY_CHARS:
        return f"[body omitted, {len(body)} bytes]"
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
        lines.append(" ".join(f"{key}={_safe_value(value)}" for key, value in extra.items()))
    _append("analysis", lines)


def log_error(
    kind: str,
    message: str,
    *,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    lines = [f"{_timestamp()} {kind} {message}"]
    if extra:
        lines.append(json.dumps({k: _safe_value(v) for k, v in extra.items()}, ensure_ascii=False))
    _append("errors", lines)


def init_file_logging() -> None:
    """Вызывается при старте приложения — создаёт папку сессии и файлы логов."""
    _ensure_initialized()


def _safe_value(value, depth=0):
    if depth >= 4:
        return "[nested data omitted]"
    if isinstance(value, (bytes, bytearray)):
        return f"[binary omitted, {len(value)} bytes]"
    if hasattr(value, "shape"):
        return f"[array omitted, shape={value.shape}]"
    if isinstance(value, str):
        return value[:1000] if len(value) <= 1000 else f"[text omitted, {len(value)} characters]"
    if isinstance(value, Mapping):
        return {str(k): _safe_value(v, depth + 1) for k, v in islice(value.items(), 30)}
    if isinstance(value, (list, tuple)):
        return [_safe_value(v, depth + 1) for v in value[:20]]
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return f"[{type(value).__name__} omitted]"
