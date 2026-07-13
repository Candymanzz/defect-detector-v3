from types import SimpleNamespace

from app.api.mappers import (
    to_analysis_settings_response,
    to_fp_zone_response,
    to_inspect_response,
    to_roi_sub_zone_response,
)


def test_to_analysis_settings_response_merges_defaults() -> None:
    response = to_analysis_settings_response("bench", {"default_threshold": 0.4})

    assert response.analysis_profile == "bench"
    assert response.settings.default_threshold == 0.4
    assert response.defaults.default_threshold == 0.25
    assert response.overrides == {"default_threshold": 0.4}


def test_to_inspect_response_maps_sub_zone_scores() -> None:
    result = SimpleNamespace(
        product_type="bench",
        status="ГОДЕН",
        anomaly_score=0.1,
        threshold=0.5,
        detector_id="det-1",
        raw_anomaly_score=0.08,
        rechecked_zones_count=1,
        recheck_adjustment=0.02,
        rechecked_zone_ids=["z1"],
        main_roi_score=0.09,
        sub_zone_scores=[
            SimpleNamespace(
                zone_id="z1",
                label="zone",
                anomaly_score=0.2,
                threshold=0.5,
                status="ГОДЕН",
            )
        ],
    )

    response = to_inspect_response(result)

    assert response.product_type == "bench"
    assert response.sub_zone_scores[0].zone_id == "z1"
    assert response.rechecked_zone_ids == ["z1"]


def test_to_roi_sub_zone_response_maps_points() -> None:
    zone = SimpleNamespace(
        id="roi-1",
        product_type="bench",
        points=[(0.1, 0.2), (0.3, 0.4)],
        threshold=0.3,
        label="main",
        created_at="2026-01-01",
    )

    response = to_roi_sub_zone_response(zone)

    assert response.id == "roi-1"
    assert response.points[0].x == 0.1
    assert response.points[1].y == 0.4


def test_to_fp_zone_response_maps_norm_points() -> None:
    zone = SimpleNamespace(
        id="fp-1",
        product_type="bench",
        points_norm_heatmap=[(0.0, 0.0), (1.0, 1.0)],
        points_norm_ref=[(0.1, 0.1)],
        heatmap_w=64,
        heatmap_h=48,
        created_at="2026-01-01",
        note="test",
    )

    response = to_fp_zone_response(zone)

    assert response.heatmap_w == 64
    assert response.points_norm_ref[0].x == 0.1
    assert response.note == "test"
