"""Справочные тесты: какие settings получаются из simple/pro knobs.

Запуск с печатью таблицы:
  python3 -m pytest tests/test_analysis_settings_preset_matrix.py -s -k table

Запуск только snapshot-проверок:
  python3 -m pytest tests/test_analysis_settings_preset_matrix.py -q
"""

from __future__ import annotations

import pytest

from app.services.analysis_settings_presets import expand_pro, expand_simple

# Поля, которые чаще всего крутят при калибровке.
_KEY_FIELDS = (
    "default_threshold",
    "min_diff_signal",
    "min_defect_area",
    "diff_percentile",
    "min_scratch_aspect",
    "scratch_score_floor",
    "scratch_aspect_floor",
    "edge_suppress_factor",
    "text_min_contrast",
    "text_structure_threshold",
    "contrast_loss_boost",
    "contrast_loss_ref_grad",
    "contrast_loss_cur_grad",
    "enable_clahe",
    "clahe_clip_limit",
    "use_patchcore",
    "fp_recheck_enabled",
    "fp_trigger_diff_q90",
)


def _pick(settings: dict, fields: tuple[str, ...] = _KEY_FIELDS) -> dict:
    return {key: settings[key] for key in fields}


# ---------------------------------------------------------------------------
# Simple: sensitivity → ожидаемые значения (threshold фиксируем 0.25)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "sensitivity,expected",
    [
        (
            0.0,
            {
                "default_threshold": 0.25,
                "min_diff_signal": 40.0,
                "min_defect_area": 50,
                "diff_percentile": 99.5,
                "min_scratch_aspect": 5.0,
                "scratch_score_floor": 0.2,
                "scratch_aspect_floor": 6.0,
                "edge_suppress_factor": 0.05,
                "text_min_contrast": 90,
                "text_structure_threshold": 50,
                "contrast_loss_boost": 1.2,
                "contrast_loss_ref_grad": 60.0,
                "contrast_loss_cur_grad": 25.0,
                "enable_clahe": False,
                "clahe_clip_limit": 1.0,
                "use_patchcore": True,
                "fp_recheck_enabled": True,
                "fp_trigger_diff_q90": 22.0,
            },
        ),
        (
            0.25,
            {
                "default_threshold": 0.25,
                "min_diff_signal": 26.0,
                "min_defect_area": 28,
                "diff_percentile": 98.75,
                "min_scratch_aspect": 4.0,
                "scratch_score_floor": 0.275,
                "scratch_aspect_floor": 5.25,
                "edge_suppress_factor": 0.125,
                "text_min_contrast": 72,
                "text_structure_threshold": 40,
                "contrast_loss_boost": 1.6,
                "contrast_loss_ref_grad": 50.0,
                "contrast_loss_cur_grad": 20.0,
                "enable_clahe": True,  # bool: переключается на defaults при local >= 0.5
                "clahe_clip_limit": 1.1,
                "use_patchcore": True,
                "fp_recheck_enabled": True,
                "fp_trigger_diff_q90": 22.0,
            },
        ),
        (
            0.5,
            {
                "default_threshold": 0.25,
                "min_diff_signal": 12.0,
                "min_defect_area": 6,
                "diff_percentile": 98.0,
                "min_scratch_aspect": 3.0,
                "scratch_score_floor": 0.35,
                "scratch_aspect_floor": 4.5,
                "edge_suppress_factor": 0.2,
                "text_min_contrast": 55,
                "text_structure_threshold": 30,
                "contrast_loss_boost": 2.0,
                "contrast_loss_ref_grad": 40.0,
                "contrast_loss_cur_grad": 15.0,
                "enable_clahe": True,
                "clahe_clip_limit": 1.2,
                "use_patchcore": True,
                "fp_recheck_enabled": True,
                "fp_trigger_diff_q90": 22.0,
            },
        ),
        (
            0.75,
            {
                "default_threshold": 0.25,
                "min_diff_signal": 8.0,
                "min_defect_area": 4,
                "diff_percentile": 96.5,
                "min_scratch_aspect": 2.5,
                "scratch_score_floor": 0.425,
                "scratch_aspect_floor": 3.75,
                "edge_suppress_factor": 0.35,
                "text_min_contrast": 42,
                "text_structure_threshold": 22,
                "contrast_loss_boost": 2.5,
                "contrast_loss_ref_grad": 32.5,
                "contrast_loss_cur_grad": 11.5,
                "enable_clahe": True,
                "clahe_clip_limit": 1.6,
                "use_patchcore": True,
                "fp_recheck_enabled": True,
                "fp_trigger_diff_q90": 22.0,
            },
        ),
        (
            1.0,
            {
                "default_threshold": 0.25,
                "min_diff_signal": 4.0,
                "min_defect_area": 3,
                "diff_percentile": 95.0,
                "min_scratch_aspect": 2.0,
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
                "use_patchcore": True,
                "fp_recheck_enabled": True,
                "fp_trigger_diff_q90": 22.0,
            },
        ),
    ],
    ids=["sens_0", "sens_0.25", "sens_0.5", "sens_0.75", "sens_1"],
)
def test_simple_sensitivity_matrix(sensitivity: float, expected: dict) -> None:
    actual = _pick(expand_simple(0.25, sensitivity))
    assert actual == expected


