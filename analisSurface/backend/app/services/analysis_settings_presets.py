"""Разворачивает abstract-ручки (simple/pro) в полный AnalysisSettings."""

from __future__ import annotations

from typing import Any

from app.services.analysis_settings import AnalysisSettings

# 0 = грубо (меньше ложных), 0.5 = заводские defaults, 1 = максимально чутко.
_DEFAULTS = AnalysisSettings.defaults().to_dict()

_COARSE: dict[str, Any] = {
    "use_patchcore": True,
    "min_defect_area": 50,
    "min_scratch_aspect": 5.0,
    "min_diff_signal": 40.0,
    "diff_percentile": 99.5,
    "scratch_score_floor": 0.2,
    "scratch_aspect_floor": 6.0,
    "edge_suppress_factor": 0.05,
    "text_min_contrast": 90,
    "text_structure_threshold": 50,
    "contrast_loss_boost": 1.2,
    "contrast_loss_ref_grad": 60.0,
    "contrast_loss_cur_grad": 25.0,
    # Keep True so sensitivity does not cliff-switch CLAHE on/off around 0.25–0.5;
    # strength is controlled only by clahe_clip_limit (≈1.0 ≈ off in the pipeline).
    "enable_clahe": True,
    "clahe_clip_limit": 1.0,
    "fp_recheck_enabled": True,
    "fp_trigger_diff_q90": 22.0,
}

_SENSITIVE: dict[str, Any] = {
    "use_patchcore": True,
    "min_defect_area": 3,
    "min_scratch_aspect": 2.0,
    "min_diff_signal": 4.0,
    "diff_percentile": 95.0,
    "scratch_score_floor": 0.5,
    "scratch_aspect_floor": 3.0,
    "edge_suppress_factor": 0.5,
    "text_min_contrast": 30,
    "text_structure_threshold": 15,
    "contrast_loss_boost": 3.0,
    "contrast_loss_ref_grad": 25.0,
    "contrast_loss_cur_grad": 8.0,
    "enable_clahe": True,
    "clahe_clip_limit": 2.0,
    "fp_recheck_enabled": True,
    "fp_trigger_diff_q90": 22.0,
}

_NOISE_FIELDS = ("min_diff_signal", "min_defect_area", "diff_percentile")
_SCRATCH_FIELDS = ("min_scratch_aspect", "scratch_score_floor", "scratch_aspect_floor")
_EDGE_FIELDS = ("edge_suppress_factor",)
_TEXT_FIELDS = (
    "text_min_contrast",
    "text_structure_threshold",
    "contrast_loss_boost",
    "contrast_loss_ref_grad",
    "contrast_loss_cur_grad",
)
_PREPROCESS_FIELDS = ("enable_clahe", "clahe_clip_limit")
_FIXED_FIELDS = ("use_patchcore", "fp_recheck_enabled", "fp_trigger_diff_q90")


def _validate_unit_interval(name: str, value: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not 0.0 <= parsed <= 1.0:
        raise ValueError(f"{name} must be in [0, 1]")
    return parsed


def _validate_threshold(value: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("threshold must be a number") from exc
    if not 0.0 < parsed <= 1.0:
        raise ValueError("threshold must be in (0, 1]")
    return parsed


def _lerp_numeric(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def _lerp_value(a: Any, b: Any, t: float) -> Any:
    if isinstance(a, bool) and isinstance(b, bool):
        return b if t >= 0.5 else a
    if isinstance(a, int) and isinstance(b, int) and not isinstance(a, bool) and not isinstance(b, bool):
        return int(round(_lerp_numeric(float(a), float(b), t)))
    return float(_lerp_numeric(float(a), float(b), t))


def lerp_anchor(field: str, t: float) -> Any:
    """Интерполяция поля: [0, 0.5] COARSE→DEFAULT, [0.5, 1] DEFAULT→SENSITIVE."""
    t = max(0.0, min(1.0, float(t)))
    coarse = _COARSE[field]
    default = _DEFAULTS[field]
    sensitive = _SENSITIVE[field]
    if t <= 0.5:
        local = t / 0.5
        return _lerp_value(coarse, default, local)
    local = (t - 0.5) / 0.5
    return _lerp_value(default, sensitive, local)


def _expand_fields(fields: tuple[str, ...], t: float, target: dict[str, Any]) -> None:
    for field in fields:
        target[field] = lerp_anchor(field, t)


def expand_simple(threshold: float, sensitivity: float) -> dict[str, Any]:
    """Две ручки → полный dict overrides (все поля AnalysisSettings)."""
    threshold = _validate_threshold(threshold)
    sensitivity = _validate_unit_interval("sensitivity", sensitivity)

    result: dict[str, Any] = {"default_threshold": threshold}
    algorithm_fields = [name for name in AnalysisSettings.field_names() if name != "default_threshold"]
    for field in algorithm_fields:
        result[field] = lerp_anchor(field, sensitivity)

    AnalysisSettings.from_overrides(result)
    return result


def expand_pro(
    threshold: float,
    noise_tolerance: float,
    scratch_sensitivity: float,
    edge_suppression: float,
    text_handling: float,
    preprocess_strength: float,
) -> dict[str, Any]:
    """Pro-ручки → полный dict overrides.

    Каждая ручка ∈ [0, 1]: 0 = грубее для группы, 1 = чувствительнее, 0.5 = defaults.
    """
    threshold = _validate_threshold(threshold)
    noise_tolerance = _validate_unit_interval("noise_tolerance", noise_tolerance)
    scratch_sensitivity = _validate_unit_interval("scratch_sensitivity", scratch_sensitivity)
    edge_suppression = _validate_unit_interval("edge_suppression", edge_suppression)
    text_handling = _validate_unit_interval("text_handling", text_handling)
    preprocess_strength = _validate_unit_interval("preprocess_strength", preprocess_strength)

    result: dict[str, Any] = {"default_threshold": threshold}
    for field in _FIXED_FIELDS:
        result[field] = _DEFAULTS[field]

    _expand_fields(_NOISE_FIELDS, noise_tolerance, result)
    _expand_fields(_SCRATCH_FIELDS, scratch_sensitivity, result)
    _expand_fields(_EDGE_FIELDS, edge_suppression, result)
    _expand_fields(_TEXT_FIELDS, text_handling, result)
    _expand_fields(_PREPROCESS_FIELDS, preprocess_strength, result)

    AnalysisSettings.from_overrides(result)
    return result
