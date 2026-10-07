import json
import logging
import os
import time
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.runtime import get_application_id

LOG = logging.getLogger("uvicorn.error")

_HTTP_LOG_MAX_BODY = 8000


def _build_http_logger() -> logging.Logger | None:
    """Один файл на запуск процесса: logs/<время>_pid<pid>.log. Отключение: ANALIS_SURFACE_HTTP_LOG=0."""
    if os.environ.get("ANALIS_SURFACE_HTTP_LOG", "1").strip().lower() in {"0", "false", "no", "off"}:
        return None
    configured = os.environ.get("ANALIS_SURFACE_HTTP_LOG_DIR", "").strip()
    log_dir = Path(configured) if configured else Path(__file__).resolve().parents[1] / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"{datetime.now():%Y-%m-%d_%H-%M-%S}_pid{os.getpid()}.log"
    logger = logging.getLogger("analisSurface.http")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    handler = logging.FileHandler(log_path, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
    logger.info("=== HTTP log started: %s ===", log_path)
    return logger


HTTP_LOG = _build_http_logger()


def _body_for_log(raw: bytes, content_type: str) -> str:
    if not raw:
        return ""
    lowered = content_type.lower()
    if "json" in lowered or lowered.startswith("text/"):
        text = raw.decode("utf-8", errors="replace")
        return text if len(text) <= _HTTP_LOG_MAX_BODY else text[:_HTTP_LOG_MAX_BODY] + "..."
    return f"[{content_type or 'binary'}, {len(raw)} bytes]"


app = FastAPI(title="Defect Detector API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    raw_body = await request.body()
    body_text = ""
    if raw_body:
        try:
            body_text = raw_body.decode("utf-8", errors="replace")
        except Exception:
            body_text = str(raw_body)
    if len(body_text) > 4000:
        body_text = body_text[:4000] + "..."
    LOG.error(
        "validation_422 path=%s detail=%s body=%s",
        request.url.path,
        exc.errors(),
        body_text,
    )
    return JSONResponse(status_code=422, content={"detail": exc.errors()})


@app.middleware("http")
async def add_application_id_to_json(request: Request, call_next) -> Response:
    response = await call_next(request)
    content_type = response.headers.get("content-type", "")
    if not content_type.startswith("application/json"):
        return response

    body = b""
    async for chunk in response.body_iterator:
        body += chunk

    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        return Response(
            content=body,
            status_code=response.status_code,
            headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
            media_type="application/json",
        )

    if isinstance(payload, dict):
        payload.setdefault("detector_id", get_application_id())
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    return Response(
        content=body,
        status_code=response.status_code,
        headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
        media_type="application/json",
    )


@app.middleware("http")
async def log_http_exchange(request: Request, call_next) -> Response:
    # Добавлен последним => внешний слой: видит запрос как пришёл и ответ как уходит клиенту.
    if HTTP_LOG is None:
        return await call_next(request)

    started = time.perf_counter()
    target = request.url.path + (f"?{request.url.query}" if request.url.query else "")
    request_body = await request.body()
    HTTP_LOG.info(
        "%s REQUEST %s %s\nHeaders: %s\nBody: %s",
        datetime.now().isoformat(timespec="milliseconds"),
        request.method,
        target,
        json.dumps(dict(request.headers), ensure_ascii=False),
        _body_for_log(request_body, request.headers.get("content-type", "")),
    )

    response = await call_next(request)
    response_body = b""
    async for chunk in response.body_iterator:
        response_body += chunk
    HTTP_LOG.info(
        "%s RESPONSE %s %s status=%s duration_ms=%.1f\nHeaders: %s\nBody: %s",
        datetime.now().isoformat(timespec="milliseconds"),
        request.method,
        target,
        response.status_code,
        (time.perf_counter() - started) * 1000.0,
        json.dumps(dict(response.headers), ensure_ascii=False),
        _body_for_log(response_body, response.headers.get("content-type", "")),
    )
    return Response(
        content=response_body,
        status_code=response.status_code,
        headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
        media_type=response.media_type,
    )


@app.get("/health")
async def health() -> dict:
    """GET /health — общий liveness (отличается от /detector/health для оркестратора)."""
    return {
        "status": "ok",
        "service": "kopcheni-service",
        "detector_id": get_application_id(),
    }
