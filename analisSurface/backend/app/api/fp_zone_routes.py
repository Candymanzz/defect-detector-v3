from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_fp_zone_response
from app.api.schemas import FPZoneCreateRequest, FPZoneListResponse, FPZoneResponse


router = APIRouter()


@router.post("/fp-zones", response_model=FPZoneResponse)
async def add_fp_zone(payload: FPZoneCreateRequest) -> FPZoneResponse:
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
    zones = inspection_service.get_fp_zones(product_type)
    return FPZoneListResponse(
        product_type=product_type,
        zones=[to_fp_zone_response(zone) for zone in zones],
    )


@router.delete("/fp-zones/{zone_id}")
async def delete_fp_zone(zone_id: str) -> dict:
    deleted = inspection_service.delete_fp_zone(zone_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="FP zone not found")
    return {"deleted": True, "zone_id": zone_id}
