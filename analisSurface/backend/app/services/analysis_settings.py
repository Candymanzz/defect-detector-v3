from dataclasses import asdict, dataclass, fields
from typing import Any


@dataclass
class AnalysisSettings:
    """Параметры алгоритма на product_type; подробности — docs/ANALYSIS_SETTINGS.md."""

    default_threshold: float = 0.25
    use_patchcore: bool = True
    min_defect_area: int = 6
    min_scratch_aspect: float = 3.0
    min_diff_signal: float = 12.0
    diff_percentile: float = 98.0
    scratch_score_floor: float = 0.35
    scratch_aspect_floor: float = 4.5
    edge_suppress_factor: float = 0.2
    text_min_contrast: int = 55
    text_structure_threshold: int = 30
    contrast_loss_boost: float = 2.0
    contrast_loss_ref_grad: float = 40.0
    contrast_loss_cur_grad: float = 15.0
    enable_clahe: bool = True
    clahe_clip_limit: float = 1.2
    fp_recheck_enabled: bool = True
    fp_trigger_diff_q90: float = 22.0
    # False: кадр уже отцентрирован upstream (positioning/geometry) — ORB/H/ECC не трогаем.
    enable_internal_alignment: bool = False

    @classmethod
    def defaults(cls) -> "AnalysisSettings":
        return cls()

    @classmethod
    def field_names(cls) -> set[str]:
        return {field.name for field in fields(cls)}

    @classmethod
    def from_overrides(cls, overrides: dict[str, Any]) -> "AnalysisSettings":
        merged = cls.defaults()
        allowed = cls.field_names()
        for key, raw_value in overrides.items():
            if key not in allowed or raw_value is None:
                continue
            current = getattr(merged, key)
            if isinstance(current, bool):
                setattr(merged, key, bool(raw_value))
            elif isinstance(current, int):
                setattr(merged, key, int(raw_value))
            elif isinstance(current, float):
                setattr(merged, key, float(raw_value))
            else:
                setattr(merged, key, raw_value)
        merged.validate()
        return merged

    def validate(self) -> None:
        if not 0.0 < self.default_threshold <= 1.0:
            raise ValueError("default_threshold must be in (0, 1]")
        if self.min_defect_area < 1:
            raise ValueError("min_defect_area must be >= 1")
        if self.min_scratch_aspect < 1.0:
            raise ValueError("min_scratch_aspect must be >= 1")
        if self.min_diff_signal < 0.0:
            raise ValueError("min_diff_signal must be >= 0")
        if not 50.0 <= self.diff_percentile <= 100.0:
            raise ValueError("diff_percentile must be in [50, 100]")
        if not 0.0 <= self.scratch_score_floor <= 1.0:
            raise ValueError("scratch_score_floor must be in [0, 1]")
        if self.scratch_aspect_floor < 1.0:
            raise ValueError("scratch_aspect_floor must be >= 1")
        if not 0.0 <= self.edge_suppress_factor <= 1.0:
            raise ValueError("edge_suppress_factor must be in [0, 1]")
        if not 0 <= self.text_min_contrast <= 255:
            raise ValueError("text_min_contrast must be in [0, 255]")
        if not 0 <= self.text_structure_threshold <= 255:
            raise ValueError("text_structure_threshold must be in [0, 255]")
        if self.contrast_loss_boost < 1.0:
            raise ValueError("contrast_loss_boost must be >= 1")
        if self.contrast_loss_ref_grad < 0.0:
            raise ValueError("contrast_loss_ref_grad must be >= 0")
        if self.contrast_loss_cur_grad < 0.0:
            raise ValueError("contrast_loss_cur_grad must be >= 0")
        if self.clahe_clip_limit <= 0.0:
            raise ValueError("clahe_clip_limit must be > 0")
        if self.fp_trigger_diff_q90 < 0.0:
            raise ValueError("fp_trigger_diff_q90 must be >= 0")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
