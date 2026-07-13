import pytest

from app.services.analysis_settings import AnalysisSettings


def test_defaults_are_valid() -> None:
    settings = AnalysisSettings.defaults()
    settings.validate()
    assert settings.default_threshold == 0.25
    assert settings.use_patchcore is True


def test_from_overrides_applies_known_fields() -> None:
    settings = AnalysisSettings.from_overrides(
        {
            "default_threshold": 0.4,
            "use_patchcore": False,
            "min_defect_area": 12,
            "unknown_field": 999,
        }
    )
    assert settings.default_threshold == 0.4
    assert settings.use_patchcore is False
    assert settings.min_defect_area == 12


@pytest.mark.parametrize(
    "field,value,match",
    [
        ("default_threshold", 0.0, "default_threshold"),
        ("default_threshold", 1.5, "default_threshold"),
        ("diff_percentile", 40.0, "diff_percentile"),
        ("text_min_contrast", 300, "text_min_contrast"),
    ],
)
def test_validate_rejects_invalid_values(field: str, value: float, match: str) -> None:
    settings = AnalysisSettings.defaults()
    setattr(settings, field, value)
    with pytest.raises(ValueError, match=match):
        settings.validate()
