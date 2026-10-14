"""Файловое логирование python-сервиса в общую папку сессии: ``logs/<дата_время>/python/``.

Папку сессии создаёт оркестратор и передаёт путь в ``IML_LOG_SESSION_DIR``; при ручном запуске
(uvicorn без оркестратора) сессия создаётся в ``<корень репозитория>/logs/<дата_время>/``.

Файлы: requests.log, responses.log, analysis.log, errors.log, slow.log. Каждая запись — одна строка
с ISO-временем (мс). Запись идёт из фонового потока пачками, поэтому обработчики запросов
не блокируются на диске. Тяжёлое (картинки, multipart, большие тела) не логируется — только размер.
"""

from __future__ import annotations

import atexit
import os
import queue
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Optional, TextIO

from app.detector_settings import is_file_logging_enabled

_ENV_SESSION_DIR = "IML_LOG_SESSION_DIR"
_SERVICE_DIR_NAME = "python"
_LOG_NAMES = ("requests", "responses", "analysis", "errors", "slow")

_MAX_REQUEST_BODY_CHARS = 1000
_MAX_RESPONSE_BODY_CHARS = 1000
_MAX_ERROR_BODY_CHARS = 4000
_SLOW_REQUEST_MS = 1000.0
_FLUSH_INTERVAL_S = 0.5

_SKIP_PATHS = frozenset({"/health", "/detector/health"})

_init_lock = threading.Lock()
_initialized = False
_session_dir: Optional[Path] = None
_queue: "queue.SimpleQueue[Optional[tuple[str, str]]]" = queue.SimpleQueue()
_writer: Optional[threading.Thread] = None


def _enabled() -> bool:
    return is_file_logging_enabled()


def file_logging_enabled() -> bool:
    """Публичная проверка: писать ли файловые логи и буферизовать HTTP в middleware."""
    return _enabled()


def _log_health_checks() -> bool:
    return os.environ.get("ANALIS_SURFACE_LOG_HEALTH", "").strip().lower() in {"1", "true", "yes"}


def _timestamp() -> str:
    return datetime.now().isoformat(timespec="milliseconds")


def _resolve_service_dir() -> Path:
    configured = os.environ.get(_ENV_SESSION_DIR, "").strip()
    if configured:
        session = Path(configured)
    else:
        repo_root = Path(__file__).resolve().parents[3]
        session = repo_root / "logs" / datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    return session / _SERVICE_DIR_NAME


def _ensure_initialized() -> None:
    global _initialized, _session_dir, _writer
    if _initialized:
        return
    with _init_lock:
        if _initialized:
            return
        if _enabled():
            try:
                directory = _resolve_service_dir()
                directory.mkdir(parents=True, exist_ok=True)
                _session_dir = directory
                _writer = threading.Thread(
                    target=_writer_loop,
                    args=(directory,),
                    name="file-log-writer",
                    daemon=True,
                )
                _writer.start()
                atexit.register(_shutdown)
                _enqueue("analysis", f"{_timestamp()} session_started dir={directory}")
            except OSError:
                _session_dir = None
        _initialized = True


def _enqueue(name: str, line: str) -> None:
    _queue.put((name, line))


def _writer_loop(directory: Path) -> None:
    handles: dict[str, TextIO] = {}
    try:
        while True:
            try:
                item = _queue.get(timeout=_FLUSH_INTERVAL_S)
            except queue.Empty:
                _flush(handles)
                continue
            if item is None:
                break
            _write(handles, directory, item)
            # Вычитываем накопившееся без ожидания, потом один flush на пачку.
            while True:
                try:
                    item = _queue.get_nowait()
                except queue.Empty:
                    break
                if item is None:
                    _flush(handles)
                    return
                _write(handles, directory, item)
            _flush(handles)
    finally:
        _flush(handles)
        for handle in handles.values():
            try:
                handle.close()
            except OSError:
                pass


