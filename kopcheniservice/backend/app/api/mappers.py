from app.api.schemas import (
    FPZonePoint,
    FPZoneResponse,
    InspectResponse,
    ShmImageOutput,
    ShmVisualsResponse,
)
from app.services.shm_io import ShmImageOutputInfo


def to_inspect_response(result) -> InspectResponse:
    return InspectResponse(
        product_type=result.product_type,
        status=result.status,
        anomaly_score=result.anomaly_score,
        threshold=result.threshold,
        detector_id=result.detector_id,
        raw_anomaly_score=result.raw_anomaly_score,
        rechecked_zones_count=result.rechecked_zones_count,
        recheck_adjustment=result.recheck_adjustment,
        rechecked_zone_ids=result.rechecked_zone_ids or [],
    )


def to_shm_image_output(output: ShmImageOutputInfo | None) -> ShmImageOutput | None:
    if output is None:
        return None
    return ShmImageOutput(
        path=output.path,
        width=output.width,
        height=output.height,
        stride=output.stride,
        channels=output.channels,
        dtype=output.dtype,
    )


def to_visuals_response(
    result,
    visual_outputs: dict[str, ShmImageOutputInfo],
) -> ShmVisualsResponse:
    return ShmVisualsResponse(
        product_type=result.product_type,
        detector_id=result.detector_id,
        aligned_image_u8=to_shm_image_output(visual_outputs.get("aligned_image")),
        diff_map_u8=to_shm_image_output(visual_outputs.get("diff_map")),
        heatmap_u8=to_shm_image_output(visual_outputs.get("heatmap")),
        segmentation_mask_u8=to_shm_image_output(visual_outputs.get("segmentation_mask")),
    )


def to_fp_zone_response(zone) -> FPZoneResponse:
    return FPZoneResponse(
        id=zone.id,
        product_type=zone.product_type,
        points_norm_heatmap=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_heatmap],
        points_norm_ref=[FPZonePoint(x=x, y=y) for x, y in zone.points_norm_ref],
        heatmap_w=zone.heatmap_w,
        heatmap_h=zone.heatmap_h,
        created_at=zone.created_at,
        note=zone.note,
    )
