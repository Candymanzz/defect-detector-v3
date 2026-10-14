"""Bounded, process-local snapshots for rendering the original verdict after PLC delivery."""

from collections import OrderedDict
from threading import Lock
from time import monotonic
from uuid import uuid4


class DeferredHeatmapCache:
    def __init__(self, *, max_bytes=64 * 1024 * 1024, ttl_seconds=45, clock=monotonic):
        self._max_bytes = max_bytes
        self._ttl = ttl_seconds
        self._clock = clock
        self._entries = OrderedDict()
        self._bytes = 0
        self._lock = Lock()

    def _expire(self, now):
        while self._entries:
            token, (created, size, _) = next(iter(self._entries.items()))
            if now - created < self._ttl:
                break
            self._entries.pop(token)
            self._bytes -= size

    def put(self, result):
        data = result.deferred_heatmap_data
        if data is None:
            return None
        mask, diff_map, _ = data
        size = mask.nbytes + diff_map.nbytes
        if size > self._max_bytes:
            return None
        token = uuid4().hex
        with self._lock:
            now = self._clock()
            self._expire(now)
            while self._entries and self._bytes + size > self._max_bytes:
                _, (_, old_size, _) = self._entries.popitem(last=False)
                self._bytes -= old_size
            self._entries[token] = (now, size, result)
            self._bytes += size
        return token

    def get(self, token):
        with self._lock:
            self._expire(self._clock())
            entry = self._entries.get(token)
            return None if entry is None else entry[2]

    def clear(self):
        with self._lock:
            self._entries.clear()
            self._bytes = 0
