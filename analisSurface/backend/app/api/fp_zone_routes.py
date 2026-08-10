"""FP-зоны (false positive): области известного шума на heatmap для fp-recheck.

Кратко: как создать зону из прошлой инспекции
---------------------------------------------
1. Из ответа inspect возьмите `fp_zone_context` (product_type, heatmap_w, heatmap_h)
   и сохраните вместе с heatmap/архивом кадра.
2. В UI обведите ложняк на heatmap той инспекции → points в норм. 0..1.
3. POST /fp-zones с телом:
   {
     "product_type": "...",
     "heatmap_w": <из fp_zone_context>,
     "heatmap_h": <из fp_zone_context>,
     "points": [{"x": 0.1, "y": 0.2}, ...],
     "source_frame_path": "<опционально: путь к кадру/heatmap>",
     "note": "..."
   }
"""

from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_fp_zone_response
from app.api.schemas import FPZoneCreateRequest, FPZoneListResponse, FPZoneResponse


router = APIRouter()


@router.post("/fp-zones", response_model=FPZoneResponse)
async def add_fp_zone(payload: FPZoneCreateRequest) -> FPZoneResponse:
    """POST /fp-zones — добавить зону ложного срабатывания.

    points — полигон на heatmap (норм. координаты); heatmap_w/h — размер heatmap при разметке.
    Для архивной инспекции передайте heatmap_* / source_frame_path той инспекции.
    """
    points = [(p.x, p.y) for p in payload.points]
    try:
        zone = inspection_service.add_fp_zone(
            product_type=payload.product_type,
            points_norm_heatmap=points,
            heatmap_w=payload.heatmap_w,
            heatmap_h=payload.heatmap_h,
            note=payload.note,
            source_frame_path=payload.source_frame_path or "",
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


@router.delete("/fp-zones")
async def delete_all_fp_zones() -> dict:
    """DELETE /fp-zones — удалить все FP-зоны (все product_type) и очистить fp_zones.json."""
    deleted_count = inspection_service.delete_all_fp_zones()
    return {"deleted": True, "zones_count": deleted_count}


@router.delete("/fp-zones/{zone_id}")
async def delete_fp_zone(zone_id: str) -> dict:
    """DELETE /fp-zones/{zone_id} — удалить зону по UUID."""
    deleted = inspection_service.delete_fp_zone(zone_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="FP zone not found")
    return {"deleted": True, "zone_id": zone_id}
