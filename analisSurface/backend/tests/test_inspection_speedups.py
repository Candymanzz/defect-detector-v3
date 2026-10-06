"""Оптимизации скорости не должны менять результат инспекции ни на бит."""

import cv2
import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.analysis_settings_presets import expand_merged
from app.services.inspection_service import InspectionService


def _frames(seed: int = 3):
    rng = np.random.default_rng(seed)
    base = cv2.GaussianBlur(rng.integers(100, 160, (240, 320, 3), dtype=np.uint8), (0, 0), 1.5)
    cv2.putText(base, "LOT 42", (40, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (20, 20, 20), 2)
    current = base.copy()
    cv2.line(current, (60, 60), (200, 80), (80, 80, 80), 2)
    current = cv2.add(current, rng.integers(0, 8, current.shape, dtype=np.uint8))
    return base, current


@pytest.fixture()
def service(tmp_path):
    svc = InspectionService(
        learned_normals_dir=tmp_path / "n", reviews_dir=tmp_path / "r", session_wipe=True
    )
    svc._anomaly_engine = None
    return svc


@pytest.mark.parametrize("clahe_clip", [1.0, 2.5])
def test_reference_memo_gives_identical_diff_map(service, clahe_clip):
    reference, current = _frames()
    settings = AnalysisSettings.from_overrides(
        {**expand_merged(0.4, 0.6), "clahe_clip_limit": clahe_clip, "enable_clahe": True}
    )
    plain = service._compute_advanced_difference(current, reference, settings, vertical_compensation=True)
    memo: dict = {}
    for _ in range(2):  # второй проход — из кэша
        cached = service._compute_advanced_difference(
            current, reference, settings, vertical_compensation=True, reference_memo=memo
        )
        assert np.array_equal(plain, cached)
    assert memo, "memo must be populated"
    assert not memo["gray"].flags.writeable


def test_reference_memo_changes_with_reference(service):
    reference, current = _frames()
    service.set_reference_frame("p", reference)
    key_a = service._reference_memo_for("p", reference.shape, None, None, None)
    service.set_reference_frame("p", np.ascontiguousarray(reference[::-1]))
    key_b = service._reference_memo_for("p", reference.shape, None, None, None)
    assert key_a is not key_b


def test_unmasked_main_score_matches_full_recompute(service):
    reference, current = _frames()
    settings = AnalysisSettings.from_overrides(expand_merged(0.4, 0.6))
    diff = service._compute_advanced_difference(current, reference, settings, vertical_compensation=True)
    score, seg = service._run_anomaly_model(diff, settings, decision_threshold=0.4)
    polygon = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
    kwargs = dict(
        filtered_diff_map=diff, segmentation_mask=seg, inspection_threshold=0.4,
        settings=settings, polygon=polygon, sub_zones=[],
    )
    assert service._score_inspection_regions(**kwargs) == service._score_inspection_regions(
        **kwargs, unmasked_main_score=score
    )
    # diff вне ROI не нулевой -> быстрый путь отключён, результат всё равно совпадает
    inner = [(0.25, 0.25), (0.75, 0.25), (0.75, 0.75), (0.25, 0.75)]
    kwargs["polygon"] = inner
    assert service._score_inspection_regions(**kwargs) == service._score_inspection_regions(
        **kwargs, unmasked_main_score=score
    )
