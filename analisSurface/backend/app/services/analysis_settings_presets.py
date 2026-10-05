"""Разворачивает abstract-ручки (simple + силы групп) в полный AnalysisSettings."""

from __future__ import annotations

from typing import Any

from app.detector_settings import get_python_detector_settings
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
    "far_edge_boost",
)

# Дальний край: отдельная ручка, не зависит от общей чувствительности.
# 50 = текущие значения по умолчанию, 0 = только базовая перспективная компенсация.
_FAR_EDGE_GAIN = (1.2, 1.35, 2.2)
_FAR_EDGE_EDGE_FACTOR = (0.2, 0.35, 0.8)

DEFAULT_STRENGTHS: dict[str, float] = {name: 50.0 for name in STRENGTH_FIELD_NAMES}


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
    if not 0.0 <= parsed <= 100.0:
        raise ValueError(f"{name} must be in [0, 100]")
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


def _configured_ranges() -> dict[str, Any]:
    """python_detector.setting_ranges из config: {поле: {at_0, at_50, at_100}}."""
    raw = get_python_detector_settings().get("setting_ranges")
    return raw if isinstance(raw, dict) else {}


def _knob_internal(name: str, percent_0_100: float) -> float:
    """Фронтовые 0..100 % -> внутреннее значение ручки по python_detector.knob_ranges.

    knob_ranges.<name>: {at_0, at_100} (по умолчанию 0 и 100); между ними линейно.
    """
    percent = max(0.0, min(100.0, float(percent_0_100)))
    raw = get_python_detector_settings().get("knob_ranges")
    entry = raw.get(name) if isinstance(raw, dict) else None
    if not isinstance(entry, dict):
        return percent
    try:
        at_0 = float(entry.get("at_0", 0.0))
        at_100 = float(entry.get("at_100", 100.0))
    except (TypeError, ValueError) as exc:
        raise ValueError(f"knob_ranges.{name} at_0/at_100 must be numbers") from exc
    return _lerp_numeric(at_0, at_100, percent / 100.0)


def _anchors(field: str, defaults: tuple[float, float, float]) -> tuple[float, float, float]:
    """Значения поля при 0 / 50 / 100 %. Недостающие якоря берутся из дефолтов."""
    entry = _configured_ranges().get(field)
    if not isinstance(entry, dict):
        return defaults
    resolved = []
    for key, default in zip(("at_0", "at_50", "at_100"), defaults):
        value = entry.get(key)
        if value is None:
            resolved.append(default)
            continue
        try:
            resolved.append(float(value))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"setting_ranges.{field}.{key} must be a number") from exc
    return resolved[0], resolved[1], resolved[2]


def _interpolate(percent_0_100: float, anchors: tuple[float, float, float]) -> float:
    """Кусочно-линейно: 0 -> at_0, 50 -> at_50, 100 -> at_100; любой шаг (0.1 %) считается сам."""
    at_0, at_50, at_100 = anchors
    percent = max(0.0, min(100.0, float(percent_0_100)))
    if percent <= 50.0:
        return _lerp_numeric(at_0, at_50, percent / 50.0)
    return _lerp_numeric(at_50, at_100, (percent - 50.0) / 50.0)


def _far_edge_value(field: str, strength_0_100: float, low_mid_high: tuple[float, float, float]) -> float:
    return _interpolate(strength_0_100, _anchors(field, low_mid_high))


def _stock_coeff(field: str, sensitivity_0_100: float) -> float:
    stock = float(_STOCK[field])
    anchors = _anchors(field, (float(_COARSE[field]), stock, float(_SENSITIVE[field])))
    return _interpolate(sensitivity_0_100, anchors) / stock


def _apply_stock_value(field: str, sensitivity_0_100: float) -> Any:
    stock = _STOCK[field]
    if isinstance(stock, bool):
        return stock
    value = _interpolate(
        sensitivity_0_100,
        _anchors(field, (float(_COARSE[field]), float(stock), float(_SENSITIVE[field]))),
    )
    if isinstance(stock, int):
        return int(round(value))
    return round(value, 6)


def _apply_stock_fields(fields: tuple[str, ...], sensitivity_0_100: float, target: dict[str, Any]) -> None:
    for field in fields:
        target[field] = _apply_stock_value(field, sensitivity_0_100)


def effective_group_sensitivity(global_sensitivity_0_100: float, change_strength_0_100: float) -> float:
    """Сила изменения группы: 50 = стандарт, 0 = группа на стоке, 100 = усиленный отклик."""
    global_s = max(0.0, min(100.0, float(global_sensitivity_0_100)))
    strength = max(0.0, min(100.0, float(change_strength_0_100)))
    delta = global_s - 50.0
    strength_mult = strength / 50.0
    return max(0.0, min(100.0, 50.0 + delta * strength_mult))


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
    noise_tolerance: float = 50.0,
    scratch_sensitivity: float = 50.0,
    edge_suppression: float = 50.0,
    text_handling: float = 50.0,
    preprocess_strength: float = 50.0,
    far_edge_boost: float = 50.0,
) -> dict[str, Any]:
    """Чувствительность (simple) + силы групп (detailed) → полный AnalysisSettings.

    sensitivity ∈ [0, 1] — единственная ручка чувствительности.
    Силы ∈ [0, 100], 50 = стандарт: насколько сильно группа следует за sensitivity.
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
            "far_edge_boost": far_edge_boost,
        }
    )
    sensitivity_100 = _knob_internal("sensitivity", sensitivity * 100.0)
    strengths = {name: _knob_internal(name, value) for name, value in strengths.items()}

    result: dict[str, Any] = {"default_threshold": threshold}
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

    result["far_edge_max_gain"] = round(_far_edge_value("far_edge_max_gain", strengths["far_edge_boost"], _FAR_EDGE_GAIN), 6)
    result["far_edge_edge_suppress_factor"] = round(
        _far_edge_value("far_edge_edge_suppress_factor", strengths["far_edge_boost"], _FAR_EDGE_EDGE_FACTOR), 6
    )

    AnalysisSettings.from_overrides(result)
    return result


def expand_simple(threshold: float, sensitivity: float) -> dict[str, Any]:
    """Simple без сохранённых сил — все группы со стандартной силой 50."""
    return expand_merged(threshold, sensitivity)
