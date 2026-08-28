"""HTTP request/response logging to a timestamped log file."""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from pathlib import Path

from starlette.requests import Request
from starlette.responses import Response

_MAX_BODY_LOG = 8000
_LOG_DIR_ENV = "ANALIS_SURFACE_HTTP_LOG_DIR"
_LOG_ENABLED_ENV = "ANALIS_SURFACE_HTTP_LOG"

_request_logger: logging.Logger | None = None


def _logs_enabled() -> bool:
    value = os.environ.get(_LOG_ENABLED_ENV, "1").strip().lower()
    return value not in {"0", "false", "no", "off"}


def _resolve_log_dir() -> Path:
    configured = os.environ.get(_LOG_DIR_ENV, "").strip()
    if configured:
        return Path(configured)
    return Path(__file__).resolve().parents[1] / "logs"


def _format_body_for_log(raw: bytes, content_type: str) -> str:
    if not raw:
        return ""

    lowered = content_type.lower()
    if "application/json" in lowered or "text/" in lowered or "+json" in lowered:
        text = raw.decode("utf-8", errors="replace")
        if len(text) > _MAX_BODY_LOG:
            return text[:_MAX_BODY_LOG] + "..."
        return text

    if "multipart/form-data" in lowered:
        return f"[multipart/form-data, {len(raw)} bytes]"

    return f"[binary, {len(raw)} bytes]"


def _format_headers(headers) -> str:
    return json.dumps(dict(headers), ensure_ascii=False)


def get_request_logger() -> logging.Logger | None:
    global _request_logger
    if _request_logger is not None:
        return _request_logger
    if not _logs_enabled():
        return None

    log_dir = _resolve_log_dir()
    log_dir.mkdir(parents=True, exist_ok=True)
    filename = datetime.now().strftime("%Y-%m-%d_%H-%M-%S.log")
    log_path = log_dir / filename

    logger = logging.getLogger("analisSurface.http")
    logger.setLevel(logging.INFO)
    logger.propagate = False

    if not logger.handlers:
        handler = logging.FileHandler(log_path, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(message)s"))
        logger.addHandler(handler)

    logger.info("=== HTTP log started: %s ===", log_path)
    _request_logger = logger
    return _request_logger


async def log_http_exchange(request: Request, call_next) -> Response:
    logger = get_request_logger()
    started_at = datetime.now()
    method = request.method
    path = request.url.path
    query = request.url.query
    request_target = f"{path}?{query}" if query else path

    request_body = await request.body()
    request_content_type = request.headers.get("content-type", "")

    if logger is not None:
        logger.info(
            "%s REQUEST %s %s\nHeaders: %s\nBody: %s",
            started_at.isoformat(timespec="milliseconds"),
            method,
            request_target,
            _format_headers(request.headers),
            _format_body_for_log(request_body, request_content_type),
        )

    response = await call_next(request)

    response_body = b""
    async for chunk in response.body_iterator:
        response_body += chunk

    finished_at = datetime.now()
    response_content_type = response.headers.get("content-type", "")

    if logger is not None:
        duration_ms = (finished_at - started_at).total_seconds() * 1000
        logger.info(
            "%s RESPONSE %s %s status=%s duration_ms=%.1f\nHeaders: %s\nBody: %s",
            finished_at.isoformat(timespec="milliseconds"),
            method,
            request_target,
            response.status_code,
            duration_ms,
            _format_headers(response.headers),
            _format_body_for_log(response_body, response_content_type),
        )

    return Response(
        content=response_body,
        status_code=response.status_code,
        headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
        media_type=response.media_type,
    )
