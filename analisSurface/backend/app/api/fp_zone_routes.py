"""FP-зоны: полигон ложного срабатывания становится мини-эталоном.

На следующей инспекции зона вычитается из основного анализа и проверяется отдельно:
кроп vs основной эталон, затем vs сохранённая ложная картинка.
"""

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from app.api.dependencies import inspection_service
from app.api.mappers import to_fp_zone_response
from app.api.schemas import FPZoneCreateRequest, FPZoneListResponse, FPZoneResponse


router = APIRouter()


@router.post("/fp-zones", response_model=FPZoneResponse)
async def add_fp_zone(payload: FPZoneCreateRequest) -> FPZoneResponse:
    """POST /fp-zones — сохранить зону ложного срабатывания как мини-эталон.

    Нужна предыдущая инспекция того же product_type: из выровненного кадра
    вырезается кроп полигона. points — норм. координаты [0,1].
    """
    points = [(p.x, p.y) for p in payload.points]
    try:
        zone = inspection_service.add_fp_zone(
            product_type=payload.product_type,
            points_norm_heatmap=points,
            heatmap_w=payload.heatmap_w,
            heatmap_h=payload.heatmap_h,
            note=payload.note,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_fp_zone_response(zone)


@router.get("/fp-zones/{product_type}", response_model=FPZoneListResponse)
async def get_fp_zones(product_type: str) -> FPZoneListResponse:
    """GET /fp-zones/{product_type} — список FP-зон (может быть пустым)."""
    zones = inspection_service.get_fp_zones(product_type)
    return FPZoneListResponse(
        product_type=product_type,
        zones=[to_fp_zone_response(zone) for zone in zones],
    )


@router.get("/fp-zones/{zone_id}/crop")
async def get_fp_zone_crop(zone_id: str) -> Response:
    """GET /fp-zones/{zone_id}/crop — PNG мини-эталона зоны."""
    png = inspection_service.get_fp_zone_crop_png(zone_id)
    if png is None:
        raise HTTPException(status_code=404, detail="FP zone crop not found")
    return Response(content=png, media_type="image/png")


@router.delete("/fp-zones")
async def delete_all_fp_zones() -> dict:
    """DELETE /fp-zones — удалить все FP-зоны и их мини-эталоны."""
    deleted_count = inspection_service.delete_all_fp_zones()
    return {"deleted": True, "zones_count": deleted_count}


@router.delete("/fp-zones/{zone_id}")
async def delete_fp_zone(zone_id: str) -> dict:
    """DELETE /fp-zones/{zone_id} — удалить зону по UUID."""
    deleted = inspection_service.delete_fp_zone(zone_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="FP zone not found")
    return {"deleted": True, "zone_id": zone_id}
