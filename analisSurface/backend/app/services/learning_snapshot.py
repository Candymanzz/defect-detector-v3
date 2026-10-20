"""Prepare learning state off the inspection thread; publish complete versions.

One daemon per process watches weakly held caches. Inspectors only read the
small generation marker and pin an already prepared value. A changed/unready
version can retain only unchanged members confirmed by the new publication;
otherwise it returns None. Removed exclusions must never hide a new defect.
"""
from contextlib import contextmanager, nullcontext
from contextvars import ContextVar
import logging
import os
import threading
import time
import weakref

from app.services.inspection_workload import BackgroundBusy

LOG = logging.getLogger("uvicorn.error")
_UNSET = object()
_caches = weakref.WeakSet()
_registry_lock = threading.Lock()
_wake = threading.Event()
_worker = None


def replace_learning_file(source, destination):
    """Atomic publication; tolerate Windows' short-lived marker read handles.

    Retrying is confined to writers. Inspectors never sleep on publication.
    """
    deadline = time.monotonic() + 0.25
    while True:
        try:
            source.replace(destination)
            return
        except PermissionError:
            if os.name != "nt" or time.monotonic() >= deadline:
                raise
            time.sleep(0.002)


@contextmanager
def learning_store_lock(path):
    """Serialize UI writers across processes; never used by inspections.

    The lock lives outside a wipeable normal-store directory. OS locks are
    released on process death, unlike an exclusive-create lock file.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as handle:
        if os.name == "nt":
            import msvcrt
            if path.stat().st_size == 0:
                handle.write(b"\0")
                handle.flush()
            def acquire():
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            def release():
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            def acquire():
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            def release():
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        deadline = time.monotonic() + 10
        while True:
            try:
                acquire()
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"learning store writer lock timed out: {path}")
                time.sleep(0.01)
        try:
            yield
        finally:
            release()


def _watch():
    while True:
        _wake.wait(0.05)
        _wake.clear()
        with _registry_lock:
            refs = [weakref.ref(cache) for cache in _caches]
        for ref in refs:
            cache = ref()
            if cache is not None:
                cache.refresh()
            del cache


class LearningSnapshot:
    def __init__(self, name, version, prepare, value, initial_version, workload=None, fallback=None):
        self.name = name
        self._version = version
        self._prepare = prepare
        self._workload = workload
        self._fallback = fallback
        self._fallback_lock = threading.Lock()
        self._fallback_version = _UNSET
        self._fallback_value = None
        self._lock = threading.Lock()
        self._refresh_lock = threading.Lock()
        self._value = value
        self._ready_version = initial_version
        self._pending_version = _UNSET
        self._retry_after = 0.0
        self._closed = False
        self._pinned = ContextVar(f"learning_snapshot_{id(self)}", default=_UNSET)
        global _worker
        with _registry_lock:
            _caches.add(self)
            if _worker is None:
                _worker = threading.Thread(target=_watch, name="learning-prepare", daemon=True)
                _worker.start()

    @property
    def pinned(self):
        return self._pinned.get() is not _UNSET

    def get(self):
        pinned = self._pinned.get()
        if pinned is not _UNSET:
            return pinned
        version = self._version()
        with self._lock:
            if version == self._ready_version:
                return self._value
            if version != self._pending_version:
                self._pending_version = version
                LOG.info("learning_snapshot event=pending kind=%s generation=%s; additions await preparation",
                         self.name, version[-1])
            if version == self._fallback_version:
                return self._fallback_value
            previous = self._value
        _wake.set()
        if self._fallback is None or not self._fallback_lock.acquire(blocking=False):
            return None
        try:
            # Metadata only, once per generation. No images/NPZ or descriptor
            # preparation here. Immutable retained members keep working while
            # additions are prepared, and deleted members stop immediately.
            value = self._fallback(previous, version)
            if version != self._version():
                return None
            with self._lock:
                if version == self._ready_version:
                    return self._value
                self._fallback_version, self._fallback_value = version, value
            return value
        except (OSError, ValueError, KeyError, TypeError):
            return None
        finally:
            self._fallback_lock.release()

    @contextmanager
    def pin(self):
        token = self._pinned.set(self.get())
        try:
            yield
        finally:
            self._pinned.reset(token)

    def publish(self, value, version):
        with self._lock:
            self._value = value
            self._ready_version = version
            self._pending_version = _UNSET
            self._fallback_version, self._fallback_value = _UNSET, None
            self._retry_after = 0.0

    def refresh(self):
        if self._closed or time.monotonic() < self._retry_after:
            return
        if not self._refresh_lock.acquire(blocking=False):
            return
        try:
            if self._closed:
                return
            version = self._version()
            with self._lock:
                if version == self._ready_version:
                    return
            started = time.perf_counter()
            guard = (self._workload.background(deadline=time.monotonic() + 0.02)
                     if self._workload is not None else nullcontext())
            with guard:
                # The callback publishes under its writer lock. It must validate
                # the generation again after preparation, before publication.
                self._prepare()
            current_version = self._version()
            with self._lock:
                ready = self._ready_version == current_version
            if ready:
                LOG.info("learning_snapshot event=ready kind=%s generation=%s prepare_ms=%.3f",
                         self.name, current_version[-1], (time.perf_counter() - started) * 1000)
        except BackgroundBusy:
            pass
        except Exception:
            self._retry_after = time.monotonic() + 1.0
            LOG.exception("learning_snapshot event=prepare_failed kind=%s", self.name)
        finally:
            self._refresh_lock.release()

    def close(self):
        """Stop watching and finish an in-flight load before store teardown."""
        self._closed = True
        with _registry_lock:
            _caches.discard(self)
        with self._refresh_lock:
            pass
