import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.analysis_settings_presets import (
    effective_group_sensitivity,
    expand_merged,
    expand_simple,
    normalize_strengths,
    _stock_coeff,
)


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


def test_expand_merged_high_scratch_strength() -> None:
    base = expand_merged(0.25, 0.75, scratch_sensitivity=50)
    boosted = expand_merged(0.25, 0.75, scratch_sensitivity=100)
    assert boosted["min_scratch_aspect"] < base["min_scratch_aspect"]


def test_expand_merged_zero_strength_keeps_group_at_stock() -> None:
    defaults = AnalysisSettings.defaults().to_dict()
    expanded = expand_merged(0.25, 1.0, noise_tolerance=0)
    assert expanded["min_diff_signal"] == defaults["min_diff_signal"]
    assert expanded["min_scratch_aspect"] != defaults["min_scratch_aspect"]


def test_expand_merged_matches_simple_with_default_strengths() -> None:
    simple = expand_simple(0.25, 0.8)
    merged = expand_merged(0.25, 0.8)
    assert simple == merged


def test_normalize_strengths_ignores_threshold() -> None:
    strengths = normalize_strengths({"threshold": 0.3, "noise_tolerance": 80})
    assert "threshold" not in strengths
    assert strengths["noise_tolerance"] == 80.0
    assert strengths["scratch_sensitivity"] == 50.0


def test_effective_group_sensitivity() -> None:
    assert effective_group_sensitivity(50, 50) == 50.0
    assert effective_group_sensitivity(100, 0) == 50.0
    assert effective_group_sensitivity(75, 50) == pytest.approx(75.0)


def test_stock_coeff_endpoints() -> None:
    assert _stock_coeff("min_diff_signal", 50) == pytest.approx(1.0)
