import threading

import pytest

from app.services.inspection_workload import BackgroundBusy, BackgroundExecutor, InspectionExecutor, InspectionWorkload


def test_background_waits_for_running_and_queued_inspections():
    workload = InspectionWorkload(quiet_seconds=0)
    unblock, started = threading.Event(), threading.Event()
    events = []
    def first():
        started.set()
        assert unblock.wait(3)
        events.append("first")
    with InspectionExecutor(workload, max_workers=1) as inspect, BackgroundExecutor(workload) as background:
        one = inspect.submit(first)
        assert started.wait(2)
        auxiliary = background.submit(lambda: events.append("background"))
        two = inspect.submit(lambda: events.append("second"))
        assert not auxiliary.done()
        unblock.set()
        one.result(timeout=2)
        two.result(timeout=2)
        auxiliary.result(timeout=2)
    assert events == ["first", "second", "background"]
    assert workload._foreground == 0


def test_inspection_does_not_wait_on_running_background_and_backlog_is_bounded():
    workload = InspectionWorkload(quiet_seconds=0)
    unblock, started = threading.Event(), threading.Event()
    def render():
        started.set()
        assert unblock.wait(3)
    with InspectionExecutor(workload, max_workers=1) as inspect, BackgroundExecutor(workload, capacity=1) as background:
        auxiliary = background.submit(render)
        try:
            assert started.wait(2)
            with pytest.raises(BackgroundBusy):
                background.submit(lambda: None)
            assert inspect.submit(lambda: "verdict").result(timeout=1) == "verdict"
        finally:
            unblock.set()
        auxiliary.result(timeout=2)


def test_cancelled_or_failed_inspections_release_reservations():
    workload = InspectionWorkload(quiet_seconds=0)
    unblock = threading.Event()
    with InspectionExecutor(workload, max_workers=1) as inspect:
        running = inspect.submit(unblock.wait, 2)
        queued = inspect.submit(lambda: None)
        assert queued.cancel()
        unblock.set()
        running.result(timeout=2)
        failed = inspect.submit(lambda: 1 / 0)
        with pytest.raises(ZeroDivisionError):
            failed.result(timeout=2)
    assert workload._foreground == 0


def test_starved_heatmap_expires_without_writing_stale_output():
    workload = InspectionWorkload(quiet_seconds=0)
    with workload.foreground(), BackgroundExecutor(workload, max_wait_seconds=.05) as background:
        future = background.submit(lambda: pytest.fail("expired heatmap must not run"))
        with pytest.raises(BackgroundBusy):
            future.result(timeout=1)


def test_history_and_heatmap_share_one_background_slot():
    workload = InspectionWorkload(quiet_seconds=0)
    with workload.background(), BackgroundExecutor(workload, max_wait_seconds=.05) as background:
        with pytest.raises(BackgroundBusy):
            background.submit(lambda: pytest.fail("another background task is active")).result(timeout=1)
