"""Equivalence and update races for prepared learning state and exact indexing."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import os
from pathlib import Path
import threading

import cv2
import numpy as np
import pytest

from app.services import learned_normals as normals
from app.services.learned_normals import AcceptedNormalMemory, extract_defect_candidates


def scene():
    frame = np.full((180, 240, 3), 60, np.uint8)
    mask = np.zeros_like(frame)
    cv2.rectangle(mask, (25, 30), (45, 55), (255, 255, 255), -1)
    frame[mask[:, :, 0] > 0] = 180
    diff = np.zeros_like(frame)
    diff[mask[:, :, 0] > 0] = 120
    return frame, diff, mask


def add(memory, name="saved"):
    frame, diff, mask = scene()
    candidate = extract_defect_candidates(frame, diff, mask)[0]
    return memory.add_from_candidate(
        product_type="part#cam=0", reference_hash="ref", inspection_id=name,
        candidate=candidate, source_frame=frame,
    )


def apply(memory):
    frame, diff, mask = scene()
    return memory.apply(product_type="part#cam=0", reference_hash="ref", aligned=frame,
                        diff_map=diff, segmentation_mask=mask)


def manual_refresh(memory, monkeypatch):
    refresh = memory._prepared.refresh
    monkeypatch.setattr(memory._prepared, "refresh", lambda: None)
    return refresh


def test_pending_update_never_applies_a_deleted_normal(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    saved = add(writer)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    refresh = manual_refresh(reader, monkeypatch)
    assert apply(reader).matched_case_ids == [saved.id]
    writer.delete(saved.id)
    # No disk payload loading or stale suppression on the inspection thread.
    def forbidden():
        pytest.fail("inspection attempted to reload learning payloads")
    original_load = reader._load
    monkeypatch.setattr(reader, "_load", forbidden)
    result = apply(reader)
    assert result.matched_case_ids == []
    np.testing.assert_array_equal(result.filtered_diff_map, scene()[1])
    monkeypatch.setattr(reader, "_load", original_load)
    refresh()
    assert reader._prepared.get()[1] == {}


def test_pending_addition_retains_existing_normals(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    saved = add(writer)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    refresh = manual_refresh(reader, monkeypatch)
    added = add(writer, "new")
    assert apply(reader).matched_case_ids == [saved.id]
    assert reader._prepared._ready_version != reader._disk_version()
    writer.delete(saved.id)
    assert apply(reader).matched_case_ids == []
    refresh()
    assert apply(reader).matched_case_ids == [added.id]


def test_slow_preparation_does_not_block_an_inspection(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    refresh = manual_refresh(reader, monkeypatch)
    saved = add(writer)
    entered, release = threading.Event(), threading.Event()
    original = reader._prepared._prepare
    def blocked():
        entered.set()
        assert release.wait(3)
        original()
    monkeypatch.setattr(reader._prepared, "_prepare", blocked)
    with ThreadPoolExecutor(max_workers=2) as pool:
        preparing = pool.submit(refresh)
        assert entered.wait(2)
        try:
            result = pool.submit(apply, reader).result(timeout=1)
            assert result.matched_case_ids == []
        finally:
            release.set()
        preparing.result(timeout=2)
    assert apply(reader).matched_case_ids == [saved.id]


def test_generation_changes_during_preparation_are_not_published(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    refresh = manual_refresh(reader, monkeypatch)
    add(writer, "first")
    original = reader._load
    def raced_load():
        original()
        # Simulate a legacy/non-cooperating publisher changing the marker.
        writer._publish_storage_generation()
    monkeypatch.setattr(reader, "_load", raced_load)
    refresh()
    assert reader._prepared._ready_version != reader._disk_version()
    monkeypatch.setattr(reader, "_load", original)
    refresh()
    assert len(reader._prepared.get()[0][("part#cam=0", "ref")].cases) == 1


def test_snapshot_is_fixed_for_the_entire_inspection(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    saved = add(writer)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    manual_refresh(reader, monkeypatch)
    with reader.inspection_snapshot():
        writer.delete(saved.id)
        assert apply(reader).matched_case_ids == [saved.id]
    assert apply(reader).matched_case_ids == []


def test_incremental_refresh_reuses_arrays_and_loads_preview_lazily(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    first = add(writer, "first")
    add(writer, "second")
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    refresh = manual_refresh(reader, monkeypatch)
    before = reader.get(first.id)
    assert before.source_frame is None
    added = add(writer, "third")
    calls = []
    original = np.load
    def counted(path, *args, **kwargs):
        calls.append(str(path))
        return original(path, *args, **kwargs)
    monkeypatch.setattr(np, "load", counted)
    refresh()
    assert calls == [str(writer.storage_dir / f"{added.id}.npz")]
    assert reader.get(first.id) is before
    assert reader.image(first.id)[1] == "image/png"
    assert reader.get(first.id).source_frame is None


def test_uncommitted_case_is_not_visible_in_published_membership(tmp_path):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    saved = add(writer)
    # Simulate a second add which wrote its artifacts but did not commit.
    writer._save_case(replace(saved, id="unfinished"))
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    assert [case["id"] for case in reader.list()] == [saved.id]


def test_simultaneous_writers_preserve_both_cases(tmp_path):
    storage = tmp_path / "normals"
    writers = [AcceptedNormalMemory(storage, session_wipe=False) for _ in range(2)]
    barrier = threading.Barrier(2)
    def save(writer):
        barrier.wait(timeout=2)
        return add(writer)
    with ThreadPoolExecutor(max_workers=2) as pool:
        saved = list(pool.map(save, writers))
    reader = AcceptedNormalMemory(storage, session_wipe=False)
    assert {case["id"] for case in reader.list()} == {case.id for case in saved}


def relocated(item, x, y, sx=1.0, sy=1.0):
    old_x, old_y, w, h = item.bbox_norm
    bbox_norm = (x, y, w * sx, h * sy)
    polygon = [(x + (px - old_x) * sx, y + (py - old_y) * sy)
               for px, py in item.polygon_norm]
    return replace(item, bbox_norm=bbox_norm, polygon_norm=polygon, _spatial_cache={})


@pytest.mark.parametrize("seed", [7, 29, 83])
def test_spatial_index_matches_exhaustive_selection_and_diagnostics(tmp_path, seed):
    memory = AcceptedNormalMemory(tmp_path / "normals")
    prototype = add(memory)
    candidate = extract_defect_candidates(*scene())[0]
    rng = np.random.default_rng(seed)
    cases = [relocated(replace(prototype, id=f"case-{i}"), float(x), float(y), float(sx), float(sy))
             for i, (x, y, sx, sy) in enumerate(zip(
                 rng.uniform(0, .85, 80), rng.uniform(0, .8, 80),
                 rng.uniform(.4, 2, 80), rng.uniform(.4, 2, 80)))]
    cases.extend([prototype, replace(prototype, id="equal-match")])
    scope = normals._NormalScope(cases)
    for dx in [0, 3.999999, 4, 4.000001, 6, 25, 80]:
        for scale in [.5, 1., 1.5]:
            shifted = relocated(candidate, candidate.bbox_norm[0] + dx / 240,
                                candidate.bbox_norm[1])
            shifted.bbox = (shifted.bbox[0], shifted.bbox[1],
                            round(candidate.bbox[2] * scale), round(candidate.bbox[3] * scale))
            exhaustive = memory._best_matching_case(shifted, cases)
            indexed = memory._best_matching_case(shifted, cases, scope)
            assert (exhaustive[0].id if exhaustive[0] else None) == (indexed[0].id if indexed[0] else None)
            assert exhaustive[1] == indexed[1]
            assert exhaustive[2] == indexed[2]


def test_index_avoids_detailed_comparison_with_distant_cases(tmp_path, monkeypatch):
    memory = AcceptedNormalMemory(tmp_path / "normals")
    prototype = add(memory)
    candidate = extract_defect_candidates(*scene())[0]
    cases = [relocated(replace(prototype, id=str(i)), .7 + i / 10000, .7) for i in range(300)]
    cases.append(prototype)
    scope = normals._NormalScope(cases)
    count = 0
    original = normals.candidate_similarity
    def counted(*args, **kwargs):
        nonlocal count
        count += 1
        return original(*args, **kwargs)
    monkeypatch.setattr(normals, "candidate_similarity", counted)
    assert memory._best_matching_case(candidate, cases, scope)[0].id == prototype.id
    assert count == 1


def test_invalid_foreign_geometry_falls_back_without_breaking_preparation(tmp_path):
    memory = AcceptedNormalMemory(tmp_path / "normals")
    prototype = add(memory)
    broken = replace(prototype, id="foreign", product_type="other-camera",
                     bbox_norm=(float("nan"), .1, .1, .1), _spatial_cache={})
    memory._cases[broken.id] = broken
    scopes, _ = memory._build_snapshot()
    assert scopes[("part#cam=0", "ref")]._indexed
    foreign = scopes[("other-camera", "ref")]
    assert not foreign._indexed
    assert list(foreign.nearby(extract_defect_candidates(*scene())[0])) == [0]


def test_fp_snapshot_is_pinned_for_the_entire_inspection(inspection_service, monkeypatch):
    service = inspection_service
    frame, _, _ = scene()
    service.set_reference_frame("part", frame)
    points = [(.1, .1), (.3, .1), (.3, .4), (.1, .4)]
    zone = service.add_fp_zone("part", points, 240, 180, aligned=frame)
    monkeypatch.setattr(service._fp_snapshot, "refresh", lambda: None)
    with service._fp_snapshot.pin():
        service.delete_fp_zone(zone.id)
        assert service.get_fp_zones("part")[0].id == zone.id
    with service._fp_snapshot.pin():
        assert service.get_fp_zones("part") == []


def test_fp_remote_updates_do_not_reload_crops_in_inspection(inspection_service, tmp_path, monkeypatch):
    from app.services.inspection_service import InspectionService
    reader = inspection_service
    writer = InspectionService(learned_normals_dir=tmp_path / "writer-normals",
                               reviews_dir=tmp_path / "writer-reviews", session_wipe=False)
    writer._fp_zones_file, writer._fp_crops_dir = reader._fp_zones_file, reader._fp_crops_dir
    writer.fp_zones, writer._fp_zones_generation = {}, None
    writer._load_fp_zones()
    frame, _, _ = scene()
    points = [(.1, .1), (.3, .1), (.3, .4), (.1, .4)]
    saved = writer.add_fp_zone("part", points, 240, 180, aligned=frame)
    reader._prepare_fp_snapshot()
    monkeypatch.setattr(reader._fp_snapshot, "refresh", lambda: None)
    writer.add_fp_zone("part", points, 240, 180, aligned=frame)
    def forbidden(*args):
        pytest.fail("inspection attempted to reload an FP crop")
    monkeypatch.setattr(reader, "_load_fp_crop", forbidden)
    with reader._fp_snapshot.pin():
        assert [zone.id for zone in reader.get_fp_zones("part")] == [saved.id]
    writer.delete_fp_zone(saved.id)
    with reader._fp_snapshot.pin():
        assert reader.get_fp_zones("part") == []


def test_reused_fp_grayscale_maps_preserve_activity(inspection_service):
    rng = np.random.default_rng(19)
    diff = rng.integers(0, 256, (80, 100, 3), dtype=np.uint8)
    mask = rng.integers(0, 2, diff.shape, dtype=np.uint8) * 255
    maps = (cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY), cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY))
    for points in [[(.1, .1), (.8, .2), (.5, .9)], [(.2, .2), (.4, .2), (.4, .4), (.2, .4)]]:
        assert inspection_service._measure_zone_activity(diff, mask, points) == inspection_service._measure_zone_activity(diff, mask, points, maps)


def test_postverdict_case_lookup_uses_the_pinned_snapshot(tmp_path, monkeypatch):
    writer = AcceptedNormalMemory(tmp_path / "normals", session_wipe=False)
    saved = add(writer)
    reader = AcceptedNormalMemory(writer.storage_dir, session_wipe=False)
    manual_refresh(reader, monkeypatch)
    with reader.inspection_snapshot():
        writer.delete(saved.id)
        def forbidden():
            pytest.fail("post-verdict overlay lookup reloaded learning data")
        monkeypatch.setattr(reader, "_refresh_from_disk_if_changed", forbidden)
        assert reader.get(saved.id).id == saved.id


def test_slow_learning_api_does_not_block_the_http_event_loop(inspection_service, monkeypatch):
    import asyncio
    import httpx
    from app.api import learned_normal_routes
    from app.main import app
    entered, release = threading.Event(), threading.Event()
    def slow_accept(**kwargs):
        entered.set()
        assert release.wait(3)
        return {"saved": True}
    monkeypatch.setattr(learned_normal_routes, "inspection_service", inspection_service)
    monkeypatch.setattr(inspection_service, "accept_all_review_defects_as_normal", slow_accept)
    async def scenario():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            update = asyncio.create_task(client.post("/learning/reviews/held/accept-all-as-normal", json={}))
            try:
                assert await asyncio.to_thread(entered.wait, 2)
                response = await asyncio.wait_for(client.get("/health"), timeout=1)
                assert response.status_code == 200
            finally:
                release.set()
                assert (await update).status_code == 200
    asyncio.run(scenario())


@pytest.mark.skipif(os.name != "nt", reason="Windows denies replacement while a reader handle is open")
def test_publication_survives_a_short_lived_windows_marker_reader(tmp_path, monkeypatch):
    from app.services.learning_snapshot import replace_learning_file
    marker, temporary = tmp_path / "generation", tmp_path / "generation.tmp"
    marker.write_text("old")
    temporary.write_text("new")
    denied = threading.Event()
    original = Path.replace
    def observed(source, target):
        try:
            return original(source, target)
        except PermissionError:
            denied.set()
            raise
    monkeypatch.setattr(Path, "replace", observed)
    with ThreadPoolExecutor(max_workers=1) as pool:
        reader = marker.open("rb")
        try:
            future = pool.submit(replace_learning_file, temporary, marker)
            assert denied.wait(1)
        finally:
            reader.close()
        future.result(timeout=1)
    assert marker.read_text() == "new"
