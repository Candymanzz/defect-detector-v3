"""Локальный replay реальных архивных кадров без камер, Java и ПЛК.

Кадр на вход инспекции всегда берётся только из ``frame.jpg``.  Соседние
``result.json`` и ``heatmap.u8`` используются как историческое сравнение.
"""

from __future__ import annotations

import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import cv2
import numpy as np

from app.api.mappers import to_inspect_with_visuals_response
from app.services.analysis_settings_presets import expand_pro, expand_simple
from app.services.inspection_service import InspectionService


@dataclass(frozen=True)
class ArchivedCameraFrame:
    camera_id: int
    frame_id: str
    inspection_id: str
    product_type: str
    frame_path: Path
    heatmap_path: Optional[Path]
    result: dict[str, Any]


@dataclass
class EmulatorCameraState:
    reference_bucket_id: Optional[str] = None
    reference_frame_id: Optional[str] = None
    threshold: Optional[float] = None
    mode: str = "simple"
    knobs: dict[str, Any] = field(default_factory=lambda: {"threshold": 0.25, "sensitivity": 0.5})
    temporary_overrides: dict[str, Any] = field(default_factory=dict)
    roi_points: list[dict[str, float]] = field(default_factory=list)
    last_run_ms: float = 0.0
    last_result: Optional[dict[str, Any]] = None


@dataclass
class EmulatorSession:
    session_id: str
    bucket_ids: list[str]
    cursor: int
    loop: bool
    service: InspectionService
    cameras: dict[int, EmulatorCameraState]
    results: dict[str, dict[int, dict[str, Any]]] = field(default_factory=dict)
    last_bucket_time_ms: float = 0.0
    lock: threading.RLock = field(default_factory=threading.RLock, repr=False)


