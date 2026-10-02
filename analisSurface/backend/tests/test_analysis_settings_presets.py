import json

import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.analysis_settings_presets import (
    effective_group_sensitivity,
    expand_merged,
    expand_simple,
    migrate_legacy_strengths,
    normalize_strengths,
    _stock_coeff,
)
from app.services.inspection_service import InspectionService


def test_expand_simple_mid_matches_defaults() -> None:
    expanded = expand_simple(0.25, 0.5)
    defaults = AnalysisSettings.defaults().to_dict()
    assert expanded["default_threshold"] == 0.25
    for key, value in defaults.items():
        if key == "default_threshold":
            continue
        assert expanded[key] == value, key


def test_expand_simple_coarse_vs_sensitive() -> None:
    coarse = expand_simple(0.25, 0.0)
    sensitive = expand_simple(0.25, 1.0)
    assert coarse["min_diff_signal"] > sensitive["min_diff_signal"]
    assert coarse["inspection_enabled"] is False
    assert sensitive["inspection_enabled"] is True


def test_zero_global_sensitivity_disables_inspection_for_all_group_values() -> None:
    for group_value in (0, 25, 75, 100):
        expanded = expand_merged(0.25, 0.0, noise_tolerance=group_value)
        assert expanded["inspection_enabled"] is False


def test_expand_merged_high_scratch_strength() -> None:
    base = expand_merged(0.25, 0.75, scratch_sensitivity=50)
    boosted = expand_merged(0.25, 0.75, scratch_sensitivity=100)
    assert boosted["min_scratch_aspect"] < base["min_scratch_aspect"]


def test_expand_merged_zero_strength_keeps_group_at_stock() -> None:
    expanded = expand_merged(0.25, 1.0, noise_tolerance=0)
    assert expanded["min_diff_signal"] > expand_merged(0.25, 0.0)["min_diff_signal"]
    assert expanded["min_scratch_aspect"] == expand_merged(0.25, 1.0)["min_scratch_aspect"]


def test_expand_merged_matches_simple_with_default_strengths() -> None:
    simple = expand_simple(0.25, 0.8)
    merged = expand_merged(0.25, 0.8)
    assert simple == merged


def test_normalize_strengths_ignores_threshold() -> None:
    strengths = normalize_strengths({"threshold": 0.3, "noise_tolerance": 80})
    assert "threshold" not in strengths
    assert strengths["noise_tolerance"] == 80.0
    assert strengths["scratch_sensitivity"] == 75.0


def test_effective_group_sensitivity() -> None:
    assert effective_group_sensitivity(80, 75) == 80.0
    assert effective_group_sensitivity(100, 0) == -50.0
    assert effective_group_sensitivity(100, 25) == 0.0
    assert effective_group_sensitivity(100, 50) == 50.0
    assert effective_group_sensitivity(100, 75) == 100.0
    assert effective_group_sensitivity(100, 100) == 150.0
    assert effective_group_sensitivity(50, 0) == -25.0
    assert effective_group_sensitivity(50, 75) == 50.0
    assert effective_group_sensitivity(0, 100) == 0.0


def test_detailed_groups_can_boost_above_global_sensitivity() -> None:
    strengths = {
        "noise_tolerance": 100,
        "scratch_sensitivity": 100,
        "edge_suppression": 100,
        "text_handling": 100,
        "preprocess_strength": 100,
    }
    assert expand_merged(0.25, 0.5, **strengths) == expand_simple(0.25, 0.75)
    assert expand_merged(0.25, 1.0, noise_tolerance=100)["min_diff_signal"] < expand_simple(
        0.25, 1.0
    )["min_diff_signal"]


def test_all_groups_reach_both_extended_endpoints() -> None:
    normal = expand_merged(0.25, 1.0)
    coarse = expand_merged(0.25, 1.0, **dict.fromkeys(normalize_strengths(None), 0))
    extreme = expand_merged(0.25, 1.0, **dict.fromkeys(normalize_strengths(None), 100))
    AnalysisSettings.from_overrides(coarse)
    AnalysisSettings.from_overrides(extreme)

    for field in ("min_diff_signal", "min_defect_area", "diff_percentile", "min_scratch_aspect",
                  "scratch_aspect_floor", "text_min_contrast", "text_structure_threshold",
                  "contrast_loss_ref_grad", "contrast_loss_cur_grad"):
        assert extreme[field] < normal[field], field
        assert coarse[field] > normal[field], field
    for field in ("scratch_score_floor", "edge_suppress_factor", "contrast_loss_boost", "clahe_clip_limit"):
        assert extreme[field] > normal[field], field
        assert coarse[field] < normal[field], field


