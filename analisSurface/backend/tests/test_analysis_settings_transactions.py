"""Concurrent inspections must observe complete, successfully saved settings."""

import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from app.services.inspection_service import InspectionService


@pytest.fixture
def settings_service(inspection_service, tmp_path):
    service = inspection_service
    service._analysis_settings_file = tmp_path / "analysis_settings.json"
    service._analysis_settings_mtime_ns = -1
    service._analysis_settings_overrides = {}
    service._analysis_settings_simple_knobs = {}
    service._analysis_settings_detailed_knobs = {}
    service.update_analysis_settings("camera", {"default_threshold": .387})
    return service


def settings_reader(path):
    service = InspectionService.__new__(InspectionService)
    service._analysis_settings_lock = threading.RLock()
    service._analysis_settings_file = path
    service._analysis_settings_mtime_ns = -1
    service._analysis_settings_overrides = {}
    service._analysis_settings_simple_knobs = {}
    service._analysis_settings_detailed_knobs = {}
    return service


def test_other_worker_sees_complete_settings_during_save(settings_service, monkeypatch):
    service = settings_service
    path = service._analysis_settings_file
    reader = settings_reader(path)
    assert reader.get_analysis_settings("camera").default_threshold == .387
    original_write = Path.write_text
    observations = []

    def write_in_parts(target, data, *args, **kwargs):
        if target.parent != path.parent or not target.name.startswith(path.name):
            return original_write(target, data, *args, **kwargs)
        with target.open("w", encoding="utf-8") as output:
            output.write(data[:len(data) // 2])
            output.flush()
            try:
                snapshot = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                snapshot = None
            observations.append((snapshot, reader.get_analysis_settings("camera").default_threshold))
            output.write(data[len(data) // 2:])
        return len(data)

    monkeypatch.setattr(Path, "write_text", write_in_parts)
    service.update_analysis_settings("camera", {"default_threshold": .4})
    assert observations
    assert all(snapshot is not None and threshold == .387 for snapshot, threshold in observations)
    assert reader.get_analysis_settings("camera").default_threshold == .4
    assert service.get_analysis_settings("camera").default_threshold == .4
    assert not list(path.parent.glob("*.tmp"))


def test_bad_reload_preserves_settings_and_retries_same_timestamp(settings_service):
    service = settings_service
    path = service._analysis_settings_file
    prior_mtime = path.stat().st_mtime_ns
    path.write_text("{", encoding="utf-8")
    os.utime(path, ns=(prior_mtime + 1_000_000, prior_mtime + 1_000_000))
    broken_mtime = path.stat().st_mtime_ns
    assert service.get_analysis_settings("camera").default_threshold == .387
    path.write_text(json.dumps([{
        "analysis_profile": "camera", "overrides": {"default_threshold": .5},
    }]), encoding="utf-8")
    os.utime(path, ns=(broken_mtime, broken_mtime))
    assert service.get_analysis_settings("camera").default_threshold == .5


def test_worker_reloads_replaced_settings_with_same_size_and_timestamp(settings_service, monkeypatch):
    writer = settings_service
    writer.update_analysis_settings("camera", {"default_threshold": .4})
    path = writer._analysis_settings_file
    before = path.stat()
    reader = settings_reader(path)
    assert reader.get_analysis_settings("camera").default_threshold == .4
    original_write = Path.write_text

    def same_timestamp(target, data, *args, **kwargs):
        count = original_write(target, data, *args, **kwargs)
        if target.parent == path.parent and target.name.endswith(".tmp"):
            os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
        return count

    monkeypatch.setattr(Path, "write_text", same_timestamp)
    writer.update_analysis_settings("camera", {"default_threshold": .5})
    assert path.stat().st_size == before.st_size
    assert path.stat().st_mtime_ns == before.st_mtime_ns
    assert reader.get_analysis_settings("camera").default_threshold == .5


def test_reader_waits_for_complete_settings_update(settings_service, monkeypatch):
    service = settings_service
    save_started = threading.Event()
    release_save = threading.Event()
    read_started = threading.Event()
    read_finished = threading.Event()
    save = service._save_analysis_settings

    def paused_save():
        save_started.set()
        assert release_save.wait(3)
        return save()

    def read():
        read_started.set()
        try:
            return service.get_analysis_settings("camera")
        finally:
            read_finished.set()

    monkeypatch.setattr(service, "_save_analysis_settings", paused_save)
    with ThreadPoolExecutor(max_workers=2) as pool:
        write_future = pool.submit(service.update_analysis_settings, "camera", {"default_threshold": .4})
        assert save_started.wait(2)
        read_future = pool.submit(read)
        try:
            assert read_started.wait(2)
            assert not read_finished.wait(.1)
        finally:
            release_save.set()
        write_future.result(timeout=3)
        assert read_future.result(timeout=3).default_threshold == .4


@pytest.mark.parametrize("operation", ["update", "simple", "detailed", "reset"])
def test_failed_save_restores_previous_effective_settings(settings_service, monkeypatch, operation):
    service = settings_service
    path = service._analysis_settings_file
    before = path.read_bytes()
    original_replace = Path.replace

    def fail_settings_replace(source, target):
        if target == path:
            raise OSError("settings replacement failed")
        return original_replace(source, target)

    monkeypatch.setattr(Path, "replace", fail_settings_replace)
    with pytest.raises(OSError, match="settings replacement failed"):
        if operation == "update":
            service.update_analysis_settings("camera", {"default_threshold": .4})
        elif operation == "simple":
            service.apply_simple_settings(
                "camera", {"default_threshold": .4}, {"threshold": .4, "sensitivity": .7},
            )
        elif operation == "detailed":
            service.apply_detailed_settings("camera", {"far_edge_boost": 80})
        else:
            service.reset_analysis_settings("camera")
    assert path.read_bytes() == before
    assert service.get_analysis_settings("camera").default_threshold == .387
    assert service.get_simple_knobs("camera") is None
    assert service.get_detailed_knobs("camera") is None
    assert not list(path.parent.glob("*.tmp"))


def test_concurrent_profile_updates_are_both_saved(settings_service, monkeypatch):
    service = settings_service
    path = service._analysis_settings_file
    first_ready = threading.Event()
    release_first = threading.Event()
    second_started = threading.Event()
    second_finished = threading.Event()
    original_replace = Path.replace

    def pause_first_publish(source, target):
        if target == path and not first_ready.is_set():
            first_ready.set()
            assert release_first.wait(3)
        return original_replace(source, target)

    def second_update():
        second_started.set()
        try:
            return service.update_analysis_settings("second", {"default_threshold": .6})
        finally:
            second_finished.set()

    monkeypatch.setattr(Path, "replace", pause_first_publish)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(service.update_analysis_settings, "first", {"default_threshold": .4})
        assert first_ready.wait(2)
        second = pool.submit(second_update)
        try:
            assert second_started.wait(2)
            assert not second_finished.wait(.1)
        finally:
            release_first.set()
        first.result(timeout=3)
        second.result(timeout=3)
    thresholds = {
        item["analysis_profile"]: item["overrides"]["default_threshold"]
        for item in json.loads(path.read_text(encoding="utf-8"))
    }
    assert thresholds == {"camera": .387, "first": .4, "second": .6}