class FrameEmulator:
    """Dataset index and isolated replay sessions for the local UI."""

    def __init__(self, archive_root: Optional[Path] = None) -> None:
        backend_root = Path(__file__).resolve().parents[1]
        self.archive_root = archive_root or backend_root / "data" / "frame"
        self._frames: dict[str, dict[int, ArchivedCameraFrame]] = {}
        self._sessions: dict[str, EmulatorSession] = {}
        self._lock = threading.RLock()
        self.refresh()

    def refresh(self) -> None:
        frames: dict[str, dict[int, ArchivedCameraFrame]] = {}
        if self.archive_root.is_dir():
            for result_path in self.archive_root.glob("camera_*/f_*/result.json"):
                try:
                    result = json.loads(result_path.read_text(encoding="utf-8"))
                    camera_id = int(result["camera_id"])
                    inspection_id = str(result["inspection_id"])
                    frame_id = str(result["frame_id"])
                    frame_path = result_path.parent / "frame.jpg"
                    if not frame_path.is_file():
                        continue
                    item = ArchivedCameraFrame(
                        camera_id=camera_id,
                        frame_id=frame_id,
                        inspection_id=inspection_id,
                        product_type=str(result.get("product_type") or f"camera-{camera_id}"),
                        frame_path=frame_path,
                        heatmap_path=(result_path.parent / "heatmap.u8")
                        if (result_path.parent / "heatmap.u8").is_file()
                        else None,
                        result=result,
                    )
                    frames.setdefault(inspection_id, {})[camera_id] = item
                except (OSError, ValueError, TypeError, json.JSONDecodeError, KeyError):
                    continue
        with self._lock:
            self._frames = dict(sorted(frames.items(), key=self._bucket_sort_key))

    @staticmethod
    def _bucket_sort_key(entry: tuple[str, dict[int, ArchivedCameraFrame]]) -> tuple[int, str]:
        inspection_id, cameras = entry
        saved = min((int(x.result.get("saved_at_ms", 0)) for x in cameras.values()), default=0)
        try:
            return saved, f"{int(inspection_id):020d}"
        except ValueError:
            return saved, inspection_id

    def dataset_summary(self) -> dict[str, Any]:
        with self._lock:
            buckets = list(self._frames.values())
            cameras = sorted({camera_id for bucket in buckets for camera_id in bucket})
            return {
                "archive_root": str(self.archive_root),
                "bucket_count": len(buckets),
                "camera_ids": cameras,
                "buckets": [self._bucket_summary(bucket) for bucket in self._frames.values()],
            }

    @staticmethod
    def _bucket_summary(bucket: dict[int, ArchivedCameraFrame]) -> dict[str, Any]:
        first = next(iter(bucket.values()), None)
        return {
            "inspection_id": first.inspection_id if first else "",
            "camera_ids": sorted(bucket),
            "frame_ids": {str(camera_id): item.frame_id for camera_id, item in bucket.items()},
            "historical": {
                str(camera_id): {
                    "status": item.result.get("python_status", item.result.get("overall_pass")),
                    "overall_pass": item.result.get("overall_pass"),
                    "anomaly_score": item.result.get("anomaly_score"),
                    "geometry_status": item.result.get("geometry_status"),
                    "product_type": item.product_type,
                }
                for camera_id, item in sorted(bucket.items())
            },
        }

    def create_session(self, *, loop: bool = True, auto_reference: bool = True) -> EmulatorSession:
        with self._lock:
            bucket_ids = list(self._frames)
            if not bucket_ids:
                raise ValueError(f"No archived frames found in {self.archive_root}")
            session_id = uuid.uuid4().hex
            session_root = self.archive_root.parent / "emulator_sessions" / session_id
            service = InspectionService(
                learned_normals_dir=session_root / "accepted_normals",
                reviews_dir=session_root / "learning_reviews",
                session_wipe=False,
                learned_normals_session_wipe=False,
            )
            cameras = {camera_id: EmulatorCameraState() for camera_id in self.camera_ids}
            session = EmulatorSession(session_id, bucket_ids, 0, loop, service, cameras)
            self._sessions[session_id] = session
            if auto_reference:
                first_bucket = self._frames[bucket_ids[0]]
                for camera_id, item in first_bucket.items():
                    self._set_reference(session, camera_id, item)
            return session

    @property
    def camera_ids(self) -> list[int]:
        with self._lock:
            return sorted({camera_id for bucket in self._frames.values() for camera_id in bucket})

    def get_session(self, session_id: str) -> EmulatorSession:
        with self._lock:
            session = self._sessions.get(session_id)
        if session is None:
            raise KeyError(session_id)
        return session

    def current_bucket(self, session: EmulatorSession) -> dict[int, ArchivedCameraFrame]:
        with self._lock:
            return self._frames[session.bucket_ids[session.cursor]]

    def move(self, session: EmulatorSession, delta: int) -> dict[str, Any]:
        with session.lock:
            count = len(session.bucket_ids)
            if count == 0:
                raise ValueError("Dataset is empty")
            next_cursor = session.cursor + delta
            if session.loop:
                session.cursor = next_cursor % count
            else:
                session.cursor = max(0, min(count - 1, next_cursor))
            return self.session_state(session)

    def set_reference_from_bucket(self, session: EmulatorSession, camera_id: int, bucket_id: str) -> dict[str, Any]:
        with self._lock:
            item = self._frames.get(str(bucket_id), {}).get(int(camera_id))
        if item is None:
            raise ValueError(f"Camera {camera_id} is missing in bucket {bucket_id}")
        with session.lock:
            self._set_reference(session, camera_id, item)
            return self.camera_state(session, camera_id)

    def _set_reference(self, session: EmulatorSession, camera_id: int, item: ArchivedCameraFrame) -> None:
        frame = cv2.imread(str(item.frame_path), cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError(f"Cannot read reference frame: {item.frame_path}")
        # Production keeps a reference per camera/view.  The archive's
        # ``product_type`` is an analysis profile and can repeat on several
        # cameras, so use a private per-camera key for emulator references and
        # learned normals while still passing the original profile to settings.
        session.service.set_reference_frame(self._product_key(item), frame)
        state = session.cameras.setdefault(camera_id, EmulatorCameraState())
        state.reference_bucket_id = item.inspection_id
        state.reference_frame_id = item.frame_id

    def update_settings(self, session: EmulatorSession, camera_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        state = session.cameras.setdefault(camera_id, EmulatorCameraState())
        mode = str(payload.get("mode", state.mode)).lower()
        knobs = dict(payload.get("knobs") or state.knobs)
        if mode == "simple":
            overrides = expand_simple(knobs["threshold"], knobs["sensitivity"])
        elif mode == "pro":
            overrides = expand_pro(
                knobs["threshold"], knobs["noise_tolerance"], knobs["scratch_sensitivity"],
                knobs["edge_suppression"], knobs["text_handling"], knobs["preprocess_strength"],
            )
        else:
            raise ValueError("mode must be simple or pro")
        state.mode = mode
        state.knobs = knobs
        state.threshold = float(knobs["threshold"])
        state.temporary_overrides = overrides
        return self.camera_state(session, camera_id)

    def update_roi(self, session: EmulatorSession, camera_id: int, points: list[dict[str, Any]]) -> dict[str, Any]:
        if len(points) < 3:
            raise ValueError("ROI must contain at least 3 points")
        normalized = []
        for point in points:
            x = float(point["x"])
            y = float(point["y"])
            if not 0.0 <= x <= 1.0 or not 0.0 <= y <= 1.0:
                raise ValueError("ROI points must be in [0, 1]")
            normalized.append({"x": x, "y": y})
        state = session.cameras.setdefault(camera_id, EmulatorCameraState())
        state.roi_points = normalized
        item = self.current_bucket(session).get(camera_id)
        if item is None:
            raise ValueError(f"Camera {camera_id} is missing in current bucket")
        session.service.set_roi_polygon(
            self._product_key(item),
            [(point["x"], point["y"]) for point in normalized],
        )
        return self.camera_state(session, camera_id)

    def camera_state(self, session: EmulatorSession, camera_id: int) -> dict[str, Any]:
        state = session.cameras.setdefault(camera_id, EmulatorCameraState())
        return {
            "camera_id": camera_id,
            "reference_bucket_id": state.reference_bucket_id,
            "reference_frame_id": state.reference_frame_id,
            "mode": state.mode,
            "knobs": state.knobs,
            "last_run_ms": state.last_run_ms,
            "roi_points": state.roi_points,
        }

    def run_current(self, session: EmulatorSession, camera_id: Optional[int] = None) -> dict[str, Any]:
        bucket = self.current_bucket(session)
        selected = [camera_id] if camera_id is not None else sorted(bucket)
        items = [bucket[int(selected_camera)] for selected_camera in selected if int(selected_camera) in bucket]
        bucket_started = time.perf_counter()
        # The production backend uses a bounded inspection worker pool.  A
        # replay should exercise the same concurrency instead of turning ten
        # cameras into a serial benchmark.
        with ThreadPoolExecutor(max_workers=max(1, min(10, len(items)))) as executor:
            futures = [executor.submit(self._run_camera_safe, session, item) for item in items]
            results = {item.camera_id: future.result() for item, future in zip(items, futures)}
        if bucket:
            with session.lock:
                bucket_key = next(iter(bucket.values())).inspection_id
                merged = dict(session.results.get(bucket_key, {}))
                merged.update(results)
                session.results[bucket_key] = merged
                session.last_bucket_time_ms = (time.perf_counter() - bucket_started) * 1000.0
        return self.session_state(session, include_results=True)

    def _run_camera_safe(self, session: EmulatorSession, item: ArchivedCameraFrame) -> dict[str, Any]:
        try:
            return self._run_camera(session, item)
        except Exception as exc:
            return {
                "camera_id": item.camera_id,
                "frame_id": item.frame_id,
                "inspection_id": item.inspection_id,
                "product_type": item.product_type,
                "historical": item.result,
                "source_frame_url": f"/local-emulator/sessions/{session.session_id}/artifact/{item.camera_id}/{item.inspection_id}/frame.jpg",
                "error": str(exc),
            }

    def _run_camera(self, session: EmulatorSession, item: ArchivedCameraFrame) -> dict[str, Any]:
        state = session.cameras.setdefault(item.camera_id, EmulatorCameraState())
        if len(state.roi_points) < 3:
            raise ValueError("ROI is not set for this camera")
        reference_key = self._product_key(item)
        if session.service.get_reference(reference_key) is None:
            reference_item = item
            if state.reference_bucket_id:
                reference_item = self._frames.get(state.reference_bucket_id, {}).get(item.camera_id, item)
            self._set_reference(session, item.camera_id, reference_item)
        # Re-apply the camera ROI for every archived profile.  Some datasets
        # contain a different analysis_profile in later buckets, while the
        # operator's ROI belongs to the physical camera view.
        session.service.set_roi_polygon(
            self._product_key(item),
            [(point["x"], point["y"]) for point in state.roi_points],
        )
        # This is the replay equivalent of capture_started -> final verdict:
        # start before reading frame.jpg and stop only after inspect_frame()
        # has returned its status.
        started = time.perf_counter()
        frame = cv2.imread(str(item.frame_path), cv2.IMREAD_COLOR)
        if frame is None:
            raise ValueError(f"Cannot read frame: {item.frame_path}")
        result = session.service.inspect_frame(
            product_type=self._product_key(item),
            frame=frame,
            threshold=state.threshold,
            include_visuals=True,
            include_heatmap_u8=True,
            analysis_profile=item.product_type,
            temporary_analysis_overrides=state.temporary_overrides or None,
            pre_learning_heatmap=True,
            store_learning_review=True,
        )
        payload = to_inspect_with_visuals_response(result).model_dump()
        state.last_run_ms = (time.perf_counter() - started) * 1000.0
        state.last_result = payload
        return {
            "camera_id": item.camera_id,
            "frame_id": item.frame_id,
            "inspection_id": item.inspection_id,
            "product_type": item.product_type,
            "historical": item.result,
            "source_frame_url": f"/local-emulator/sessions/{session.session_id}/artifact/{item.camera_id}/{item.inspection_id}/frame.jpg",
            "historical_heatmap_url": f"/local-emulator/sessions/{session.session_id}/artifact/{item.camera_id}/{item.inspection_id}/heatmap.png",
            "new": payload,
            "processing_ms": state.last_run_ms,
            "capture_to_verdict_ms": state.last_run_ms,
        }

    @staticmethod
    def _product_key(item: ArchivedCameraFrame) -> str:
        return f"emulator-camera-{item.camera_id}-{item.product_type}"

    def session_state(self, session: EmulatorSession, *, include_results: bool = False) -> dict[str, Any]:
        bucket = self.current_bucket(session)
        state = {
            "session_id": session.session_id,
            "cursor": session.cursor,
            "bucket_count": len(session.bucket_ids),
            "loop": session.loop,
            "bucket": self._bucket_summary(bucket),
            "cameras": {str(camera_id): self.camera_state(session, camera_id) for camera_id in self.camera_ids},
            "last_bucket_time_ms": session.last_bucket_time_ms,
        }
        if include_results:
            state["results"] = session.results.get(next(iter(bucket.values())).inspection_id, {}) if bucket else {}
        return state

    def list_reviews(self, session: EmulatorSession, product_type: Optional[str] = None) -> list[dict[str, Any]]:
        return session.service.list_learning_reviews(product_type=product_type)

    def get_review(self, session: EmulatorSession, inspection_id: str) -> Optional[dict[str, Any]]:
        return session.service.get_learning_review(inspection_id)

    def accept_all(self, session: EmulatorSession, inspection_id: str, note: str = "") -> dict[str, Any]:
        return session.service.accept_review_all_as_normal(inspection_id, note=note)

    def accept_one(
        self,
        session: EmulatorSession,
        inspection_id: str,
        defect_id: str,
        note: str = "",
    ) -> dict[str, Any]:
        return session.service.accept_review_defect_as_normal(inspection_id, defect_id, note=note)

    def accepted_cases(self, session: EmulatorSession, product_type: Optional[str] = None) -> list[dict[str, Any]]:
        return session.service.list_accepted_normal_cases(product_type=product_type)

    def session_artifact(self, session: EmulatorSession, camera_id: int, bucket_id: str, kind: str) -> tuple[bytes, str]:
        with self._lock:
            item = self._frames.get(str(bucket_id), {}).get(int(camera_id))
        if item is None:
            raise KeyError(bucket_id)
        if kind == "frame.jpg":
            return item.frame_path.read_bytes(), "image/jpeg"
        if kind == "result.json":
            return json.dumps(item.result, ensure_ascii=False).encode("utf-8"), "application/json"
        if kind == "heatmap.png":
            if item.heatmap_path is None:
                raise FileNotFoundError("Historical heatmap is missing")
            raw = np.fromfile(item.heatmap_path, dtype=np.uint8)
            width = int(item.result.get("heatmap", {}).get("width", 0))
            height = int(item.result.get("heatmap", {}).get("height", 0))
            if width <= 0 or height <= 0 or raw.size < width * height:
                raise ValueError("Invalid historical heatmap dimensions")
            gray = raw[: width * height].reshape(height, width)
            color = cv2.applyColorMap(gray, cv2.COLORMAP_JET)
            ok, encoded = cv2.imencode(".png", color)
            if not ok:
                raise ValueError("Cannot encode historical heatmap")
            return encoded.tobytes(), "image/png"
        raise KeyError(kind)
