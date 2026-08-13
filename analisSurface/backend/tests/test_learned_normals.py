import numpy as np
import pytest

from app.services.learned_normals import (
    DefectCandidate,
    _convert_legacy_template,
    _fit_template,
    _mask_geometry,
    filter_review_candidates,
)


def _candidate(
    candidate_id: str,
    *,
    area: int,
    width: int,
    height: int,
    diff: float = 60.0,
    diff_mean: float | None = None,
    diff_q90: float | None = None,
    diff_max: float | None = None,
) -> DefectCandidate:
    placeholder = np.zeros((2, 2), dtype=np.uint8)
    return DefectCandidate(
        id=candidate_id,
        bbox=(0, 0, width, height),
        bbox_norm=(0.0, 0.0, 0.1, 0.1),
        polygon_norm=[],
        area=area,
        diff_mean=diff if diff_mean is None else diff_mean,
        diff_q90=diff if diff_q90 is None else diff_q90,
        diff_max=diff if diff_max is None else diff_max,
        score=0.5,
        mask=placeholder,
        mask_template=placeholder,
        diff_template=placeholder,
        appearance_template=placeholder,
    )


def test_review_filter_is_relative_to_strongest_candidate() -> None:
    small_frame_candidates = [
        _candidate("main-small", area=20, width=5, height=5),
        _candidate("noise-small", area=2, width=2, height=2),
    ]
    large_frame_candidates = [
        _candidate("main-large", area=2000, width=50, height=50),
        _candidate("noise-large", area=200, width=20, height=20),
    ]

    assert [item.id for item in filter_review_candidates(small_frame_candidates)] == ["main-small"]
    assert [item.id for item in filter_review_candidates(large_frame_candidates)] == ["main-large"]


def test_review_filter_keeps_multiple_candidates_with_meaningful_relative_impact() -> None:
    candidates = [
        _candidate("strongest", area=1000, width=40, height=30),
        _candidate("meaningful", area=300, width=24, height=15),
        _candidate("noise", area=40, width=7, height=7),
    ]

    assert [item.id for item in filter_review_candidates(candidates, min_relative_impact=0.20)] == [
        "strongest",
        "meaningful",
    ]


def test_review_filter_is_invariant_to_resolution_scale() -> None:
    rng = np.random.default_rng(42)
    original: list[DefectCandidate] = []
    scaled: list[DefectCandidate] = []
    scale = 4
    for index in range(100):
        width = int(rng.integers(2, 80))
        height = int(rng.integers(2, 80))
        area = int(rng.integers(1, width * height + 1))
        diff_mean = float(rng.uniform(5.0, 140.0))
        diff_q90 = float(rng.uniform(diff_mean, 200.0))
        diff_max = float(rng.uniform(diff_q90, 255.0))
        values = {
            "candidate_id": f"candidate-{index}",
            "diff_mean": diff_mean,
            "diff_q90": diff_q90,
            "diff_max": diff_max,
        }
        original.append(_candidate(area=area, width=width, height=height, **values))
        scaled.append(
            _candidate(
                area=area * scale * scale,
                width=width * scale,
                height=height * scale,
                **values,
            )
        )

    original_ids = [item.id for item in filter_review_candidates(original, min_relative_impact=0.20)]
    scaled_ids = [item.id for item in filter_review_candidates(scaled, min_relative_impact=0.20)]
    assert scaled_ids == original_ids


def test_review_filter_is_invariant_to_common_diff_intensity_change() -> None:
    candidates = [
        _candidate("first", area=900, width=35, height=30, diff=100.0),
        _candidate("second", area=350, width=25, height=18, diff=80.0),
        _candidate("noise", area=80, width=10, height=9, diff=140.0),
    ]
    darker = [
        _candidate(
            item.id,
            area=item.area,
            width=item.bbox[2],
            height=item.bbox[3],
            diff=item.diff_mean * 0.4,
        )
        for item in candidates
    ]

    assert [item.id for item in filter_review_candidates(darker)] == [
        item.id for item in filter_review_candidates(candidates)
    ]


def test_review_filter_keeps_thin_scratch_but_hides_equal_area_spot() -> None:
    candidates = [
        _candidate("main", area=1000, width=36, height=36),
        _candidate("thin-scratch", area=150, width=100, height=2),
        _candidate("spot", area=150, width=13, height=13),
    ]

    assert [item.id for item in filter_review_candidates(candidates, min_relative_impact=0.20)] == [
        "main",
        "thin-scratch",
    ]


