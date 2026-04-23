import base64
import os
import time
from io import BytesIO
from typing import Optional

import cv2
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
from PIL import Image


class CaptureResponse(BaseModel):
    image_b64: str
    source: str
    duration_ms: float


app = FastAPI(title="Camera Capture Server", version="0.1.0")


def _encode_png_b64(frame) -> str:
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    img = Image.fromarray(rgb)
    buff = BytesIO()
    img.save(buff, format="PNG")
    return base64.b64encode(buff.getvalue()).decode("utf-8")


def _candidate_sources(source: Optional[str]) -> list[str]:
    if source:
        return [source]
    env_source = os.getenv("CAMERA_SOURCE")
    if env_source:
        return [env_source]
    return []


def _capture_from_source(source: str):
    if source.isdigit():
        cap = cv2.VideoCapture(int(source))
    else:
        cap = cv2.VideoCapture(source, cv2.CAP_FFMPEG)

    # Reduce waiting time when source is invalid/unreachable.
    cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 3000)
    cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
    if not cap.isOpened():
        cap.release()
        return None

    ok, frame = cap.read()
    cap.release()
    if not ok or frame is None:
        return None
    return frame


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/capture", response_model=CaptureResponse)
async def capture(source: Optional[str] = Query(default=None)) -> CaptureResponse:
    started = time.perf_counter()
    sources = _candidate_sources(source)
    if not sources:
        raise HTTPException(
            status_code=400,
            detail="Camera source is not configured. Set CAMERA_SOURCE env or pass ?source=<url|index>.",
        )

    for candidate in sources:
        frame = _capture_from_source(candidate)
        if frame is None:
            continue

        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return CaptureResponse(
            image_b64=_encode_png_b64(frame),
            source=candidate,
            duration_ms=elapsed_ms,
        )

    raise HTTPException(status_code=502, detail="Could not capture frame from configured camera source")