def _write(handles: dict[str, TextIO], directory: Path, item: tuple[str, str]) -> None:
    name, line = item
    handle = handles.get(name)
    if handle is None:
        try:
            handle = (directory / f"{name}.log").open("a", encoding="utf-8")
        except OSError:
            return
        handles[name] = handle
    try:
        handle.write(line + "\n")
    except OSError:
        pass


def _flush(handles: dict[str, TextIO]) -> None:
    for handle in handles.values():
        try:
            handle.flush()
        except OSError:
            pass


def _shutdown() -> None:
    writer = _writer
    if writer is None or not writer.is_alive():
        return
    _queue.put(None)
    writer.join(timeout=2.0)


def _append(log_name: str, line: str) -> None:
    if not _enabled():
        return
    _ensure_initialized()
    if _session_dir is None:
        return
    _enqueue(log_name, line)


def _path_only(target: str) -> str:
    return target.split("?", 1)[0]


def _should_skip_http(path: str) -> bool:
    return _path_only(path) in _SKIP_PATHS and not _log_health_checks()


def _format_body_for_log(body: bytes, content_type: str, limit: int) -> str:
    """Короткое представление тела: бинарное/multipart — только размер, текст — обрезанный префикс."""
    if not body:
        return ""
    content_type = (content_type or "").lower()
    size = len(body)
    if "multipart/" in content_type:
        return f"[multipart omitted, {size} bytes]"
    if content_type.startswith(("image/", "video/", "audio/", "application/octet-stream")):
        return f"[binary omitted, {size} bytes]"
    # Декодируем только нужный префикс: тела могут быть мегабайтными (base64 картинки).
    text = body[: limit * 4].decode("utf-8", errors="replace")
    text = text.replace("\r", "").replace("\n", "\\n")
    if len(text) > limit or size > limit * 4:
        return f"{text[:limit]}...[{size} bytes total]"
    return text


def get_session_log_dir() -> Optional[Path]:
    """Папка python-логов текущей сессии (или None, если логирование выключено)."""
    _ensure_initialized()
    return _session_dir


def log_http_request(
    method: str,
    path: str,
    headers: Mapping[str, str],
    body: bytes,
) -> None:
    if _should_skip_http(path):
        return
    content_type = headers.get("content-type", headers.get("Content-Type", ""))
    _append(
        "requests",
        f"{_timestamp()} {method} {path} bytes={len(body)} "
        f"body={_format_body_for_log(body, content_type, _MAX_REQUEST_BODY_CHARS)}",
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
    is_error = status_code >= 400
    body_text = _format_body_for_log(
        body, content_type, _MAX_ERROR_BODY_CHARS if is_error else _MAX_RESPONSE_BODY_CHARS
    )
    now = _timestamp()
    _append(
        "responses",
        f"{now} {method} {path} status={status_code} duration_ms={duration_ms:.1f} "
        f"bytes={len(body)} body={body_text}",
    )
    if is_error:
        log_error(
            "http_error",
            f"{method} {path} -> {status_code} ({duration_ms:.1f} ms)",
            extra={"body": body_text},
        )
    if duration_ms >= _SLOW_REQUEST_MS:
        _append(
            "slow",
            f"{now} SLOW {method} {path} status={status_code} duration_ms={duration_ms:.1f}",
        )


def log_analysis_stage(
    stage: str,
    message: str,
    *,
    product_type: str = "",
    skipped: bool = False,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    if not _enabled():
        return
    line = f"{_timestamp()} stage={stage}"
    if product_type:
        line += f" product_type={product_type}"
    if skipped:
        line += " SKIPPED"
    line += f" {message}"
    if extra:
        line += " | " + " ".join(f"{key}={value}" for key, value in extra.items())
    _append("analysis", line)


def log_error(
    kind: str,
    message: str,
    *,
    extra: Optional[Mapping[str, Any]] = None,
) -> None:
    if not _enabled():
        return
    line = f"{_timestamp()} {kind} {message}"
    if extra:
        line += " | " + " ".join(f"{key}={value}" for key, value in extra.items())
    _append("errors", line)


def init_file_logging() -> None:
    """Вызывается при старте приложения — создаёт папку сессии и запускает фоновую запись."""
    _ensure_initialized()
