import json
import logging

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.file_logging import (
    init_file_logging,
    log_error,
)
from app.runtime import get_application_id
from app.session_http_logging import SessionHttpLogging

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
    # Validation inputs can contain image bytes; retain field/type/message only.
    details = [{"loc": error.get("loc"), "type": error.get("type"),
                "msg": error.get("msg")} for error in exc.errors()]
    LOG.error("validation_422 path=%s detail=%s", request.url.path, details)
    log_error("validation_422", f"{request.method} {request.url.path}", extra={"detail": details})
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


@app.get("/health")
async def health() -> dict:
    """GET /health — общий liveness (отличается от /detector/health для оркестратора)."""
    return {
        "status": "ok",
        "service": "kopcheni-service",
        "detector_id": get_application_id(),
    }

app.add_middleware(SessionHttpLogging)
