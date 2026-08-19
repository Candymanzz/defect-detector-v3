import json
import logging
import os
import time
import uuid

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.logging_setup import configure_logging, format_body_preview, should_capture_request_body
from app.runtime import get_application_id

configure_logging()

LOG = logging.getLogger("uvicorn.error")
HTTP_LOG = logging.getLogger("app.http")

REQUEST_BODY_LOG_MAX_BYTES = max(0, int(os.environ.get("ANALIS_LOG_REQUEST_BODY_MAX_BYTES", "8192")))
RESPONSE_BODY_LOG_MAX_BYTES = max(0, int(os.environ.get("ANALIS_LOG_RESPONSE_BODY_MAX_BYTES", "8192")))


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
    started_at = time.perf_counter()
    request_id = uuid.uuid4().hex[:12]
    client_host = request.client.host if request.client else "-"
    raw_query = request.url.query
    target = request.url.path if not raw_query else f"{request.url.path}?{raw_query}"
    content_type = request.headers.get("content-type", "")
    content_length_raw = request.headers.get("content-length", "0")
    try:
        content_length = int(content_length_raw)
    except ValueError:
        content_length = 0

    request_body = b""
    request_body_preview = None
    if REQUEST_BODY_LOG_MAX_BYTES > 0 and should_capture_request_body(
        content_type, content_length, REQUEST_BODY_LOG_MAX_BYTES
    ):
        request_body = await request.body()
        request_body_preview = format_body_preview(request_body)

        async def receive() -> dict:
            return {"type": "http.request", "body": request_body, "more_body": False}

        request._receive = receive

    HTTP_LOG.info(
        "http_request request_id=%s method=%s path=%s client=%s content_type=%s content_length=%s body=%s",
        request_id,
        request.method,
        target,
        client_host,
        content_type or "-",
        content_length,
        request_body_preview or "<skipped>",
    )

    try:
        response = await call_next(request)
    except Exception:
        elapsed_ms = (time.perf_counter() - started_at) * 1000.0
        HTTP_LOG.exception(
            "http_response request_id=%s method=%s path=%s status=500 duration_ms=%.3f failed=true",
            request_id,
            request.method,
            target,
            elapsed_ms,
        )
        raise

    content_type = response.headers.get("content-type", "")
    if not content_type.startswith("application/json"):
        elapsed_ms = (time.perf_counter() - started_at) * 1000.0
        HTTP_LOG.info(
            "http_response request_id=%s method=%s path=%s status=%s duration_ms=%.3f content_type=%s content_length=%s body=%s",
            request_id,
            request.method,
            target,
            response.status_code,
            elapsed_ms,
            content_type or "-",
            response.headers.get("content-length", "-"),
            "<not-json>",
        )
        return response

    body = b""
    async for chunk in response.body_iterator:
        body += chunk

    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        elapsed_ms = (time.perf_counter() - started_at) * 1000.0
        body_preview = None
        if RESPONSE_BODY_LOG_MAX_BYTES > 0 and len(body) <= RESPONSE_BODY_LOG_MAX_BYTES:
            body_preview = format_body_preview(body)
        HTTP_LOG.info(
            "http_response request_id=%s method=%s path=%s status=%s duration_ms=%.3f content_type=%s content_length=%s body=%s",
            request_id,
            request.method,
            target,
            response.status_code,
            elapsed_ms,
            content_type or "-",
            len(body),
            body_preview or "<unparsed-json>",
        )
        return Response(
            content=body,
            status_code=response.status_code,
            headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
            media_type="application/json",
        )

    if isinstance(payload, dict):
        payload.setdefault("detector_id", get_application_id())
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    elapsed_ms = (time.perf_counter() - started_at) * 1000.0
    body_preview = None
    if RESPONSE_BODY_LOG_MAX_BYTES > 0 and len(body) <= RESPONSE_BODY_LOG_MAX_BYTES:
        body_preview = format_body_preview(body)
    HTTP_LOG.info(
        "http_response request_id=%s method=%s path=%s status=%s duration_ms=%.3f content_type=%s content_length=%s body=%s",
        request_id,
        request.method,
        target,
        response.status_code,
        elapsed_ms,
        content_type or "-",
        len(body),
        body_preview or "<skipped>",
    )

    return Response(
        content=body,
        status_code=response.status_code,
        headers={k: v for k, v in response.headers.items() if k.lower() != "content-length"},
        media_type="application/json",
    )


@app.get("/health")
async def health() -> dict:
    """GET /health — общий liveness (отличается от /detector/health для оркестратора)."""
    return {
        "status": "ok",
        "service": "kopcheni-service",
        "detector_id": get_application_id(),
    }
