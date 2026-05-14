import json

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import router
from app.runtime import get_application_id


app = FastAPI(title="Defect Detector API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)


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
    return {
        "status": "ok",
        "service": "kopcheni-service",
        "detector_id": get_application_id(),
    }