def test_simple_threshold_only_changes_default_threshold() -> None:
    a = expand_simple(0.1, 0.5)
    b = expand_simple(0.9, 0.5)
    assert a["default_threshold"] == 0.1
    assert b["default_threshold"] == 0.9
    for key in _KEY_FIELDS:
        if key == "default_threshold":
            continue
        assert a[key] == b[key], key


# ---------------------------------------------------------------------------
# Pro: каждая ручка двигает только свою группу
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "knob,value,field,expected",
    [
        ("noise_tolerance", 0.0, "min_diff_signal", 40.0),
        ("noise_tolerance", 0.5, "min_diff_signal", 12.0),
        ("noise_tolerance", 1.0, "min_diff_signal", 4.0),
        ("noise_tolerance", 0.0, "min_defect_area", 50),
        ("noise_tolerance", 1.0, "min_defect_area", 3),
        ("noise_tolerance", 0.0, "diff_percentile", 99.5),
        ("noise_tolerance", 1.0, "diff_percentile", 95.0),
        ("scratch_sensitivity", 0.0, "min_scratch_aspect", 5.0),
        ("scratch_sensitivity", 0.5, "min_scratch_aspect", 3.0),
        ("scratch_sensitivity", 1.0, "min_scratch_aspect", 2.0),
        ("scratch_sensitivity", 0.0, "scratch_score_floor", 0.2),
        ("scratch_sensitivity", 1.0, "scratch_score_floor", 0.5),
        ("edge_suppression", 0.0, "edge_suppress_factor", 0.05),
        ("edge_suppression", 0.5, "edge_suppress_factor", 0.2),
        ("edge_suppression", 1.0, "edge_suppress_factor", 0.5),
        ("text_handling", 0.0, "text_min_contrast", 90),
        ("text_handling", 0.5, "text_min_contrast", 55),
        ("text_handling", 1.0, "text_min_contrast", 30),
        ("text_handling", 0.0, "contrast_loss_boost", 1.2),
        ("text_handling", 1.0, "contrast_loss_boost", 3.0),
        ("preprocess_strength", 0.0, "enable_clahe", False),
        ("preprocess_strength", 0.5, "enable_clahe", True),
        ("preprocess_strength", 1.0, "clahe_clip_limit", 2.0),
        ("preprocess_strength", 0.0, "clahe_clip_limit", 1.0),
        ("preprocess_strength", 0.5, "clahe_clip_limit", 1.2),
    ],
)
def test_pro_knob_field_matrix(knob: str, value: float, field: str, expected: object) -> None:
    knobs = {
        "threshold": 0.25,
        "noise_tolerance": 0.5,
        "scratch_sensitivity": 0.5,
        "edge_suppression": 0.5,
        "text_handling": 0.5,
        "preprocess_strength": 0.5,
    }
    knobs[knob] = value
    expanded = expand_pro(
        knobs["threshold"],
        knobs["noise_tolerance"],
        knobs["scratch_sensitivity"],
        knobs["edge_suppression"],
        knobs["text_handling"],
        knobs["preprocess_strength"],
    )
    assert expanded[field] == expected


