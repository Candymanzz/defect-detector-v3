"""Learning failures must leave the previous effective configuration intact."""

import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pytest

from app.services.analysis_settings import AnalysisSettings
from app.services.inspection_models import RoiSubZone
from app.services.learned_normals import AcceptedNormalMemory, InspectionReviewStore
from app.services.learned_normals import decode_review_arrays


def make_review(service, *, scaled_roi=False):
    reference = np.full((120, 160, 3), 40, np.uint8)
    current = reference.copy()
    current[15:40, 15:50] = 220
    current[65:100, 105:145] = 230
    service.set_reference_frame("learning-regression", reference)
    if scaled_roi:
        service.set_roi_polygon("learning-regression", [(0, 0), (1, 0), (1, 1), (0, 1)])
    result = service.inspect_frame(
        "learning-regression", current, include_visuals=False, threshold=.1,
        settings=AnalysisSettings.from_overrides({"use_patchcore": False}),
        inspect_scale_after_align=.75 if scaled_roi else None,
    )
    review = service._learning_reviews.get(result.inspection_id)
    assert review.defects
    return review


def test_scaled_review_with_roi_has_counterfactual(inspection_service):
    review = make_review(inspection_service, scaled_roi=True)
    response = inspection_service.accept_all_review_defects_as_normal(review.inspection_id)
    assert response["counterfactual_score"] is not None
    assert response["counterfactual_status"] is not None
    assert response["original_score"] == review.original_score
    assert response["original_status"] == review.original_status


@pytest.mark.parametrize("accept_all", [False, True])
def test_review_write_failure_rolls_back_learning(inspection_service, monkeypatch, accept_all):
    service = inspection_service
    review = make_review(service)
    with monkeypatch.context() as patch:
        def fail_put(*args):
            raise OSError("review write failed")
        patch.setattr(service._learning_reviews, "put", fail_put)
        with pytest.raises(OSError, match="review write failed"):
            if accept_all:
                service.accept_all_review_defects_as_normal(review.inspection_id)
            else:
                service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    assert not service.list_accepted_normal_cases()
    assert not service.get_fp_zones(review.product_type)
    assert not service._learning_reviews.get(review.inspection_id).accepted_defect_ids


