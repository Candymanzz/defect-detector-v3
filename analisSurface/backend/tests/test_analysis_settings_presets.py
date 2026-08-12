import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.analysis_settings_presets import expand_pro, expand_simple, lerp_anchor


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
    assert coarse["min_defect_area"] > sensitive["min_defect_area"]
    assert coarse["diff_percentile"] > sensitive["diff_percentile"]


def test_expand_simple_rejects_invalid() -> None:
    with pytest.raises(ValueError, match="threshold"):
        expand_simple(0.0, 0.5)
    with pytest.raises(ValueError, match="sensitivity"):
        expand_simple(0.25, 1.5)


def test_expand_pro_mid_matches_defaults_groups() -> None:
    expanded = expand_pro(0.3, 0.5, 0.5, 0.5, 0.5, 0.5)
    defaults = AnalysisSettings.defaults().to_dict()
    assert expanded["default_threshold"] == 0.3
    for key in (
        "min_diff_signal",
        "min_defect_area",
        "diff_percentile",
        "min_scratch_aspect",
        "edge_suppress_factor",
        "text_min_contrast",
        "enable_clahe",
        "clahe_clip_limit",
        "use_patchcore",
        "fp_recheck_enabled",
    ):
        assert expanded[key] == defaults[key], key


def test_expand_pro_noise_knob_moves_noise_fields() -> None:
    low = expand_pro(0.25, 0.0, 0.5, 0.5, 0.5, 0.5)
    high = expand_pro(0.25, 1.0, 0.5, 0.5, 0.5, 0.5)
    assert low["min_diff_signal"] > high["min_diff_signal"]
    # scratch group stays at defaults
    assert low["min_scratch_aspect"] == high["min_scratch_aspect"]


def test_lerp_anchor_endpoints() -> None:
    assert lerp_anchor("min_diff_signal", 0.0) == 40.0
    assert lerp_anchor("min_diff_signal", 0.5) == 12.0
    assert lerp_anchor("min_diff_signal", 1.0) == 4.0
