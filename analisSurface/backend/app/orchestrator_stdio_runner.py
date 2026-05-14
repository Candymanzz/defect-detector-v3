"""
Точка входа для оркестратора: бинарный протокол IMLB по stdin/stdout (как python-detectors).
Запуск: из корня репозитория — python -m app.orchestrator_stdio_runner (cwd = корень проекта).
"""
from __future__ import annotations

import atexit
import json
import mmap
import os
import struct
import sys
from typing import Any

import cv2
import numpy as np

from app.services.inspection_service import InspectionService

MAGIC = b"IMLB"
VERSION = 1
MSG_COMMAND = 1
MSG_RESPONSE = 2
MSG_ERROR = 3
_SHM_CACHE: dict[str, tuple[int, mmap.mmap, int]] = {}


def _cleanup_shm_cache() -> None:
    for _, (fd, mm, _) in list(_SHM_CACHE.items()):
        try:
            mm.close()
        except Exception:
            pass
        try:
            os.close(fd)
        except Exception:
            pass
    _SHM_CACHE.clear()


atexit.register(_cleanup_shm_cache)

_SERVICE = InspectionService()


def _read_exact(n: int) -> bytes:
    data = b""
    while len(data) < n:
        chunk = sys.stdin.buffer.read(n - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def read_message() -> tuple[int, dict[str, Any], bytes]:
    prefix = _read_exact(16)
    magic, version, msg_type, _reserved, header_len, payload_len = struct.unpack(">4sBBHII", prefix)
    if magic != MAGIC or version != VERSION:
        raise ValueError("Unsupported protocol")
    header_raw = _read_exact(header_len)
    payload = _read_exact(payload_len)
    header = json.loads(header_raw.decode("utf-8")) if header_raw else {}
    return msg_type, header, payload


def write_message(msg_type: int, header: dict[str, Any], payload: bytes = b"") -> None:
    header_bytes = json.dumps(header, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    prefix = struct.pack(">4sBBHII", MAGIC, VERSION, msg_type, 0, len(header_bytes), len(payload))
    sys.stdout.buffer.write(prefix)
    sys.stdout.buffer.write(header_bytes)
    if payload:
        sys.stdout.buffer.write(payload)
    sys.stdout.buffer.flush()


def _shm_file_path(shm_name: str) -> str:
    key = shm_name.lstrip("/").replace("/", "_")
    if os.name == "nt":
        root = os.environ.get("LOCALAPPDATA") or os.environ.get("TEMP") or "."
        d = os.path.join(root, "iml_shm")
        os.makedirs(d, exist_ok=True)
        return os.path.join(d, key)
    return os.path.join("/dev/shm", key)


def _get_shm_mapping(shm_file: str) -> tuple[int, mmap.mmap, int]:
    cached = _SHM_CACHE.get(shm_file)
    if cached is not None:
        fd, mm, size = cached
        try:
            current_size = os.fstat(fd).st_size
            if current_size == size:
                return cached
        except OSError:
            pass
        try:
            mm.close()
        except Exception:
            pass
        try:
            os.close(fd)
        except Exception:
            pass
        _SHM_CACHE.pop(shm_file, None)

    fd = os.open(shm_file, os.O_RDONLY)
    size = os.fstat(fd).st_size
    mm = mmap.mmap(fd, 0, access=mmap.ACCESS_READ)
    _SHM_CACHE[shm_file] = (fd, mm, size)
    return fd, mm, size


def _read_frame_from_shm(header: dict[str, Any]) -> np.ndarray:
    shm_name = str(header["shm_name"])
    width = int(header["width"])
    height = int(header["height"])
    stride = int(header.get("stride", width * 3))
    offset = int(header.get("shm_offset", 0))

    shm_file = _shm_file_path(shm_name)
    _, mm, size = _get_shm_mapping(shm_file)
    byte_len = stride * height
    if offset < 0 or offset + byte_len > size:
        raise ValueError(f"shm window out of range: offset={offset} len={byte_len} size={size}")
    return np.ndarray(
        (height, width, 3),
        dtype=np.uint8,
        buffer=mm,
        offset=offset,
        strides=(stride, 3, 1),
    )


def _decode_image_bytes(payload: bytes) -> np.ndarray:
    data = np.frombuffer(payload, dtype=np.uint8)
    image = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("could not decode image payload")
    return image


def _inspection_result_to_response_header(result: Any, detector_id: str) -> dict[str, Any]:
    """Только JSON-сериализуемые поля для заголовка MSG_RESPONSE (без numpy)."""
    status = str(getattr(result, "status", "ERROR"))
    ok = status not in {"БРАК", "FAIL", "ERROR"}
    return {
        "status": status,
        "ok": ok,
        "anomaly_score": float(getattr(result, "anomaly_score", 0.0)),
        "threshold": float(getattr(result, "threshold", 0.0)),
        "product_type": str(getattr(result, "product_type", "")),
        "detector_id": str(detector_id or getattr(result, "detector_id", "")),
        "raw_anomaly_score": float(getattr(result, "raw_anomaly_score", 0.0)),
        "rechecked_zones_count": int(getattr(result, "rechecked_zones_count", 0)),
        "recheck_adjustment": float(getattr(result, "recheck_adjustment", 0.0)),
        "rechecked_zone_ids": getattr(result, "rechecked_zone_ids", None) or [],
    }


def main() -> int:
    default_detector = os.environ.get("IML_DEFAULT_DETECTOR", "kopcheni")

    while True:
        try:
            msg_type, header, payload = read_message()
        except EOFError:
            return 0
        except Exception as exc:
            write_message(MSG_ERROR, {"error": str(exc)})
            continue

        if msg_type != MSG_COMMAND:
            write_message(MSG_ERROR, {"error": f"unexpected msg_type={msg_type}"})
            continue

        op = str(header.get("op", ""))

        try:
            if op == "health":
                write_message(
                    MSG_RESPONSE,
                    {
                        "status": "ok",
                        "service": "kopcheni-service",
                        "default_detector": default_detector,
                    },
                )
            elif op == "set_reference":
                product_type = str(header["product_type"])
                _SERVICE.set_reference(product_type, payload)
                write_message(
                    MSG_RESPONSE,
                    {"status": "ok", "product_type": product_type, "detector_id": str(header.get("detector_id", default_detector))},
                )
            elif op == "set_reference_shm":
                product_type = str(header["product_type"])
                frame = _read_frame_from_shm(header)
                _SERVICE.set_reference_frame(product_type, frame.copy())
                write_message(
                    MSG_RESPONSE,
                    {"status": "ok", "product_type": product_type, "detector_id": str(header.get("detector_id", default_detector))},
                )
            elif op == "inspect":
                product_type = str(header["product_type"])
                threshold = header.get("threshold")
                if threshold is not None:
                    threshold = float(threshold)
                include_visuals = bool(header.get("include_visuals", False))
                detector_id = str(header.get("detector_id", default_detector))
                frame = _decode_image_bytes(payload)
                result = _SERVICE.inspect_frame(
                    product_type=product_type,
                    frame=frame,
                    threshold=threshold,
                    include_visuals=include_visuals,
                    detector_id=detector_id,
                )
                write_message(MSG_RESPONSE, _inspection_result_to_response_header(result, detector_id))
            elif op == "inspect_shm":
                product_type = str(header["product_type"])
                threshold = header.get("threshold")
                if threshold is not None:
                    threshold = float(threshold)
                include_visuals = bool(header.get("include_visuals", False))
                detector_id = str(header.get("detector_id", default_detector))
                frame = _read_frame_from_shm(header)
                result = _SERVICE.inspect_frame(
                    product_type=product_type,
                    frame=frame.copy(),
                    threshold=threshold,
                    include_visuals=include_visuals,
                    detector_id=detector_id,
                )
                write_message(MSG_RESPONSE, _inspection_result_to_response_header(result, detector_id))
            elif op == "inspect_stub":
                write_message(
                    MSG_RESPONSE,
                    {
                        "camera_id": header.get("camera_id"),
                        "frame_id": header.get("frame_id"),
                        "status": "OK",
                        "anomaly_score": 0.0,
                        "threshold": float(header.get("threshold", 0.25)),
                        "ok": True,
                    },
                )
            elif op == "inject_exit":
                raise SystemExit(42)
            elif op == "inject_timeout_ms":
                import time

                timeout_ms = int(header.get("timeout_ms", 1000))
                time.sleep(max(0, timeout_ms) / 1000.0)
                write_message(MSG_RESPONSE, {"status": "timeout_injected"})
            elif op == "inject_broken_response":
                sys.stdout.buffer.write(b"BROKEN_RESPONSE")
                sys.stdout.buffer.flush()
            else:
                write_message(MSG_ERROR, {"error": f"unknown op={op}"})
        except Exception as exc:
            write_message(MSG_ERROR, {"error": str(exc), "op": op})


if __name__ == "__main__":
    raise SystemExit(main())
