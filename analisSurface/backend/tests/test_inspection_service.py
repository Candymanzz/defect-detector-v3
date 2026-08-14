import json
from pathlib import Path

import cv2
import numpy as np
import pytest

from app.services.analysis_settings_presets import expand_simple
from app.services.inspection_geometry import (
    polygon_area,
    validate_polygon_inside_parent,
    validate_polygon_points,
)
from app.services.inspection_service import InspectionService


def test_validate_polygon_points_rejects_out_of_range() -> None:
    with pytest.raises(ValueError, match="inside \\[0, 1\\]"):
        validate_polygon_points([(0.0, 0.0), (1.2, 0.5), (0.5, 1.0)], "ROI polygon")


def test_validate_polygon_inside_parent() -> None:
    parent = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
    child = [(0.1, 0.1), (0.9, 0.1), (0.9, 0.9)]
    normalized = validate_polygon_inside_parent(child, parent, "Sub-ROI polygon")
    assert len(normalized) == 3
    assert polygon_area(normalized) > 0.0


def test_inspect_identical_frames_passes(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    result = inspection_service.inspect_frame("bench", gray_frame.copy(), threshold=0.5)

    assert result.product_type == "bench"
    assert result.status == "ГОДЕН"
    assert result.anomaly_score < result.threshold
    assert result.inspection_id is not None
    history = inspection_service.get_learning_review(result.inspection_id)
    assert history is not None
    assert history["original_status"] == "ГОДЕН"


def test_inspection_history_keeps_pass_and_fail_frames(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    inspection_service.set_reference_frame("history", gray_frame)
    passed = inspection_service.inspect_frame("history", gray_frame.copy(), threshold=0.5, include_visuals=False)
    defective = gray_frame.copy()
    defective[10:30, 10:50] = 255
    failed = inspection_service.inspect_frame("history", defective, threshold=0.1, include_visuals=False)

    reviews = {item["inspection_id"]: item for item in inspection_service.list_learning_reviews()}
    assert reviews[passed.inspection_id]["original_status"] == "ГОДЕН"
    assert reviews[failed.inspection_id]["original_status"] == "БРАК"
    assert reviews[passed.inspection_id]["defects_count"] == 0
    assert reviews[failed.inspection_id]["defects_count"] >= 1


def test_accept_all_review_defects_saves_score_driving_candidates(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((240, 360, 3), 78, dtype=np.uint8)
    for x in range(20, 350, 40):
        cv2.line(reference, (x, 18), (x, 220), (91, 91, 91), 1)
    for y in range(30, 230, 40):
        cv2.line(reference, (15, y), (345, y), (84, 84, 84), 1)
    current = reference.copy()
    current[35:64, 34:78] = 206
    current[152:188, 254:309] = 220
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("accept-all", reference)

    result = inspection_service.inspect_frame(
        "accept-all",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(result.inspection_id)
    assert review is not None
    # Обе области заметно влияют на результат и должны быть доступны оператору.
    assert len(review["defects"]) == 2

    accepted = inspection_service.accept_all_review_defects_as_normal(
        result.inspection_id,
        note="whole image accepted",
    )

    assert accepted["accepted_count"] == 2
    assert len(accepted["accepted_cases"]) == 2
    updated = inspection_service.get_learning_review(result.inspection_id)
    assert updated is not None
    assert all(item["manually_accepted"] for item in updated["defects"])
    replay = inspection_service.inspect_frame(
        "accept-all",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert replay.status == "ГОДЕН"
    assert replay.learned_normal_matches_count == 2
    with pytest.raises(ValueError, match="already accepted"):
        inspection_service.accept_all_review_defects_as_normal(result.inspection_id)


def test_accept_all_review_defects_handles_mixed_shapes_and_sizes(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((240, 360, 3), 78, dtype=np.uint8)
    for x in range(20, 350, 40):
        cv2.line(reference, (x, 18), (x, 220), (91, 91, 91), 1)
    for y in range(30, 230, 40):
        cv2.line(reference, (15, y), (345, y), (84, 84, 84), 1)
    current = reference.copy()
    current[34:65, 32:77] = 210
    cv2.line(current, (145, 80), (275, 101), (236, 236, 236), 3)
    cv2.circle(current, (285, 175), 13, (212, 212, 212), -1)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("accept-all-mixed", reference)

    result = inspection_service.inspect_frame(
        "accept-all-mixed",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(result.inspection_id)
    assert review is not None
    # Все три заметно влияющие области разных форм должны быть показаны и
    # добавлены в норму одной кнопкой.
    assert len(review["defects"]) == 3

    accepted = inspection_service.accept_all_review_defects_as_normal(result.inspection_id)
    assert accepted["accepted_count"] == 3

    replay = inspection_service.inspect_frame(
        "accept-all-mixed",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert replay.status == "ГОДЕН"
    assert replay.learned_normal_matches_count == 3


def test_accept_all_keeps_new_important_defect_rejected(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((180, 300, 3), 80, dtype=np.uint8)
    accepted_frame = reference.copy()
    accepted_frame[35:80, 30:85] = 205
    # Слабая точка не должна сохраняться и после обучения не должна повторно
    # становиться главной причиной брака.
    accepted_frame[135:141, 130:136] = 110
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("accept-all-new-defect", reference)

    original = inspection_service.inspect_frame(
        "accept-all-new-defect",
        accepted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    inspection_service.accept_all_review_defects_as_normal(original.inspection_id)

    new_frame = accepted_frame.copy()
    new_frame[95:135, 205:265] = 230
    result = inspection_service.inspect_frame(
        "accept-all-new-defect",
        new_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count >= 1
    assert result.status == "БРАК"


def test_inspect_without_reference_raises(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    with pytest.raises(ValueError, match="Reference for product_type"):
        inspection_service.inspect_frame("missing", gray_frame)


def test_inspect_detects_large_difference(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    defective = gray_frame.copy()
    defective[10:30, 10:50] = 255

    result = inspection_service.inspect_frame("bench", defective, threshold=0.01)

    assert result.status == "БРАК"
    assert result.anomaly_score >= result.threshold


def test_scoped_camera_inspection_uses_saved_profile_threshold_when_request_omits_it(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    profile = "bench-lan1"
    scoped_product_type = f"{profile}#cam=0"
    inspection_service._analysis_settings_overrides = {
        profile: expand_simple(threshold=0.01, sensitivity=1.0),
    }
    inspection_service.set_reference_frame(scoped_product_type, gray_frame)
    defective = gray_frame.copy()
    defective[10:30, 10:50] = 255

    result = inspection_service.inspect_frame(
        scoped_product_type,
        defective,
        threshold=None,
        alignment_h_ref_to_cur=[1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
    )

    assert result.threshold == pytest.approx(0.01)
    assert result.anomaly_score >= result.threshold


def test_identity_homography_skips_realign(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    assert inspection_service._is_identity_homography(identity)
    assert not inspection_service._is_identity_homography([1.0, 0.0, 5.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0])

    aligned = inspection_service._align_to_reference(
        gray_frame.copy(),
        gray_frame,
        "bench",
        alignment_h_ref_to_cur=identity,
    )
    assert np.array_equal(aligned, gray_frame)


def test_activity_score_does_not_saturate_on_moderate_mask() -> None:
    # Раньше active_ratio*1.2 давал 1.0 уже при ~0.84 покрытия маски.
    score = InspectionService._activity_score(diff_q90=40.0, diff_max=80.0, active_ratio=0.85)
    assert score < 1.0
    assert score > 0.3


def test_score_region_uses_real_mask_not_bbox(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    h, w = gray_frame.shape[:2]
    region_mask = np.zeros((h, w), dtype=bool)
    # Узкий треугольник: bbox намного больше самой ROI.
    region_mask[10:50, 20:25] = True
    region_mask[10:30, 25:40] = True

    diff_map = np.zeros_like(gray_frame)
    # Сильный diff только ВНЕ треугольника, но ВНУТРИ bbox — раньше раздувал score.
    diff_map[10:50, 40:70] = (0, 0, 255)
    segmentation_mask = diff_map.copy()

    score = inspection_service._score_region(
        diff_map,
        segmentation_mask,
        region_mask,
        inspection_service.get_analysis_settings("bench"),
    )
    assert score < 0.2


def test_set_and_get_reference(inspection_service: InspectionService, gray_frame: np.ndarray) -> None:
    inspection_service.set_reference_frame("product-a", gray_frame)
    stored = inspection_service.get_reference("product-a")

    assert stored is not None
    assert stored.shape == gray_frame.shape
    assert np.array_equal(stored, gray_frame)


def test_operator_acceptance_is_post_factum_and_applies_to_future_frames(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    acceptable = gray_frame.copy()
    acceptable[10:30, 10:50] = 255

    original = inspection_service.inspect_frame(
        "bench",
        acceptable,
        threshold=0.1,
        include_visuals=False,
    )
    assert original.status == "БРАК"
    assert original.inspection_id
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    assert len(review["defects"]) == 1

    accepted = inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
        note="оператор подтвердил норму",
    )
    assert accepted["affects_original_pipeline_decision"] is False
    assert accepted["original_status"] == "БРАК"
    assert inspection_service.get_learning_review(original.inspection_id)["original_status"] == "БРАК"

    future = inspection_service.inspect_frame(
        "bench",
        acceptable,
        threshold=0.1,
        include_visuals=False,
    )
    assert future.learned_normal_matches_count == 1
    assert future.status == "ГОДЕН"
    assert future.anomaly_score < future.threshold

    case_id = accepted["accepted_case"]["id"]
    preview = inspection_service.get_accepted_normal_case_image(case_id)
    assert preview is not None
    assert preview[1] == "image/png"
    assert inspection_service.delete_accepted_normal_case(case_id) is True
    assert inspection_service.list_accepted_normal_cases() == []
    review_after_delete = inspection_service.get_learning_review(original.inspection_id)
    assert review_after_delete is not None
    assert review_after_delete["defects"][0]["manually_accepted"] is False

    without_exception = inspection_service.inspect_frame(
        "bench",
        acceptable,
        threshold=0.1,
        include_visuals=False,
    )
    assert without_exception.learned_normal_matches_count == 0
    assert without_exception.status == "БРАК"


def test_delete_all_accepted_normals_clears_every_camera(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    acceptable = gray_frame.copy()
    acceptable[10:30, 10:50] = 255
    reviews = []
    for product_type in ("cam-a", "cam-b"):
        inspection_service.set_reference_frame(product_type, gray_frame)
        original = inspection_service.inspect_frame(
            product_type,
            acceptable,
            threshold=0.1,
            include_visuals=False,
        )
        review = inspection_service.get_learning_review(original.inspection_id)
        inspection_service.accept_review_defect_as_normal(
            original.inspection_id,
            review["defects"][0]["id"],
        )
        reviews.append(original.inspection_id)

    assert len(inspection_service.list_accepted_normal_cases()) == 2
    assert inspection_service.delete_all_accepted_normal_cases() == 2
    assert inspection_service.list_accepted_normal_cases() == []
    assert inspection_service.delete_all_accepted_normal_cases() == 0

    for inspection_id in reviews:
        review = inspection_service.get_learning_review(inspection_id)
        assert review is not None
        assert review["defects"][0]["manually_accepted"] is False

    future = inspection_service.inspect_frame("cam-a", acceptable, threshold=0.1, include_visuals=False)
    assert future.learned_normal_matches_count == 0
    assert future.status == "БРАК"


def test_new_defect_still_fails_next_to_learned_normal(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    acceptable = gray_frame.copy()
    acceptable[8:20, 8:28] = 255
    original = inspection_service.inspect_frame("bench", acceptable, threshold=0.1, include_visuals=False)
    review = inspection_service.get_learning_review(original.inspection_id)
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    with_new_defect = acceptable.copy()
    # Новый дефект в другом месте содержит небольшой горизонтальный фрагмент,
    # похожий на уменьшенную норму, но остальная часть креста обязана оставить БРАК.
    with_new_defect[40:58, 62:65] = 255
    with_new_defect[47:51, 50:76] = 255
    future = inspection_service.inspect_frame("bench", with_new_defect, threshold=0.1, include_visuals=False)

    assert future.learned_normal_matches_count >= 1
    assert len(future.matched_accepted_case_ids) == 1
    assert future.status == "БРАК"
    assert future.anomaly_score >= future.threshold


def test_learned_normal_matches_nearby_shifted_rescaled_shape(
    inspection_service: InspectionService,
) -> None:
    height, width = 120, 200
    # Один и тот же материал/фон по всему изделию: проверяем небольшой локальный
    # сдвиг допустимой формы, а не перенос исключения в другую часть изделия.
    reference_gray = np.full((height, width), 80, dtype=np.uint8)
    reference = np.stack([reference_gray, reference_gray, reference_gray], axis=-1)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("portable-normal", reference)

    accepted_frame = reference.copy()
    accepted_frame[20:63, 43:48] = 255
    accepted_frame[59:64, 43:129] = 255
    original = inspection_service.inspect_frame(
        "portable-normal",
        accepted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    shifted_frame = reference.copy()
    shifted_frame[27:68, 55:60] = 255
    shifted_frame[63:68, 55:109] = 255
    shifted = inspection_service.inspect_frame(
        "portable-normal",
        shifted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert shifted.learned_normal_matches_count == 1
    assert shifted.status == "ГОДЕН"
    assert shifted.anomaly_score < shifted.threshold


def test_learned_normal_does_not_match_same_shape_far_away(
    inspection_service: InspectionService,
) -> None:
    height, width = 120, 200
    reference = np.full((height, width, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("position-bound-normal", reference)

    accepted = reference.copy()
    accepted[20:63, 43:48] = 255
    accepted[59:64, 43:129] = 255
    original = inspection_service.inspect_frame(
        "position-bound-normal",
        accepted,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    far_away = reference.copy()
    far_away[35:76, 145:150] = 255
    far_away[71:76, 145:199] = 255
    result = inspection_service.inspect_frame(
        "position-bound-normal",
        far_away,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 0
    assert result.status == "БРАК"


def test_learned_normal_matches_smaller_fragmented_shape(
    inspection_service: InspectionService,
) -> None:
    height, width = 120, 200
    reference = np.full((height, width, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("fragmented-normal", reference)

    accepted_frame = reference.copy()
    accepted_frame[20:63, 43:48] = 130
    accepted_frame[59:64, 43:129] = 130
    original = inspection_service.inspect_frame(
        "fragmented-normal",
        accepted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    # Та же L-образная структура меньше и разорвана порогом на две близкие
    # компоненты. Для сравнения они должны образовать один логический дефект.
    fragmented = reference.copy()
    fragmented[27:38, 59:64] = 130
    fragmented[43:62, 55:60] = 130
    fragmented[58:62, 55:86] = 130
    result = inspection_service.inspect_frame(
        "fragmented-normal",
        fragmented,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 1
    assert result.status == "ГОДЕН"


def test_one_learned_normal_does_not_suppress_multiple_distant_matches(
    inspection_service: InspectionService,
) -> None:
    height, width = 120, 180
    reference = np.full((height, width, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("multiple-matches", reference)

    accepted_frame = reference.copy()
    accepted_frame[12:32, 12:42] = 130
    original = inspection_service.inspect_frame(
        "multiple-matches",
        accepted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    current = reference.copy()
    current[12:22, 90:105] = 130
    current[48:54, 28:37] = 130
    current[90:93, 145:150] = 130
    result = inspection_service.inspect_frame(
        "multiple-matches",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 0
    assert result.matched_accepted_case_ids == []
    assert result.status == "БРАК"


def test_larger_similar_defect_remains_reject_after_partial_match(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((100, 140, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("upper-size-limit", reference)

    accepted_frame = reference.copy()
    accepted_frame[10:20, 10:20] = 130
    original = inspection_service.inspect_frame(
        "upper-size-limit",
        accepted_frame,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    assert review is not None
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    larger = reference.copy()
    larger[45:70, 70:95] = 130
    result = inspection_service.inspect_frame(
        "upper-size-limit",
        larger,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.status == "БРАК"
    assert result.anomaly_score >= result.threshold


def test_learning_review_hides_small_dots_but_keeps_important_defect(
    inspection_service: InspectionService,
) -> None:
    height, width = 285, 609
    reference = np.full((height, width, 3), 25, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("review-importance", reference)

    current = reference.copy()
    # Значимая L-образная область.
    current[100:140, 104:109] = 90
    current[135:140, 104:137] = 90
    # Три малоконтрастные точки, которые не должны загромождать review.
    current[90:96, 245:251] = 55
    current[174:181, 463:470] = 55
    current[185:192, 205:212] = 60

    result = inspection_service.inspect_frame(
        "review-importance",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert result.status == "БРАК"
    assert result.inspection_id is not None

    review = inspection_service.get_learning_review(result.inspection_id)
    assert review is not None
    assert len(review["defects"]) == 1
    important = review["defects"][0]
    assert important["bbox"]["x"] < 150
    assert important["bbox"]["height"] > 30


@pytest.mark.parametrize("scale", [1, 2, 3])
def test_learning_review_relative_filter_is_stable_across_frame_resolutions(
    inspection_service: InspectionService,
    scale: int,
) -> None:
    height, width = 240 * scale, 320 * scale
    product_type = f"review-resolution-{scale}"
    reference = np.full((height, width, 3), 25, dtype=np.uint8)
    current = reference.copy()
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame(product_type, reference)

    current[70 * scale : 115 * scale, 45 * scale : 50 * scale] = 90
    current[110 * scale : 115 * scale, 45 * scale : 85 * scale] = 90
    current[45 * scale : 50 * scale, 180 * scale : 185 * scale] = 55
    current[150 * scale : 156 * scale, 240 * scale : 246 * scale] = 55
    current[190 * scale : 196 * scale, 130 * scale : 136 * scale] = 60

    result = inspection_service.inspect_frame(
        product_type,
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(result.inspection_id)

    assert result.status == "БРАК"
    assert review is not None
    assert len(review["defects"]) == 1
    assert review["defects"][0]["bbox"]["x"] < 100 * scale


def test_review_filter_does_not_change_score_or_pipeline_verdict(
    inspection_service: InspectionService,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    reference = np.full((180, 260, 3), 25, dtype=np.uint8)
    current = reference.copy()
    current[60:105, 50:56] = 100
    current[99:105, 50:110] = 100
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("review-enabled", reference)
    inspection_service.set_reference_frame("review-disabled", reference)

    with_review = inspection_service.inspect_frame(
        "review-enabled",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    monkeypatch.setattr(
        "app.services.inspection_service.filter_review_candidates",
        lambda candidates: [],
    )
    without_review = inspection_service.inspect_frame(
        "review-disabled",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert with_review.status == without_review.status == "БРАК"
    assert with_review.anomaly_score == pytest.approx(without_review.anomaly_score)
    assert with_review.raw_anomaly_score == pytest.approx(without_review.raw_anomaly_score)
    assert with_review.inspection_id is not None
    assert without_review.inspection_id is not None
    hidden_review = inspection_service.get_learning_review(without_review.inspection_id)
    assert hidden_review is not None
    assert hidden_review["defects"] == []


def test_learned_rectangle_does_not_suppress_round_defect(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((180, 260, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("shape-rectangle-circle", reference)
    accepted = reference.copy()
    accepted[35:55, 35:78] = 145
    first = inspection_service.inspect_frame(
        "shape-rectangle-circle",
        accepted,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(first.inspection_id)
    inspection_service.accept_review_defect_as_normal(first.inspection_id, review["defects"][0]["id"])

    current = reference.copy()
    cv2.circle(current, (175, 110), 16, (145, 145, 145), -1, cv2.LINE_AA)
    result = inspection_service.inspect_frame(
        "shape-rectangle-circle",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 0
    assert result.status == "БРАК"


def test_learned_horizontal_scratch_does_not_suppress_vertical_scratch(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((180, 260, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("shape-scratch-direction", reference)
    accepted = reference.copy()
    cv2.line(accepted, (30, 65), (115, 65), (145, 145, 145), 5, cv2.LINE_AA)
    first = inspection_service.inspect_frame(
        "shape-scratch-direction",
        accepted,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(first.inspection_id)
    inspection_service.accept_review_defect_as_normal(first.inspection_id, review["defects"][0]["id"])

    current = reference.copy()
    cv2.line(current, (185, 45), (185, 140), (145, 145, 145), 5, cv2.LINE_AA)
    result = inspection_service.inspect_frame(
        "shape-scratch-direction",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 0
    assert result.status == "БРАК"


def test_learned_bent_trace_matches_nearby_smaller_rotated_copy(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((240, 320, 3), 80, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("shape-bent-scaled", reference)

    accepted = reference.copy()
    cv2.polylines(
        accepted,
        [np.asarray([(50, 35), (50, 100), (125, 100)], dtype=np.int32)],
        False,
        (145, 145, 145),
        6,
        cv2.LINE_AA,
    )
    first = inspection_service.inspect_frame(
        "shape-bent-scaled",
        accepted,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(first.inspection_id)
    inspection_service.accept_review_defect_as_normal(
        first.inspection_id,
        review["defects"][0]["id"],
    )

    current = reference.copy()
    cv2.polylines(
        current,
        [np.asarray([(115, 90), (75, 90), (75, 55)], dtype=np.int32)],
        False,
        (145, 145, 145),
        5,
        cv2.LINE_AA,
    )
    result = inspection_service.inspect_frame(
        "shape-bent-scaled",
        current,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 1
    assert result.anomaly_score < result.threshold


def test_learning_reviews_fifo_evicts_oldest_on_disk(
    tmp_path: Path,
    gray_frame: np.ndarray,
) -> None:
    service = InspectionService(
        learned_normals_dir=tmp_path / "accepted_normals",
        reviews_dir=tmp_path / "learning_reviews",
        review_limit=3,
        session_wipe=True,
    )
    service._anomaly_engine = None
    service.set_reference_frame("fifo", gray_frame)
    inspection_ids: list[str] = []
    for _ in range(4):
        defective = gray_frame.copy()
        defective[10:30, 10:50] = 255
        result = service.inspect_frame("fifo", defective, threshold=0.1, include_visuals=False)
        assert result.inspection_id is not None
        inspection_ids.append(result.inspection_id)

    assert service.get_learning_review(inspection_ids[0]) is None
    assert not (tmp_path / "learning_reviews" / inspection_ids[0]).exists()
    assert service.get_learning_review(inspection_ids[1]) is not None
    assert service.get_learning_review(inspection_ids[3]) is not None
    assert len(service.list_learning_reviews()) == 3


def test_accepted_normal_is_wiped_on_service_restart(
    inspection_service: InspectionService,
    gray_frame: np.ndarray,
) -> None:
    inspection_service.set_reference_frame("bench", gray_frame)
    acceptable = gray_frame.copy()
    acceptable[12:28, 15:45] = 255
    original = inspection_service.inspect_frame("bench", acceptable, threshold=0.1, include_visuals=False)
    review = inspection_service.get_learning_review(original.inspection_id)
    inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )

    restarted = InspectionService(
        learned_normals_dir=inspection_service._accepted_normals.storage_dir,
        reviews_dir=inspection_service._learning_reviews.storage_dir,
        review_limit=5,
        session_wipe=True,
    )
    restarted._anomaly_engine = None
    restarted.set_reference_frame("bench", gray_frame)
    result = restarted.inspect_frame("bench", acceptable, threshold=0.1, include_visuals=False)

    assert result.learned_normal_matches_count == 0
    assert result.status == "БРАК"
    assert restarted.get_learning_review(original.inspection_id) is None


def test_new_colored_scratch_inside_accepted_broad_area_remains_a_defect(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((180, 260, 3), 45, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("glare-with-scratch", reference)

    accepted_glare = reference.copy()
    cv2.rectangle(accepted_glare, (18, 28), (152, 156), (130, 130, 130), -1)
    first = inspection_service.inspect_frame(
        "glare-with-scratch",
        accepted_glare,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    first_review = inspection_service.get_learning_review(first.inspection_id)
    assert first_review is not None
    inspection_service.accept_review_defect_as_normal(
        first.inspection_id,
        first_review["defects"][0]["id"],
    )

    glare_only = inspection_service.inspect_frame(
        "glare-with-scratch",
        accepted_glare,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert glare_only.learned_normal_matches_count == 1
    assert glare_only.status == "ГОДЕН"

    glare_and_scratch = accepted_glare.copy()
    cv2.line(glare_and_scratch, (70, 48), (72, 136), (0, 0, 255), 4, cv2.LINE_AA)
    result = inspection_service.inspect_frame(
        "glare-with-scratch",
        glare_and_scratch,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 1
    assert result.status == "БРАК"
    review = inspection_service.get_learning_review(result.inspection_id)
    assert review is not None
    assert len(review["defects"]) == 1
    scratch_bbox = review["defects"][0]["bbox"]
    assert scratch_bbox["width"] < 30
    assert scratch_bbox["height"] > 60


def test_accepted_glare_covers_weaker_shifted_field_but_not_a_new_scratch(
    inspection_service: InspectionService,
) -> None:
    height, width = 220, 320
    reference = np.full((height, width, 3), 42, dtype=np.uint8)
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    profile = "varying-glare"
    inspection_service._analysis_settings_overrides[profile] = expand_simple(
        threshold=0.10,
        sensitivity=1.0,
    )
    inspection_service.set_reference_frame(profile, reference)

    def with_glare(center_x: int, center_y: int, strength: int) -> np.ndarray:
        y_grid, x_grid = np.ogrid[:height, :width]
        radius = np.sqrt(
            ((x_grid - center_x) / 105.0) ** 2
            + ((y_grid - center_y) / 82.0) ** 2
        )
        illumination = np.clip(1.0 - radius, 0.0, 1.0) * float(strength)
        frame = reference.astype(np.float32) + illumination[..., None]
        return np.clip(frame, 0, 255).astype(np.uint8)

    accepted_glare = with_glare(96, 112, 105)
    first = inspection_service.inspect_frame(
        profile,
        accepted_glare,
        threshold=0.10,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    first_review = inspection_service.get_learning_review(first.inspection_id)
    assert first_review is not None
    assert len(first_review["defects"]) >= 1
    inspection_service.accept_all_review_defects_as_normal(first.inspection_id)

    weaker_shifted_glare = with_glare(106, 116, 72)
    glare_only = inspection_service.inspect_frame(
        profile,
        weaker_shifted_glare,
        threshold=0.10,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert glare_only.learned_normal_matches_count >= 1
    assert glare_only.status == "ГОДЕН"

    glare_and_scratch = weaker_shifted_glare.copy()
    cv2.line(glare_and_scratch, (92, 66), (101, 162), (0, 0, 255), 5, cv2.LINE_AA)
    damaged = inspection_service.inspect_frame(
        profile,
        glare_and_scratch,
        threshold=0.10,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    assert damaged.learned_normal_matches_count >= 1
    assert damaged.status == "БРАК"
    damaged_review = inspection_service.get_learning_review(damaged.inspection_id)
    assert damaged_review is not None
    assert len(damaged_review["defects"]) >= 1


def test_legacy_stretched_normal_is_loaded_with_recovered_aspect(
    inspection_service: InspectionService,
) -> None:
    reference = np.full((120, 200, 3), 80, dtype=np.uint8)
    acceptable = reference.copy()
    acceptable[25:33, 30:115] = 145
    identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
    inspection_service.set_reference_frame("legacy-template", reference)
    original = inspection_service.inspect_frame(
        "legacy-template",
        acceptable,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )
    review = inspection_service.get_learning_review(original.inspection_id)
    accepted = inspection_service.accept_review_defect_as_normal(
        original.inspection_id,
        review["defects"][0]["id"],
    )
    case_id = accepted["accepted_case"]["id"]
    storage_dir = inspection_service._accepted_normals.storage_dir
    metadata_path = storage_dir / f"{case_id}.json"
    arrays_path = storage_dir / f"{case_id}.npz"

    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata.pop("template_version", None)
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    with np.load(arrays_path, allow_pickle=False) as arrays:
        mask = arrays["mask_template"].copy()
        diff = arrays["diff_template"].copy()
        appearance = arrays["appearance_template"].copy()
    y_points, x_points = np.where(mask > 0)
    y0, y1 = int(np.min(y_points)), int(np.max(y_points)) + 1
    x0, x1 = int(np.min(x_points)), int(np.max(x_points)) + 1
    legacy_arrays = {
        "mask_template": cv2.resize(mask[y0:y1, x0:x1], (64, 64), interpolation=cv2.INTER_NEAREST),
        "diff_template": cv2.resize(diff[y0:y1, x0:x1], (64, 64), interpolation=cv2.INTER_AREA),
        "appearance_template": cv2.resize(
            appearance[y0:y1, x0:x1],
            (64, 64),
            interpolation=cv2.INTER_AREA,
        ),
    }
    np.savez_compressed(arrays_path, **legacy_arrays)

    restarted = InspectionService(
        learned_normals_dir=storage_dir,
        reviews_dir=inspection_service._learning_reviews.storage_dir,
        review_limit=5,
        session_wipe=False,
    )
    restarted._anomaly_engine = None
    restarted.set_reference_frame("legacy-template", reference)
    result = restarted.inspect_frame(
        "legacy-template",
        acceptable,
        threshold=0.1,
        include_visuals=False,
        alignment_h_ref_to_cur=identity,
    )

    assert result.learned_normal_matches_count == 1
    assert result.status == "ГОДЕН"
