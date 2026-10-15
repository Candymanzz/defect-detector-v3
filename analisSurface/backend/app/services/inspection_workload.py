"""Keep auxiliary history/heatmap work behind queued and running inspections."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import threading
import time


class BackgroundBusy(RuntimeError):
    pass


class InspectionWorkload:
    def __init__(self, quiet_seconds: float = 0.025):
        self._condition = threading.Condition()
        self._background_lock = threading.Lock()
        self._foreground = 0
        self._last_finished = 0.0
        self._quiet_seconds = quiet_seconds

    def started(self):
        with self._condition:
            self._foreground += 1

    def finished(self):
        with self._condition:
            self._foreground -= 1
            self._last_finished = time.monotonic()
            self._condition.notify_all()

    @contextmanager
    def foreground(self):
        self.started()
        try:
            yield
        finally:
            self.finished()

    @contextmanager
    def background(self, *, deadline: float | None = None):
        # Only one auxiliary CPU/disk task per Python process, including history.
        # A new inspection never waits on this lock. An already-running background
        # task cannot be preempted; only its next task is held back.
        if deadline is None:
            acquired = self._background_lock.acquire()
        else:
            acquired = self._background_lock.acquire(timeout=max(0, deadline - time.monotonic()))
        if not acquired:
            raise BackgroundBusy("background task expired while waiting for auxiliary work")
        try:
            with self._condition:
                while True:
                    remaining = None if deadline is None else deadline - time.monotonic()
                    if remaining is not None and remaining <= 0:
                        raise BackgroundBusy("background task expired; inspection has priority")
                    quiet_left = self._quiet_seconds - (time.monotonic() - self._last_finished)
                    if not self._foreground and quiet_left <= 0:
                        break
                    wait = remaining if self._foreground else quiet_left
                    if remaining is not None:
                        wait = min(wait, remaining)
                    self._condition.wait(wait)
            yield
        finally:
            self._background_lock.release()


class InspectionExecutor(ThreadPoolExecutor):
    def __init__(self, workload: InspectionWorkload, **kwargs):
        super().__init__(**kwargs)
        self._workload = workload

    def submit(self, fn, /, *args, **kwargs):
        # Reserve BEFORE queueing: auxiliary tasks must also yield to requests
        # whose inspection worker has not started yet. Cancellation releases it.
        self._workload.started()
        try:
            future = super().submit(fn, *args, **kwargs)
        except BaseException:
            self._workload.finished()
            raise
        future.add_done_callback(lambda _: self._workload.finished())
        return future


class BackgroundExecutor(ThreadPoolExecutor):
    def __init__(self, workload: InspectionWorkload, capacity: int = 4, max_wait_seconds: float = 3.0):
        super().__init__(max_workers=1, thread_name_prefix="heatmap")
        self._workload = workload
        self._capacity = threading.BoundedSemaphore(capacity)
        self._max_wait_seconds = max_wait_seconds

    def submit(self, fn, /, *args, **kwargs):
        if not self._capacity.acquire(blocking=False):
            raise BackgroundBusy("background heatmap queue is full; inspection has priority")
        deadline = time.monotonic() + self._max_wait_seconds

        def run():
            with self._workload.background(deadline=deadline):
                return fn(*args, **kwargs)

        try:
            future = super().submit(run)
        except BaseException:
            self._capacity.release()
            raise
        future.add_done_callback(lambda _: self._capacity.release())
        return future
