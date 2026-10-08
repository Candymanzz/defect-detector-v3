"""Главный ROI-полигон: точки в нормализованных координатах [0, 1]."""

from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.schemas import RoiPoint, RoiPolygonRequest, RoiPolygonResponse


router = APIRouter()


@router.post("/roi-polygon", response_model=RoiPolygonResponse)
async def set_roi_polygon(payload: RoiPolygonRequest) -> RoiPolygonResponse:
    """POST /roi-polygon — задать область анализа (мин. 3 точки, координаты 0..1).

    Требует загруженный эталон для product_type.
    """
    if inspection_service.get_reference(payload.product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")
    points = [(p.x, p.y) for p in payload.points]
    try:
        line = (
            [(p.x, p.y) for p in payload.perspective_line]
            if payload.perspective_line
            else None
        )
        inspection_service.set_perspective_line(payload.product_type, line)
        inspection_service.set_roi_polygon(product_type=payload.product_type, points=points)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return RoiPolygonResponse(
        product_type=payload.product_type,
        points=payload.points,
        perspective_line=payload.perspective_line or None,
    )


@router.get("/roi-polygon/{product_type}", response_model=RoiPolygonResponse)
async def get_roi_polygon(product_type: str) -> RoiPolygonResponse:
    """GET /roi-polygon/{product_type} — текущий ROI. 404 если не задан."""
    points = inspection_service.get_roi_polygon(product_type)
    if points is None:
        raise HTTPException(status_code=404, detail="ROI polygon not set for this product_type")
    return RoiPolygonResponse(
        product_type=product_type,
        points=[RoiPoint(x=x, y=y) for x, y in points],
        perspective_line=_line_points(inspection_service.get_perspective_line(product_type)),
    )


def _line_points(line):
    return None if line is None else [RoiPoint(x=x, y=y) for x, y in line]
