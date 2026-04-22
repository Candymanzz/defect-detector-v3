from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import router


app = FastAPI(title="Defect Detector API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}
