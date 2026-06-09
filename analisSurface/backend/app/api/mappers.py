from app.api.dependencies import inspection_service
from app.api.schemas import (
    AnalysisSettingsResponse,
    AnalysisSettingsValues,
    FPZonePoint,
    FPZoneResponse,
    InspectResponse,
    InspectWithVisualsResponse,
    RoiSubZonePoint,
    RoiSubZoneResponse,
    RoiSubZoneScoreResponse,
    ShmImageOutput,
    ShmVisualsResponse,
)
from app.services.analysis_settings import AnalysisSettings
from app.services.shm_io import ShmImageOutputInfo


def to_analysis_settings_values(settings: AnalysisSettings) -> AnalysisSettingsValues:
    payload = settings.to_dict()
    return AnalysisSettingsValues(**payload)


def to_analysis_settings_response(analysis_profile: str, overrides: dict) -> AnalysisSettingsResponse:
    effective = AnalysisSettings.from_overrides(overrides)
    defaults = AnalysisSettings.defaults()
    return AnalysisSettingsResponse(
        analysis_profile=analysis_profile,
        settings=to_analysis_settings_values(effective),
        defaults=to_analysis_settings_values(defaults),
        overrides=overrides,
    )


def to_inspect_with_visuals_response(result) -> InspectWithVisualsResponse:
    base = to_inspect_response(result)
    return InspectWithVisualsResponse(
        **base.model_dump(),
        aligned_image_b64=(
            inspection_service.encode_image_b64(result.aligned_image)
            if result.aligned_image is not None
            else None
        ),
        diff_map_b64=(
            inspection_service.encode_image_b64(result.diff_map) if result.diff_map is not None else None
        ),
        heatmap_b64=(
            inspection_service.encode_image_b64(result.heatmap) if result.heatmap is not None else None
        ),
        segmentation_mask_b64=(
            inspection_service.encode_image_b64(result.segmentation_mask)
            if result.segmentation_mask is not None
            else None
        ),
    )


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
        main_roi_score=result.main_roi_score,
        sub_zone_scores=[
            RoiSubZoneScoreResponse(
                zone_id=entry.zone_id,
                label=entry.label,
                anomaly_score=entry.anomaly_score,
                threshold=entry.threshold,
                status=entry.status,
            )
            for entry in (result.sub_zone_scores or [])
        ],
    )


def to_roi_sub_zone_response(zone) -> RoiSubZoneResponse:
    return RoiSubZoneResponse(
        id=zone.id,
        product_type=zone.product_type,
        points=[RoiSubZonePoint(x=x, y=y) for x, y in zone.points],
        threshold=zone.threshold,
        label=zone.label,
        created_at=zone.created_at,
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
