"""Разворачивает abstract-ручки (simple + силы групп) в полный AnalysisSettings."""

from __future__ import annotations

from typing import Any

from app.services.analysis_settings import AnalysisSettings

_STOCK = AnalysisSettings.defaults().to_dict()

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

STRENGTH_FIELD_NAMES = (
    "noise_tolerance",
    "scratch_sensitivity",
    "edge_suppression",
    "text_handling",
    "preprocess_strength",
)

DEFAULT_STRENGTHS: dict[str, float] = {name: 100.0 for name in STRENGTH_FIELD_NAMES}
MAX_STRENGTH = 100.0
MIN_EFFECTIVE_SENSITIVITY = 0.0
MAX_EFFECTIVE_SENSITIVITY = 100.0
STRENGTH_SCALE_VERSION = "direct_0_100_v3"


def migrate_legacy_strengths(raw: dict[str, Any]) -> dict[str, float]:
    """Старая прямая шкала 0–200 → прямая 0–100 с ограничением сверху."""
    return normalize_strengths({
        name: min(MAX_STRENGTH, max(0.0, float(raw.get(name, 100.0))))
        for name in STRENGTH_FIELD_NAMES
    })


def migrate_intermediate_centered_strengths(raw: dict[str, Any]) -> dict[str, float]:
    """Переходный centered_0_100 → эквивалентная прямая шкала 0–100."""
    return normalize_strengths({
        name: min(MAX_STRENGTH, max(0.0, 4.0 * float(raw.get(name, 50.0)) - 100.0))
        for name in STRENGTH_FIELD_NAMES
    })


def migrate_centered_v2_strengths(raw: dict[str, Any]) -> dict[str, float]:
    """centered_minus50_150_v2 → прямая шкала 0–100 с ограничением краёв."""
    return normalize_strengths({
        name: min(MAX_STRENGTH, max(0.0, 2.0 * float(raw.get(name, 75.0)) - 50.0))
        for name in STRENGTH_FIELD_NAMES
    })


def _validate_unit_interval(name: str, value: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not 0.0 <= parsed <= 1.0:
        raise ValueError(f"{name} must be in [0, 1]")
    return parsed


def _validate_percent(name: str, value: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not 0.0 <= parsed <= MAX_STRENGTH:
        raise ValueError(f"{name} must be in [0, {MAX_STRENGTH:g}]")
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


def _stock_coeff(field: str, sensitivity_0_to_100: float) -> float:
    sensitivity = max(MIN_EFFECTIVE_SENSITIVITY, min(MAX_EFFECTIVE_SENSITIVITY, float(sensitivity_0_to_100)))
    stock = _STOCK[field]
    coarse_ratio = float(_COARSE[field]) / float(stock)
    sensitive_ratio = float(_SENSITIVE[field]) / float(stock)
    t = (sensitivity - 50.0) / 50.0
    if t <= 0.0:
        return _lerp_numeric(coarse_ratio, 1.0, t + 1.0)
    return _lerp_numeric(1.0, sensitive_ratio, t)


def _apply_stock_value(field: str, effective_sensitivity: float) -> Any:
    stock = _STOCK[field]
    if isinstance(stock, bool):
        return stock
    coeff = _stock_coeff(field, effective_sensitivity)
    raw = float(stock) * coeff
    if isinstance(stock, int) and not isinstance(stock, bool):
        return int(round(raw, 10))
    return round(raw, 6)


def _apply_stock_fields(fields: tuple[str, ...], effective_sensitivity: float, target: dict[str, Any]) -> None:
    for field in fields:
        target[field] = _apply_stock_value(field, effective_sensitivity)


def effective_group_sensitivity(global_sensitivity_0_100: float, strength_0_100: float) -> float:
    """Прямая шкала: итог группы = общая чувствительность × сила группы / 100."""
    global_s = max(0.0, min(100.0, float(global_sensitivity_0_100)))
    strength = max(0.0, min(MAX_STRENGTH, float(strength_0_100)))
    return global_s * strength / 100.0


def normalize_strengths(raw: dict[str, Any] | None) -> dict[str, float]:
    merged = dict(DEFAULT_STRENGTHS)
    if not raw:
        return merged
    for name in STRENGTH_FIELD_NAMES:
        if name in raw:
            merged[name] = _validate_percent(name, raw[name])
    return merged


def expand_merged(
    threshold: float,
    sensitivity: float,
    noise_tolerance: float = 100.0,
    scratch_sensitivity: float = 100.0,
    edge_suppression: float = 100.0,
    text_handling: float = 100.0,
    preprocess_strength: float = 100.0,
) -> dict[str, Any]:
    """Чувствительность (simple) + силы групп (detailed) → полный AnalysisSettings.

    sensitivity ∈ [0, 1] — общий множитель для всех групп.
    Силы ∈ [0, 100] и напрямую умножаются на общую чувствительность.
    """
    threshold = _validate_threshold(threshold)
    sensitivity = _validate_unit_interval("sensitivity", sensitivity)
    strengths = normalize_strengths(
        {
            "noise_tolerance": noise_tolerance,
            "scratch_sensitivity": scratch_sensitivity,
            "edge_suppression": edge_suppression,
            "text_handling": text_handling,
            "preprocess_strength": preprocess_strength,
        }
    )
    sensitivity_100 = sensitivity * 100.0

    result: dict[str, Any] = {
        "default_threshold": threshold,
        "inspection_enabled": sensitivity > 0.0,
    }
    for field in _FIXED_FIELDS:
        result[field] = _STOCK[field]

    _apply_stock_fields(
        _NOISE_FIELDS,
        effective_group_sensitivity(sensitivity_100, strengths["noise_tolerance"]),
        result,
    )
    _apply_stock_fields(
        _SCRATCH_FIELDS,
        effective_group_sensitivity(sensitivity_100, strengths["scratch_sensitivity"]),
        result,
    )
    _apply_stock_fields(
        _EDGE_FIELDS,
        effective_group_sensitivity(sensitivity_100, strengths["edge_suppression"]),
        result,
    )
    _apply_stock_fields(
        _TEXT_FIELDS,
        effective_group_sensitivity(sensitivity_100, strengths["text_handling"]),
        result,
    )
    _apply_stock_fields(
        _PREPROCESS_FIELDS,
        effective_group_sensitivity(sensitivity_100, strengths["preprocess_strength"]),
        result,
    )

    AnalysisSettings.from_overrides(result)
    return result


def expand_simple(threshold: float, sensitivity: float) -> dict[str, Any]:
    """Simple без сохранённых значений групп — все группы полностью следуют общей чувствительности."""
    return expand_merged(threshold, sensitivity)
