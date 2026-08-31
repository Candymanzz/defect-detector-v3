import json
import logging
import time

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.file_logging import init_file_logging, log_http_request, log_http_response
from app.runtime import get_application_id

LOG = logging.getLogger("uvicorn.error")


app = FastAPI(title="Defect Detector API", version="0.1.0")


@app.on_event("startup")
def _startup_file_logging() -> None:
    init_file_logging()

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
async def http_file_logging(request: Request, call_next) -> Response:
    started = time.perf_counter()
    method = request.method
    path = request.url.path
    if request.url.query:
        path = f"{path}?{request.url.query}"
    request_headers = dict(request.headers)
    request_body = await request.body()
    log_http_request(method, path, request_headers, request_body)

    response = await call_next(request)
    duration_ms = (time.perf_counter() - started) * 1000.0

    response_body = b""
    async for chunk in response.body_iterator:
        response_body += chunk
    response_headers = {
        key: value
        for key, value in response.headers.items()
        if key.lower() != "content-length"
    }
    log_http_response(
        method,
        path,
        response.status_code,
        duration_ms,
        response_headers,
        response_body,
    )
    return Response(
        content=response_body,
        status_code=response.status_code,
        headers=response_headers,
        media_type=response.media_type,
    )


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


@app.get("/health")
async def health() -> dict:
    """GET /health — общий liveness (отличается от /detector/health для оркестратора)."""
    return {
        "status": "ok",
        "service": "kopcheni-service",
        "detector_id": get_application_id(),
    }