def test_failed_normal_publication_does_not_reappear_after_reload(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    memory = service._accepted_normals
    def fail_publish():
        raise OSError("generation write failed")
    monkeypatch.setattr(memory, "_publish_storage_generation", fail_publish)
    with pytest.raises(OSError, match="generation write failed"):
        service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    restarted = AcceptedNormalMemory(memory.storage_dir, session_wipe=False)
    assert not restarted.list()


def test_deleting_normal_unmarks_cold_review(inspection_service):
    service = inspection_service
    old_review = make_review(service)
    response = service.accept_review_defect_as_normal(old_review.inspection_id, old_review.defects[0].id)
    make_review(service)
    service._learning_reviews = InspectionReviewStore(
        max_items=1, storage_dir=service._learning_reviews.storage_dir, session_wipe=False,
    )
    assert old_review.inspection_id not in service._learning_reviews._order
    assert service.delete_accepted_normal_case(response["accepted_case"]["id"])
    restored = service._learning_reviews.get(old_review.inspection_id)
    assert not restored.accepted_defect_ids
    assert all(candidate.matched_case_id is None for candidate in restored.defects)


def test_partial_fp_write_failure_rolls_back_all_new_normals(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    assert len(review.defects) == 2
    save_crop = service._save_fp_crop
    calls = 0
    def fail_second_crop(zone):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("FP crop write failed")
        return save_crop(zone)
    monkeypatch.setattr(service, "_save_fp_crop", fail_second_crop)
    with pytest.raises(OSError, match="FP crop write failed"):
        service.accept_all_review_defects_as_normal(review.inspection_id)
    assert not service.list_accepted_normal_cases()
    assert not service.get_fp_zones(review.product_type)
    assert not service._learning_reviews.get(review.inspection_id).accepted_defect_ids


def test_failed_review_array_write_preserves_previous_review(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    review.accepted_defect_ids.add(review.defects[0].id)
    def fail_arrays(*args, **kwargs):
        raise OSError("array write failed")
    monkeypatch.setattr(np, "savez_compressed", fail_arrays)
    with pytest.raises(OSError, match="array write failed"):
        service._learning_reviews.put(review)
    restored = service._learning_reviews.get(review.inspection_id)
    assert restored is not None
    assert not restored.accepted_defect_ids
    np.testing.assert_array_equal(restored.defects[0].diff_template, review.defects[0].diff_template)
    for original, decoded in zip(decode_review_arrays(review), decode_review_arrays(restored)):
        np.testing.assert_array_equal(original, decoded)


def test_learning_does_not_rewrite_published_review_images(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    images = (review.aligned_jpeg, review.diff_png, review.raw_mask_png, review.heatmap_jpeg)
    write_bytes = Path.write_bytes

    def fail_image_write(target, data):
        if target.name.startswith(("aligned.jpg", "diff.png", "mask.png", "heatmap.jpg")):
            raise OSError("image storage unavailable")
        return write_bytes(target, data)

    monkeypatch.setattr(Path, "write_bytes", fail_image_write)
    accepted = service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    assert accepted["saved"]
    restored = service._learning_reviews.get(review.inspection_id)
    assert (restored.aligned_jpeg, restored.diff_png, restored.raw_mask_png, restored.heatmap_jpeg) == images
    decode_review_arrays(restored)


def test_partial_initial_review_image_write_never_publishes_broken_frame(tmp_path, monkeypatch):
    store = InspectionReviewStore(storage_dir=tmp_path / "reviews", session_wipe=False)
    image = np.full((24, 32, 3), 100, np.uint8)
    task = dict(inspection_id="first", product_type="part", reference_hash="ref",
                status="ГОДЕН", score=0, threshold=.387, aligned=image,
                diff_map=image, raw_mask=image, candidates=[])
    write_bytes = Path.write_bytes

    def partial_image_write(target, data):
        if target.name.startswith("diff.png"):
            write_bytes(target, b"")
            raise OSError("partial image write")
        return write_bytes(target, data)

    with monkeypatch.context() as patch:
        patch.setattr(Path, "write_bytes", partial_image_write)
        with pytest.raises(OSError, match="partial image write"):
            store.add(**task)
    assert store.get("first") is None
    assert not store.list()
    assert not list(store.storage_dir.rglob("*.tmp"))
    store.add(**task)
    restored = store.get("first")
    for decoded in decode_review_arrays(restored):
        np.testing.assert_array_equal(decoded, image)


@pytest.mark.parametrize("strict_sub_zone", [False, True])
def test_rejecting_residual_is_not_cleared_by_review_importance_filter(
    inspection_service, monkeypatch, strict_sub_zone,
):
    from app.services.learned_normals import extract_defect_candidates

    service = inspection_service
    product = "residual-regression"
    reference = np.full((160, 200, 3), 40, np.uint8)
    normal = reference.copy()
    normal[20:140, 20:180] = 180
    diff = np.zeros_like(normal)
    diff[20:140, 20:180] = 100
    settings = AnalysisSettings.from_overrides({"use_patchcore": False, "fp_recheck_enabled": False})
    service.set_reference_frame(product, reference)
    _, mask = service._run_anomaly_model(diff, settings)
    candidate = extract_defect_candidates(normal, diff, mask)[0]
    service._accepted_normals.add_from_candidate(
        product_type=product, reference_hash=service._reference_hashes[product],
        inspection_id="normal", candidate=candidate, source_frame=normal,
    )
    current = normal.copy()
    current[75:85, 95:105] = 205
    monkeypatch.setattr(service, "_compute_advanced_difference", lambda *args, **kwargs: diff.copy())
    learned = service._accepted_normals.apply(
        product_type=product, reference_hash=service._reference_hashes[product],
        aligned=current, diff_map=diff, segmentation_mask=mask,
    )
    assert learned.all_important_candidates_matched
    residual_score, _ = service._run_anomaly_model(learned.filtered_diff_map, settings)
    assert residual_score >= .05
    if strict_sub_zone:
        service.set_roi_polygon(product, [(0, 0), (1, 0), (1, 1), (0, 1)])
        service.roi_sub_zones[product] = [RoiSubZone(
            "seam", product, [(0.45, 0.43), (0.55, 0.43), (0.55, 0.57), (0.45, 0.57)],
            threshold=.05,
        )]
    result = service.inspect_frame(
        product, current, settings=settings, threshold=.387 if strict_sub_zone else .05,
        include_visuals=True, store_learning_review=False,
    )
    assert result.status == "БРАК"
    assert np.any(result.heatmap_u8[75:85, 95:105])
    if strict_sub_zone:
        assert result.sub_zone_scores[0].status == "БРАК"


def test_learning_waits_for_inspection_configuration_lock(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    started = threading.Event()
    memory_changed = threading.Event()
    add_case = service._accepted_normals.add_from_candidate
    def track_add(**kwargs):
        memory_changed.set()
        return add_case(**kwargs)
    monkeypatch.setattr(service._accepted_normals, "add_from_candidate", track_add)
    def accept():
        started.set()
        return service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    with ThreadPoolExecutor(max_workers=1) as pool:
        with service._product_lock(review.product_type):
            future = pool.submit(accept)
            assert started.wait(1)
            mutated_during_inspection = memory_changed.wait(.1)
        response = future.result(timeout=5)
    assert response["saved"]
    assert not mutated_during_inspection


def test_updating_cold_review_does_not_expand_or_reorder_recent_history(inspection_service):
    service = inspection_service
    old_review = make_review(service)
    latest_review = make_review(service)
    store = InspectionReviewStore(max_items=1, storage_dir=service._learning_reviews.storage_dir, session_wipe=False)
    store.put(old_review)
    assert [item["inspection_id"] for item in store.list()] == [latest_review.inspection_id]
    assert store.get(old_review.inspection_id) is not None


def test_delete_removes_fp_exception_even_if_review_unmark_fails(inspection_service, monkeypatch):
    service = inspection_service
    review = make_review(service)
    accepted = service.accept_review_defect_as_normal(review.inspection_id, review.defects[0].id)
    def fail_unmark(*args):
        raise OSError("review update failed")
    monkeypatch.setattr(service._learning_reviews, "unmark_case", fail_unmark)
    with pytest.raises(OSError, match="review update failed"):
        service.delete_accepted_normal_case(accepted["accepted_case"]["id"])
    assert not service.list_accepted_normal_cases()
    assert not service.get_fp_zones(review.product_type)