def test_pro_knob_does_not_move_other_groups() -> None:
    """noise_tolerance=0 двигает noise, scratch/edge/text/preprocess остаются на defaults."""
    expanded = expand_pro(0.25, 0.0, 0.5, 0.5, 0.5, 0.5)
    mid = expand_pro(0.25, 0.5, 0.5, 0.5, 0.5, 0.5)
    assert expanded["min_diff_signal"] != mid["min_diff_signal"]
    for field in (
        "min_scratch_aspect",
        "scratch_score_floor",
        "edge_suppress_factor",
        "text_min_contrast",
        "enable_clahe",
        "clahe_clip_limit",
    ):
        assert expanded[field] == mid[field], field


# ---------------------------------------------------------------------------
# Печатная таблица для ручной проверки (pytest -s -k table)
# ---------------------------------------------------------------------------

def test_print_simple_sensitivity_table(capsys: pytest.CaptureFixture[str]) -> None:
    sensitivities = (0.0, 0.25, 0.5, 0.75, 1.0)
    rows = {s: _pick(expand_simple(0.25, s)) for s in sensitivities}

    cols = ["field"] + [f"s={s:g}" for s in sensitivities]
    widths = {col: len(col) for col in cols}
    for field in _KEY_FIELDS:
        widths["field"] = max(widths["field"], len(field))
        for s in sensitivities:
            widths[f"s={s:g}"] = max(widths[f"s={s:g}"], len(str(rows[s][field])))

    def fmt_row(values: list[str]) -> str:
        return " | ".join(value.ljust(widths[col]) for value, col in zip(values, cols))

    lines = [
        "",
        "=== simple: threshold=0.25, sensitivity → settings ===",
        fmt_row(cols),
        "-+-".join("-" * widths[col] for col in cols),
    ]
    for field in _KEY_FIELDS:
        lines.append(fmt_row([field] + [str(rows[s][field]) for s in sensitivities]))
    lines.append("")

    print("\n".join(lines))
    # всегда проходит — нужен только вывод
    assert rows[0.5]["min_diff_signal"] == 12.0


def test_print_pro_knob_table(capsys: pytest.CaptureFixture[str]) -> None:
    levels = (0.0, 0.5, 1.0)
    groups = {
        "noise_tolerance": ("min_diff_signal", "min_defect_area", "diff_percentile"),
        "scratch_sensitivity": ("min_scratch_aspect", "scratch_score_floor", "scratch_aspect_floor"),
        "edge_suppression": ("edge_suppress_factor",),
        "text_handling": (
            "text_min_contrast",
            "text_structure_threshold",
            "contrast_loss_boost",
            "contrast_loss_ref_grad",
            "contrast_loss_cur_grad",
        ),
        "preprocess_strength": ("enable_clahe", "clahe_clip_limit"),
    }

    lines = ["", "=== pro: одна ручка меняется, остальные = 0.5, threshold=0.25 ==="]
    for knob, fields in groups.items():
        lines.append(f"\n[{knob}]")
        header = f"{'field':28} | " + " | ".join(f"{v:g}".rjust(8) for v in levels)
        lines.append(header)
        lines.append("-" * len(header))
        for field in fields:
            cells = []
            for value in levels:
                knobs = {
                    "noise_tolerance": 0.5,
                    "scratch_sensitivity": 0.5,
                    "edge_suppression": 0.5,
                    "text_handling": 0.5,
                    "preprocess_strength": 0.5,
                }
                knobs[knob] = value
                expanded = expand_pro(0.25, **knobs)
                cells.append(str(expanded[field]).rjust(8))
            lines.append(f"{field:28} | " + " | ".join(cells))
    lines.append("")

    print("\n".join(lines))
    assert True