def test_review_filter_keeps_low_contrast_scratch_with_material_relative_impact() -> None:
    candidates = [
        _candidate("main", area=10000, width=100, height=100, diff=50.0),
        _candidate(
            "important-scratch",
            area=2500,
            width=250,
            height=8,
            diff_mean=28.0,
            diff_q90=33.0,
            diff_max=36.0,
        ),
        _candidate(
            "weak-trace",
            area=700,
            width=150,
            height=7,
            diff_mean=28.0,
            diff_q90=31.0,
            diff_max=35.0,
        ),
    ]

    assert [item.id for item in filter_review_candidates(candidates)] == [
        "main",
        "important-scratch",
    ]


def test_review_filter_keeps_significant_scratch_below_broad_defect_cutoff() -> None:
    candidates = [
        _candidate("broad-defect", area=10_000, width=125, height=80, diff=50.0),
        _candidate(
            "visible-scratch",
            area=1_000,
            width=260,
            height=10,
            diff_mean=27.0,
            diff_q90=31.0,
            diff_max=38.0,
        ),
        _candidate(
            "weak-line-noise",
            area=450,
            width=180,
            height=8,
            diff_mean=25.0,
            diff_q90=30.0,
            diff_max=34.0,
        ),
    ]

    assert [item.id for item in filter_review_candidates(candidates)] == [
        "broad-defect",
        "visible-scratch",
    ]


def test_review_filter_keeps_secondary_score_driving_component() -> None:
    candidates = [
        _candidate("main-defect", area=1_000, width=40, height=30, diff=60.0),
        _candidate("secondary-defect", area=190, width=22, height=12, diff=58.0),
        _candidate("weak-noise", area=70, width=10, height=8, diff=45.0),
    ]

    assert [item.id for item in filter_review_candidates(candidates)] == [
        "main-defect",
        "secondary-defect",
    ]


def test_residual_filter_keeps_new_absolute_signal_but_drops_weak_background() -> None:
    candidates = [
        _candidate(
            "weak-background",
            area=500,
            width=40,
            height=20,
            diff_mean=10.0,
            diff_q90=16.0,
            diff_max=20.0,
        ),
        _candidate(
            "new-defect",
            area=250,
            width=31,
            height=18,
            diff_mean=8.0,
            diff_q90=19.0,
            diff_max=29.0,
        ),
    ]

    assert [
        item.id
        for item in filter_review_candidates(
            candidates,
            baseline_maximum_impact=100_000.0,
        )
    ] == ["new-defect"]


def test_review_filter_uses_weighted_diff_energy_not_area_only() -> None:
    candidates = [
        _candidate("main", area=1000, width=35, height=35, diff=80.0),
        _candidate("weak-large", area=400, width=22, height=22, diff=15.0),
        _candidate("strong-medium", area=300, width=20, height=18, diff=90.0),
    ]

    assert [item.id for item in filter_review_candidates(candidates, min_relative_impact=0.20)] == [
        "main",
        "strong-medium",
    ]


def test_review_filter_handles_twenty_noise_components() -> None:
    candidates = [_candidate("main", area=1200, width=40, height=35, diff=70.0)]
    candidates.extend(
        _candidate(f"noise-{index}", area=20 + index, width=7, height=7, diff=35.0)
        for index in range(20)
    )

    assert [item.id for item in filter_review_candidates(candidates)] == ["main"]


def test_review_filter_keeps_strongest_candidate_for_zero_diff_input() -> None:
    candidates = [
        _candidate("first", area=10, width=4, height=3, diff=0.0),
        _candidate("second", area=100, width=12, height=10, diff=0.0),
    ]

    assert [item.id for item in filter_review_candidates(candidates)] == ["first"]


def test_shape_template_preserves_wide_and_tall_aspect_ratios() -> None:
    wide = _fit_template(np.full((10, 50), 255, dtype=np.uint8), binary=True)
    tall = _fit_template(np.full((50, 10), 255, dtype=np.uint8), binary=True)

    wide_geometry = _mask_geometry(wide)
    tall_geometry = _mask_geometry(tall)
    assert wide_geometry is not None
    assert tall_geometry is not None
    assert wide_geometry.aspect == pytest.approx(5.0, rel=0.1)
    assert tall_geometry.aspect == pytest.approx(0.2, rel=0.1)


def test_legacy_stretched_template_recovers_saved_bbox_aspect() -> None:
    old_stretched = np.full((64, 64), 255, dtype=np.uint8)
    restored = _convert_legacy_template(
        old_stretched,
        (0.1, 0.2, 0.5, 0.1),
        binary=True,
    )

    geometry = _mask_geometry(restored)
    assert geometry is not None
    assert geometry.aspect == pytest.approx(5.0, rel=0.1)