def test_effective_above_100_is_same_from_equivalent_knobs() -> None:
    assert expand_merged(0.25, 1.0, noise_tolerance=80)["min_diff_signal"] == expand_merged(
        0.25, 0.8, noise_tolerance=93.75
    )["min_diff_signal"]


def test_detailed_strength_above_new_maximum_is_rejected() -> None:
    with pytest.raises(ValueError, match=r"\[0, 100\]"):
        expand_merged(0.25, 0.5, noise_tolerance=100.1)


def test_all_detailed_groups_are_scaled_by_global_sensitivity() -> None:
    strengths = {
        "noise_tolerance": 80,
        "scratch_sensitivity": 75,
        "edge_suppression": 70,
        "text_handling": 60,
        "preprocess_strength": 50,
    }
    actual = expand_merged(0.25, 0.8, **strengths)
    for group, field in (
        ("noise_tolerance", "min_diff_signal"),
        ("scratch_sensitivity", "min_scratch_aspect"),
        ("edge_suppression", "edge_suppress_factor"),
        ("text_handling", "text_min_contrast"),
        ("preprocess_strength", "clahe_clip_limit"),
    ):
        expected = expand_merged(0.25, 1.0, **{group: 25 + 0.8 * (strengths[group] - 25)})
        assert actual[field] == expected[field]


def test_migrate_legacy_strengths_preserves_multiplier_positions() -> None:
    migrated = migrate_legacy_strengths({"noise_tolerance": 0, "scratch_sensitivity": 200})
    assert migrated["noise_tolerance"] == 25.0
    assert migrated["scratch_sensitivity"] == 100.0
    assert migrated["edge_suppression"] == 75.0


def test_migrate_legacy_pro_knobs_keeps_unspecified_groups_neutral() -> None:
    migrated = InspectionService._migrate_legacy_pro_knobs({"noise_tolerance": 0.5})
    assert migrated["noise_tolerance"] == 50.0
    assert migrated["scratch_sensitivity"] == 75.0


def test_stock_coeff_endpoints() -> None:
    assert _stock_coeff("min_diff_signal", 50) == pytest.approx(1.0)


def test_saved_knobs_recalculate_old_overrides_on_load(tmp_path) -> None:
    settings_file = tmp_path / "analysis_settings.json"
    settings_file.write_text(json.dumps([{
        "analysis_profile": "saved-profile",
        "overrides": {"min_diff_signal": 12.0},
        "simple_knobs": {"threshold": 0.25, "sensitivity": 0.8},
        "detailed_knobs": {"noise_tolerance": 90.0},
    }]), encoding="utf-8")
    service = object.__new__(InspectionService)
    service._analysis_settings_file = settings_file

    service._load_analysis_settings()

    assert service._analysis_settings_overrides["saved-profile"]["min_diff_signal"] == expand_simple(
        0.25, 0.72
    )["min_diff_signal"]
    assert service._analysis_settings_detailed_knobs["saved-profile"]["noise_tolerance"] == 70.0


def test_fractional_detailed_knobs_survive_disk_roundtrip(tmp_path) -> None:
    settings_file = tmp_path / "analysis_settings.json"
    saved = object.__new__(InspectionService)
    saved._analysis_settings_file = settings_file
    saved._analysis_settings_overrides = {}
    saved._analysis_settings_simple_knobs = {
        "saved-profile": {"threshold": 0.3, "sensitivity": 0.8}
    }
    saved._analysis_settings_detailed_knobs = {
        "saved-profile": {"noise_tolerance": 75.1}
    }
    saved._save_analysis_settings()

    loaded = object.__new__(InspectionService)
    loaded._analysis_settings_file = settings_file
    loaded._load_analysis_settings()

    assert loaded._analysis_settings_detailed_knobs["saved-profile"]["noise_tolerance"] == 75.1
    assert json.loads(settings_file.read_text(encoding="utf-8"))[0]["strength_scale"] == "centered_minus50_150_v2"
