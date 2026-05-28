from fastapi import APIRouter, HTTPException

from app.api.dependencies import inspection_service
from app.api.mappers import to_roi_sub_zone_response
from app.api.schemas import (
    RoiSubZoneCreateRequest,
    RoiSubZoneListResponse,
    RoiSubZoneResponse,
    RoiSubZoneUpdateRequest,
)


router = APIRouter()


@router.post("/roi-sub-zones", response_model=RoiSubZoneResponse)
async def add_roi_sub_zone(payload: RoiSubZoneCreateRequest) -> RoiSubZoneResponse:
    if inspection_service.get_reference(payload.product_type) is None:
        raise HTTPException(status_code=400, detail="Reference is not set for this product_type")
    points = [(p.x, p.y) for p in payload.points]
    try:
        zone = inspection_service.add_roi_sub_zone(
            product_type=payload.product_type,
            points=points,
            threshold=payload.threshold,
            label=payload.label,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return to_roi_sub_zone_response(zone)


@router.get("/roi-sub-zones/{product_type}", response_model=RoiSubZoneListResponse)
async def get_roi_sub_zones(product_type: str) -> RoiSubZoneListResponse:
    zones = inspection_service.get_roi_sub_zones(product_type)
    return RoiSubZoneListResponse(
        product_type=product_type,
        zones=[to_roi_sub_zone_response(zone) for zone in zones],
    )


@router.patch("/roi-sub-zones/{zone_id}", response_model=RoiSubZoneResponse)
async def update_roi_sub_zone(zone_id: str, payload: RoiSubZoneUpdateRequest) -> RoiSubZoneResponse:
    points = [(p.x, p.y) for p in payload.points] if payload.points is not None else None
    try:
        zone = inspection_service.update_roi_sub_zone(
            zone_id=zone_id,
            threshold=payload.threshold,
            label=payload.label,
            points=points,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if zone is None:
        raise HTTPException(status_code=404, detail="Sub-ROI zone not found")
    return to_roi_sub_zone_response(zone)


@router.delete("/roi-sub-zones/{zone_id}")
async def delete_roi_sub_zone(zone_id: str) -> dict:
    deleted = inspection_service.delete_roi_sub_zone(zone_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Sub-ROI zone not found")
    return {"deleted": True, "zone_id": zone_id}
